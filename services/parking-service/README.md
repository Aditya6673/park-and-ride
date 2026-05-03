# Parking Service — Park & Ride Platform

> **Microservice:** `parking-service`  
> **Port:** `8082`  
> **Responsibility:** Parking lot discovery, slot booking, QR code access, and real-time availability.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Technology Stack](#3-technology-stack)
4. [Project Structure](#4-project-structure)
5. [Domain Model](#5-domain-model)
6. [Database Schema](#6-database-schema)
7. [Booking Algorithm](#7-booking-algorithm)
8. [API Endpoints](#8-api-endpoints)
9. [Request & Response DTOs](#9-request--response-dtos)
10. [Real-time Availability (WebSocket)](#10-real-time-availability-websocket)
11. [Configuration](#11-configuration)
12. [Running Locally](#12-running-locally)
13. [Testing](#13-testing)
14. [Known Limitations & TODOs](#14-known-limitations--todos)

---

## 1. Overview

The Parking Service manages the full lifecycle of a parking booking — from lot discovery and slot assignment through to check-in and QR code gate validation. It is a downstream consumer of JWTs issued by the `auth-service` and publishes booking events to Kafka for the notification and payment services.

**What it does:**
- Search parking lots by city or GPS proximity (Haversine)
- Real-time slot availability via Redis cache + WebSocket push
- Distributed slot booking with Redisson `RLock` (prevents double-booking in a multi-instance deployment)
- Double-booking prevention enforced at the DB level via PostgreSQL `EXCLUDE` constraint
- JWT-signed QR code generation (offline gate validation)
- Scheduled no-show auto-cancellation (30-minute grace period)
- Booking CRUD with per-user active booking limit (max 3)

**What it does NOT do:**
- Process payments — publishes `BOOKING_CONFIRMED` events to Kafka; payment-service handles charging
- Send notifications — notification-service consumes Kafka events
- Issue or validate JWT identity — trusts the JWT issued by auth-service

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Parking Service                          │
│                                                              │
│  REST Controllers  →  Service Layer      →  Repositories     │
│  (ParkingLotCtrl     (BookingService        (ParkingLot      │
│   BookingCtrl)        SlotAssignment         ParkingSlot     │
│                       AvailabilitySvc        Booking)        │
│                       QRCodeService)                         │
│         ↕                   ↕                    ↕           │
│   Spring Security      Redis (cache)       PostgreSQL        │
│   (JwtAuthFilter)      Redisson (locks)    (JPA + Flyway)    │
│   WebSocket/STOMP      Kafka (producer)                      │
└──────────────────────────────────────────────────────────────┘
          ↑                                    ↓
   HTTP Clients                      booking-events topic
   WebSocket Clients                 (payment-service,
   (Frontend Map)                    notification-service)
```

### Shared Modules Used

| Module | Purpose |
|---|---|
| `common-dto` | `ApiResponse<T>` — standard response envelope |
| `common-security` | `JwtUtil` (token validation), `SecurityConstants` (claim keys, public endpoints) |
| `common-events` | `BookingEvent` — Kafka message schema shared with payment and notification services |

---

## 3. Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Framework | Spring Boot 3.4.5 | Spring Framework 6.2 |
| Language | Java 21 | LTS release |
| Security | Spring Security 6 | Stateless, JWT-based (no UserDetails lookup) |
| JWT | JJWT (io.jsonwebtoken) | Token validation via `common-security` |
| ORM | Spring Data JPA + Hibernate 6 | `ddl-auto=validate` — Flyway owns schema |
| Schema Migration | Flyway | V1 → V5 applied on startup |
| Database | PostgreSQL 16 | Dedicated instance (`parking_db`, port 5433) |
| Availability Cache | Redis 7.2 | Cache-aside pattern; 60s TTL per lot |
| Distributed Locks | Redisson 3.27 | `RLock` per slot UUID; 5s wait / 10s lease |
| QR Codes | ZXing 3.5.3 | 400×400 PNG; H-level error correction |
| Real-time Updates | Spring WebSocket (STOMP) | SockJS fallback; pushes to `/topic/availability/{lotId}` |
| Message Bus | Apache Kafka | Producer only — `booking-events` topic |
| Boilerplate | Lombok | `@Builder`, `@Getter`, `@Slf4j` |
| API Docs | SpringDoc OpenAPI 2.8.0 | Swagger UI at `/swagger-ui.html` |
| Observability | Micrometer + Actuator | Prometheus-ready |
| Build | Maven 3 (multi-module) | Parent POM at repo root |
| Container | Docker (multi-stage) | Non-root user, layered JARs |

---

## 4. Project Structure

```
services/parking-service/
├── src/main/java/com/parkride/parking/
│   ├── ParkingServiceApplication.java       # Spring Boot entry point (@EnableScheduling)
│   ├── config/
│   │   ├── RedisConfig.java                 # RedisTemplate<String,String> + JwtUtil @Bean
│   │   ├── RedissonConfig.java              # RedissonClient for distributed slot locks
│   │   ├── WebSocketConfig.java             # STOMP broker, /ws endpoint, SockJS
│   │   └── OpenApiConfig.java               # Swagger info + bearerAuth scheme
│   ├── controller/
│   │   ├── ParkingLotController.java        # GET /api/v1/parking/lots/** (public)
│   │   └── BookingController.java           # /api/v1/bookings/** (JWT required)
│   ├── domain/
│   │   ├── ParkingLot.java                  # JPA entity — lot aggregate
│   │   ├── ParkingSlot.java                 # JPA entity — individual slot
│   │   ├── Booking.java                     # JPA entity — booking with @Version lock
│   │   ├── BookingStatus.java               # PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW
│   │   ├── SlotType.java                    # CAR, MOTORCYCLE, EV, HANDICAPPED, TRUCK
│   │   └── SlotStatus.java                  # AVAILABLE, RESERVED, OCCUPIED, MAINTENANCE
│   ├── dto/
│   │   ├── CreateBookingRequest.java        # POST /bookings body
│   │   ├── BookingResponse.java             # Booking response payload
│   │   ├── ParkingLotResponse.java          # Lot response with live availableSlots
│   │   └── ParkingSlotResponse.java         # Slot response
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java      # @RestControllerAdvice
│   │   ├── ParkingException.java            # 400 — general business rule violation
│   │   ├── SlotUnavailableException.java    # 409 — no slot available
│   │   └── BookingNotFoundException.java    # 404 — booking not found or wrong user
│   ├── repository/
│   │   ├── ParkingLotRepository.java        # city search, Haversine proximity JPQL
│   │   ├── ParkingSlotRepository.java       # availability query (time-overlap aware)
│   │   └── BookingRepository.java           # active count, no-show candidates
│   ├── security/
│   │   ├── SecurityConfig.java              # Filter chain, CORS, STATELESS, 401 entry point
│   │   └── JwtAuthFilter.java               # Validates JWT, sets SecurityContext
│   ├── service/
│   │   ├── AvailabilityService.java         # Redis cache-aside for slot counts
│   │   ├── SlotAssignmentService.java       # 10-step distributed booking algorithm
│   │   ├── QRCodeService.java               # ZXing PNG generation + JJWT signing
│   │   └── BookingService.java              # CRUD + scheduled housekeeping
│   └── websocket/
│       └── AvailabilityBroadcastService.java # STOMP broadcast after every state change
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       ├── V1__create_parking_lots.sql
│       ├── V2__create_parking_slots.sql
│       ├── V3__create_bookings.sql          # GIST EXCLUDE constraint for double-booking
│       ├── V4__seed_sample_data.sql         # 3 lots + 25 slots (Delhi, Bengaluru, Mumbai)
│       └── V5__fix_coordinate_column_types.sql
├── src/test/java/com/parkride/parking/
│   ├── service/
│   │   ├── BookingServiceTest.java          # Unit tests (Mockito) — 9 tests
│   │   └── SlotAssignmentServiceTest.java   # Unit tests (Mockito) — 6 tests
│   └── controller/
│       └── ParkingControllerIT.java         # Integration tests (live DB) — 11 tests
├── Dockerfile                               # Multi-stage production image
└── pom.xml
```

---

## 5. Domain Model

### `ParkingLot` entity

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Auto-generated primary key |
| `name` | `VARCHAR(200)` | Display name |
| `address` | `TEXT` | Street address |
| `city` / `state` | `VARCHAR(100)` | For city-based search |
| `latitude` / `longitude` | `DOUBLE PRECISION` | WGS84 coordinates for proximity search |
| `totalSlots` | `INT` | Physical slot count (informational) |
| `contactPhone` | `VARCHAR(20)` | Optional |
| `imageUrl` | `VARCHAR(500)` | Optional |
| `active` | `BOOLEAN` | Soft-disable without deleting |
| `createdAt` / `updatedAt` | `TIMESTAMPTZ` | Hibernate auto-managed |

### `ParkingSlot` entity

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Auto-generated primary key |
| `lot` | `ParkingLot` | Many-to-one FK |
| `slotNumber` | `VARCHAR(20)` | e.g. `A-001` |
| `slotType` | `SlotType` enum | `CAR`, `MOTORCYCLE`, `EV`, `HANDICAPPED`, `TRUCK` |
| `status` | `SlotStatus` enum | `AVAILABLE`, `RESERVED`, `OCCUPIED`, `MAINTENANCE` |
| `pricePerHour` | `DECIMAL(10,2)` | In INR |
| `floor` | `VARCHAR(10)` | e.g. `G`, `1`, `B1` |
| `positionIndex` | `INT` | Sort order for slot scoring |
| `active` | `BOOLEAN` | Soft-disable |

### `Booking` entity

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Auto-generated primary key |
| `userId` | `UUID` | From JWT — no FK to users table (cross-service) |
| `slot` | `ParkingSlot` | Many-to-one FK |
| `startTime` / `endTime` | `TIMESTAMPTZ` | Booked window |
| `status` | `BookingStatus` enum | See lifecycle below |
| `totalAmount` | `DECIMAL(10,2)` | `pricePerHour × hours` |
| `qrToken` | `TEXT` | JWT-signed QR code token |
| `checkInTime` | `TIMESTAMPTZ` | Set on gate scan |
| `cancellationReason` | `TEXT` | Optional |
| `version` | `BIGINT` | Optimistic lock (`@Version`) |
| `createdAt` | `TIMESTAMPTZ` | Immutable |

### Booking Status Lifecycle

```
PENDING → CONFIRMED → CHECKED_IN → COMPLETED
                  ↓
              CANCELLED
                  ↓
               NO_SHOW   (auto after 30 min grace)
```

---

## 6. Database Schema

Five Flyway migrations run automatically on startup:

```
V1__create_parking_lots.sql        — parking_lots table, indexes on city + coordinates
V2__create_parking_slots.sql       — parking_slots table, composite unique (lot, slotNumber)
V3__create_bookings.sql            — bookings table + GIST EXCLUDE constraint (no_double_booking)
V4__seed_sample_data.sql           — 3 real lots (Delhi/Bengaluru/Mumbai) + ~25 slots
V5__fix_coordinate_column_types.sql — ALTER latitude/longitude to DOUBLE PRECISION
```

**Double-booking prevention** is enforced at two levels:
1. **Application layer** — Redisson `RLock` per slot; re-verify inside lock (TOCTOU-safe)
2. **Database layer** — PostgreSQL `EXCLUDE USING gist` constraint on `(slot_id, tstzrange(start_time, end_time))`

---

## 7. Booking Algorithm

`SlotAssignmentService.assignAndBook()` runs a 10-step distributed algorithm:

```
1.  Read Redis availability cache → fast-reject if count = 0
2.  Query DB for available slots for the time window
3.  Reject if empty list returned
4.  Score slots: prefer requested slot, then lowest position index
5.  Acquire Redisson RLock on slot UUID (5s wait, 10s lease)
6.  Re-verify slot is still free inside the lock (TOCTOU prevention)
7.  Calculate amount: pricePerHour × duration (fractional hours supported)
8.  Persist PENDING Booking → generate JWT QR token → set CONFIRMED
9.  Update slot status to RESERVED
10. Refresh Redis cache → broadcast via WebSocket → publish Kafka event
    (lock released in finally block)
```

**QR Token** is a JWT signed with the service's secret. Claims: `sub` (bookingId), `uid`, `sid`, `lid`, expiry = `endTime + 1 hour`. Gate scanners verify offline.

**Scheduled jobs:**
- Every 15 min — auto-cancel CONFIRMED bookings with no check-in after 30-min grace → `NO_SHOW`
- Every 10 min — clean up stale PENDING bookings older than 15 min (abandoned checkouts)

---

## 8. API Endpoints

Base URL: `http://localhost:8082`  
Interactive docs: `http://localhost:8082/swagger-ui.html`

### Parking Lots (`/api/v1/parking/lots`) — Public

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/lots?city=Delhi` | Public | List active lots by city (paginated) |
| `GET` | `/lots/nearby?lat=&lng=&radiusKm=` | Public | Proximity search (default 5 km radius) |
| `GET` | `/lots/{lotId}` | Public | Lot details with live `availableSlots` count |
| `GET` | `/lots/{lotId}/slots` | Public | All active slots in a lot |

### Bookings (`/api/v1/bookings`) — JWT Required

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/bookings` | Bearer | Create booking (runs distributed assignment) |
| `GET` | `/bookings` | Bearer | List own bookings (paginated, newest first) |
| `GET` | `/bookings/{id}` | Bearer | Get single booking |
| `DELETE` | `/bookings/{id}` | Bearer | Cancel booking (releases slot, refreshes cache) |
| `GET` | `/bookings/{id}/qr-token` | Bearer | Get raw JWT QR token string |
| `GET` | `/bookings/{id}/qr` | Bearer | Get QR code as PNG image (400×400) |

### WebSocket

| Endpoint | Description |
|---|---|
| `ws://host:8082/ws` | STOMP connection endpoint (SockJS fallback available) |
| `/topic/availability/{lotId}` | Subscribe to receive availability updates after each booking change |

---

## 9. Request & Response DTOs

### `CreateBookingRequest`

```json
{
  "slotId":           "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "startTime":        "2026-05-10T10:00:00Z",
  "endTime":          "2026-05-10T12:00:00Z",
  "preferredSlotType": "CAR"
}
```

Validation: `startTime` and `endTime` must be future; `endTime > startTime`; min 30 min; max 24 hours.

### `BookingResponse`

```json
{
  "success": true,
  "message": "Booking confirmed",
  "data": {
    "id":          "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "userId":      "550e8400-e29b-41d4-a716-446655440000",
    "slotId":      "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "slotNumber":  "A-001",
    "lotId":       "a3bb189e-8bf9-3888-9912-ace4e6543002",
    "lotName":     "Connaught Place Parking",
    "startTime":   "2026-05-10T10:00:00Z",
    "endTime":     "2026-05-10T12:00:00Z",
    "status":      "CONFIRMED",
    "totalAmount": 100.00,
    "qrToken":     "eyJhbGciOiJIUzI1NiJ9...",
    "createdAt":   "2026-05-03T07:00:00Z"
  }
}
```

### `ParkingLotResponse`

```json
{
  "id":             "a3bb189e-8bf9-3888-9912-ace4e6543002",
  "name":           "Connaught Place Parking",
  "city":           "New Delhi",
  "latitude":       28.6315,
  "longitude":      77.2167,
  "totalSlots":     50,
  "availableSlots": 12,
  "active":         true
}
```

`availableSlots` is injected at query time from the Redis cache — not stored in the DB.

### WebSocket Availability Message

```json
{
  "lotId":          "a3bb189e-8bf9-3888-9912-ace4e6543002",
  "availableSlots": 11
}
```

### Error Response (all errors)

```json
{
  "success": false,
  "message": "No available slots for the requested time window"
}
```

HTTP status codes: `400` validation/business rule, `401` missing JWT, `403` forbidden, `404` booking not found, `409` slot unavailable or double-booking, `500` unexpected.

---

## 10. Real-time Availability (WebSocket)

The service pushes an availability update to `/topic/availability/{lotId}` after **every** booking creation, cancellation, or no-show auto-cancellation.

### JavaScript Client Example

```js
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8082/ws'),
  onConnect: () => {
    client.subscribe(`/topic/availability/${lotId}`, (msg) => {
      const { availableSlots } = JSON.parse(msg.body);
      updateMapMarker(lotId, availableSlots);
    });
  },
});
client.activate();
```

### Availability Cache (Redis)

```
Key:   availability:{lotId}    (e.g. availability:a3bb189e-...)
Value: "12"                    (available slot count as string)
TTL:   60 seconds
```

On cache miss, the service queries `SELECT COUNT(*) FROM parking_slots WHERE lot_id = ? AND status = 'AVAILABLE'` and repopulates. Cache is also explicitly refreshed after every booking state change.

---

## 11. Configuration

### `application.properties` (key settings)

```properties
server.port=8082
spring.application.name=parking-service

# Database (dedicated parking instance)
spring.datasource.url=jdbc:postgresql://localhost:5433/parking_db
spring.datasource.username=parking_user
spring.datasource.password=parking_secret

# JPA — Flyway owns the schema
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Redis — availability cache
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=redis_secret

# Kafka — booking events producer
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true

# JWT secret — MUST match auth-service and be overridden in production
security.jwt.secret=dev-secret-change-this-in-production-min32chars
```

### Environment Variables for Production

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `SPRING_DATA_REDIS_PASSWORD` | Redis password |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `SECURITY_JWT_SECRET` | JWT signing secret (must match auth-service) |

---

## 12. Running Locally

### Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop

### Step 1 — Start infrastructure

```powershell
# From repo root
docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres-parking redis
```

Wait for both containers to show `(healthy)`:

```powershell
docker-compose -f infrastructure/docker/docker-compose.yml ps
```

### Step 2 — Build shared modules

```powershell
mvn install -DskipTests -pl shared/common-dto,shared/common-events,shared/common-security
```

### Step 3 — Start the service

```powershell
mvn spring-boot:run -pl services/parking-service
```

Service starts at `http://localhost:8082`. Flyway automatically applies all 5 migrations on first start, including seeding 3 real parking lots and ~25 slots.

### Step 4 — Test with Swagger UI

Open: `http://localhost:8082/swagger-ui.html`

**Quick test flow:**

1. Get a JWT from auth-service (port 8081) → click **Authorize** in Swagger
2. Search lots by city:
```
GET http://localhost:8082/api/v1/parking/lots?city=New Delhi
```
3. Create a booking:
```
POST http://localhost:8082/api/v1/bookings
Authorization: Bearer <your-jwt>
Content-Type: application/json

{
  "slotId":    "<slotId from step 2>",
  "startTime": "2026-06-01T10:00:00Z",
  "endTime":   "2026-06-01T12:00:00Z"
}
```
4. Get the QR code PNG:
```
GET http://localhost:8082/api/v1/bookings/<bookingId>/qr
Authorization: Bearer <your-jwt>
```

---

## 13. Testing

### Unit Tests

```powershell
mvn test -pl services/parking-service -Dtest="BookingServiceTest,SlotAssignmentServiceTest"
```

| Test class | Tests | What it covers |
|---|---|---|
| `BookingServiceTest` | 9 | Create booking, max active limit, time window validation (too short/long), get booking, cancel, pagination |
| `SlotAssignmentServiceTest` | 6 | Happy path, Redis fast-reject (cache=0), no DB slots, lock timeout, TOCTOU race condition, amount calculation (1.5h × ₹50 = ₹75) |

All dependencies mocked with Mockito — no database, Redis, or Redisson required.

### Integration Tests

Require Docker running with `postgres-parking` and `redis` containers healthy.

```powershell
# Start containers first
docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres-parking redis

# Run IT tests
mvn "-Dspring.profiles.active=test" test -pl services/parking-service "-Dtest=ParkingControllerIT"
```

Uses `MockMvc` against the full Spring context connected to the live `parking_db`.

| Test | What it covers |
|---|---|
| `listLots_returnsSeededData` | V4 seed data is accessible via GET |
| `getLot_public_success` | Lot details without JWT |
| `getSlots_public_success` | Slot list without JWT |
| `createBooking_authenticated_success` | Full booking flow → 201 + CONFIRMED + QR token |
| `createBooking_noJwt_unauthorized` | Missing JWT → 401 |
| `getBooking_owner_success` | Owner can read own booking |
| `getBooking_wrongUser_notFound` | Another user's booking → 404 |
| `getQrImage_success` | Returns PNG bytes |
| `cancelBooking_owner_success` | Cancel → DB status = CANCELLED |
| `concurrentBooking_exactlyOneSucceeds` | Two threads race same slot → exactly 1×201, 1×409 |

### Run all tests

```powershell
mvn "-Dspring.profiles.active=test" test -pl services/parking-service
```

Expected: **26/26 tests pass** (15 unit + 11 integration).

---

## 14. Known Limitations & TODOs

| # | Item | Phase |
|---|---|---|
| 1 | **Kafka events not consumed** — Parking service publishes `BOOKING_CONFIRMED` / `BOOKING_CANCELLED` events to `booking-events` but no consumer exists yet. Payment and notification services will consume these. | Phase 3 |
| 2 | **No check-in endpoint** — Gate validation logic (QR scan → `CHECKED_IN` status update) is not yet exposed as a REST endpoint. The `QRCodeService.validateAndExtractBookingId()` method exists and is ready. | Next sprint |
| 3 | **No payment integration** — Bookings are confirmed without payment. A `PAYMENT_REQUIRED` status and payment flow will be added in Phase 3. | Phase 3 |
| 4 | **Redisson requires same Redis as cache** — Both `RedisTemplate` (cache) and `RedissonClient` (locks) connect to the same Redis instance. In production these should be separate instances or use Redis Cluster. | Phase 3 |
| 5 | **WebSocket authentication** — The `/ws` endpoint is currently `permitAll()`. In production, STOMP `CONNECT` frames should carry the JWT and be validated before subscription is allowed. | Phase 3 |
| 6 | **No admin lot management endpoints** — `POST /lots` and `PUT /lots/{id}` are secured to `ROLE_ADMIN` but not yet implemented (no admin controller). | Next sprint |
| 7 | **Proximity search has no pagination** — `findNearby()` returns all lots within the radius with no limit. Should add `LIMIT` and distance-ordered pagination. | Minor improvement |
