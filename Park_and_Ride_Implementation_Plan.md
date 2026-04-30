# Park & Ride: Smart Parking & Last-Mile Connectivity
## Complete Implementation Plan — System Design to Deployment

**Tech Stack:** Java 21 · Spring Boot 3.x · React 18 · PostgreSQL · Redis · Apache Kafka · Kubernetes  
**Document Version:** 1.0  
**Methodology:** Domain-Driven Design · Microservices · Agile Phased Delivery

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Phase 1 — System Architecture & Design](#2-phase-1--system-architecture--design)
3. [Phase 2 — Backend Implementation Plan](#3-phase-2--backend-implementation-plan)
4. [Phase 3 — Frontend Implementation Plan](#4-phase-3--frontend-implementation-plan)
5. [Phase 4 — Testing Strategy](#5-phase-4--testing-strategy)
6. [Phase 5 — Deployment & DevOps](#6-phase-5--deployment--devops)
7. [Phase 6 — Phased Delivery Roadmap](#7-phase-6--phased-delivery-roadmap)
8. [Architecture Decision Records (ADRs)](#8-architecture-decision-records-adrs)
9. [Non-Functional Requirements](#9-non-functional-requirements)

---

## 1. Project Overview

### 1.1 Problem Statement

Urban commuters face two compounding problems: finding parking near transit hubs under time pressure, and bridging the gap between metro stations and their final destination. The Park & Ride system solves both by integrating real-time parking reservation, automated check-in, dynamic pricing, and last-mile ride booking into a single, seamless platform.

### 1.2 Core Functional Domains

| Domain | Responsibility |
|---|---|
| **Parking Reservation** | Slot search, advance booking, QR/RFID check-in, cancellation & modification |
| **Last-Mile Ride Integration** | Cab, shuttle, and e-rickshaw booking, pooling, real-time tracking |
| **Availability & Conflict Resolution** | Distributed slot locking, no-show auto-cancellation, dynamic reassignment |
| **Dynamic Pricing** | Demand-based surge pricing, subscription plans, loyalty rewards |
| **Offline Mode** | Offline QR access, pre-cached maps, auto-sync on reconnect |
| **Payments** | Wallet management, refunds, metro card integration, loyalty points |
| **Notifications** | SMS, email, push notifications for all booking lifecycle events |
| **Operator Dashboard** | Occupancy analytics, pricing rule management, revenue reporting |

### 1.3 System Quality Goals

| Attribute | Target |
|---|---|
| **Availability** | 99.9% uptime (≤ 8.7 hours downtime/year) |
| **Booking Latency** | p99 < 500ms under peak load |
| **Concurrent Users** | 10,000 simultaneous users without degradation |
| **Data Consistency** | Zero double-bookings (hard invariant) |
| **Offline Capability** | Full read access + check-in offline |
| **Recovery Time Objective (RTO)** | < 5 minutes for any single service failure |

---

## 2. Phase 1 — System Architecture & Design

### 2.1 High-Level Architecture

The system is built on a **microservices architecture** fronted by an API Gateway. React clients (web, mobile-responsive, PWA) communicate exclusively through the gateway, which handles authentication, rate limiting, and request routing. All domain services communicate asynchronously via **Apache Kafka** for event-driven operations, and synchronously via **REST/HTTP** only when an immediate response is required by the caller.

```
┌──────────────────────────────────────────────────┐
│                 React Frontend (PWA)              │
│        Web · Mobile Browser · Home Screen        │
└─────────────────────┬────────────────────────────┘
                      │ HTTPS / WebSocket (STOMP)
┌─────────────────────▼────────────────────────────┐
│           Spring Cloud Gateway (API Gateway)     │
│     Rate Limiting · JWT Validation · Routing     │
└──┬──────┬──────┬──────┬──────┬──────┬────────────┘
   │      │      │      │      │      │
   ▼      ▼      ▼      ▼      ▼      ▼
 Auth  Parking  Ride  Pricing  Pay  Notif
 Svc    Svc     Svc    Svc    Svc   Svc
   │      │      │      │      │      │
   └──────┴──────┴──────┴──────┴──────┘
                      │
              Apache Kafka Bus
                      │
   ┌──────────────────┼──────────────────┐
   ▼                  ▼                  ▼
PostgreSQL          Redis           Elasticsearch
(per service)   (availability,    (audit logs,
                  pricing cache,    search)
                  sessions)
```

### 2.2 Service Inventory

#### 2.2.1 API Gateway (Spring Cloud Gateway)

**Responsibilities:**
- Single entry point for all client traffic
- JWT validation and forwarding of resolved claims (`X-User-Id`, `X-User-Role`) to downstream services
- Route configuration per service with path-based routing
- Global rate limiting using Redis token bucket (e.g., 100 requests/minute per user)
- Request/response logging with correlation ID injection (`X-Trace-Id`)
- CORS configuration

**Key Spring Cloud Gateway Filters:**
- `AuthenticationFilter` — validates JWT signature and expiry
- `RateLimiterFilter` — enforces per-user and per-IP throttling
- `LoggingFilter` — emits structured access logs with trace IDs
- `CircuitBreakerFilter` — returns fallback responses when downstream services are unavailable

---

#### 2.2.2 Auth Service

**Responsibilities:**
- User registration (commuter, parking operator, admin roles)
- Login and JWT issuance (access token: 15-minute TTL, refresh token: 7-day TTL stored in Redis)
- Social login (Google OAuth2)
- Password reset via email OTP
- Token revocation (invalidation list stored in Redis)

**Data Model:**
```
users               user_roles           refresh_tokens
---------           ----------           --------------
id (UUID)           user_id (FK)         id (UUID)
email               role_name            user_id (FK)
password_hash       created_at           token_hash
phone                                    expires_at
is_verified                              is_revoked
created_at
```

**Security Design:**
- Passwords hashed with BCrypt (cost factor 12)
- JWT signed with RS256 (asymmetric) — public key distributed to all services for local validation
- Refresh token rotation on every use (old token immediately invalidated)
- Account lockout after 5 failed login attempts (15-minute lockout window)

---

#### 2.2.3 Parking Service

**Responsibilities:**
- Parking lot and slot CRUD (operator-facing)
- Real-time slot availability (Redis as source of truth, PostgreSQL as persistent record)
- Booking lifecycle management: `PENDING → CONFIRMED → CHECKED_IN → COMPLETED` (or `CANCELLED`)
- QR code generation and validation
- RFID/LPR check-in webhook ingestion
- Distributed slot locking to prevent double-bookings
- No-show auto-cancellation (scheduled job: grace period 15 minutes after reservation time)

**Data Model:**
```
parking_lots                  parking_slots
--------------                --------------
id (UUID)                     id (UUID)
name                          lot_id (FK)
address                       slot_number
geolocation (PostGIS POINT)   floor
total_slots                   vehicle_type (CAR/BIKE/EV)
operator_id (FK)              is_accessible
operating_hours               status (AVAILABLE/OCCUPIED/RESERVED/MAINTENANCE)
amenities (JSONB)             created_at

bookings                      booking_modifications
---------                     --------------------
id (UUID)                     id (UUID)
user_id (FK)                  booking_id (FK)
slot_id (FK)                  old_slot_id
booking_type (HOURLY/DAILY)   new_slot_id
start_time                    modified_at
end_time                      reason
amount_paid
status
qr_code_hash
version (optimistic lock)
```

**Concurrency Control (Layered Strategy):**

| Layer | Mechanism | Purpose |
|---|---|---|
| Layer 1 | Redis `SETNX` via Redisson `RLock` | Distributed mutual exclusion — prevents concurrent bookings at application level |
| Layer 2 | PostgreSQL Partial Unique Index | `UNIQUE (slot_id, status) WHERE status IN ('CONFIRMED','CHECKED_IN')` — database-level safety net |
| Layer 3 | JPA `@Version` Optimistic Locking | Catches concurrent modifications on the `bookings` table, triggers retry |

**Kafka Events Published:**
- `parking.booking.confirmed` — consumed by Pricing, Notification, Payment services
- `parking.booking.cancelled` — consumed by Notification, Payment (refund trigger)
- `parking.slot.checked-in` — consumed by Notification
- `parking.slot.availability.changed` — consumed by the frontend via WebSocket relay

---

#### 2.2.4 Ride Service

**Responsibilities:**
- Ride booking (cab, shuttle, e-rickshaw)
- Driver/vehicle assignment (nearest available driver using PostGIS geospatial query)
- Ride pooling: match riders going in similar directions, calculate shared route
- Real-time ride tracking (driver location updates via WebSocket)
- Pre-scheduled ride booking linked to parking check-out time
- Integration with route optimization API (OSRM or Google Maps Directions API)

**Data Model:**
```
vehicles                      drivers
---------                     --------
id (UUID)                     id (UUID)
registration_number           user_id (FK)
type (CAB/SHUTTLE/ERICKSHAW)  vehicle_id (FK)
capacity                      current_location (PostGIS POINT)
is_active                     status (AVAILABLE/ON_TRIP/OFFLINE)

ride_bookings                 ride_pool_groups
--------------                ----------------
id (UUID)                     id (UUID)
user_id (FK)                  ride_ids (UUID[])
driver_id (FK)                route_geometry (PostGIS LINESTRING)
booking_type (INSTANT/SCHED)  max_detour_minutes
pickup_location (POINT)       status
dropoff_location (POINT)
fare
status
scheduled_at
```

**Driver Location Update Architecture:**
- Drivers send GPS location every 5 seconds via WebSocket
- Location stored in Redis (TTL: 30 seconds) as `driver:{id}:location` → `{lat, lng, timestamp}`
- Geospatial index maintained in Redis `GEOADD drivers_geo {lng} {lat} {driver_id}`
- `GEORADIUS` command finds all available drivers within N km of pickup point
- Location history persisted to PostgreSQL asynchronously via Kafka

---

#### 2.2.5 Pricing Service

**Responsibilities:**
- Compute parking fee for a given slot, duration, and time window
- Compute ride fare based on distance, vehicle type, and surge multiplier
- Manage subscription plans and loyalty points redemption
- Expose surge pricing rationale (transparency API)

**Pricing Algorithm:**

```
Base Rate (from pricing_rules table)
    × Time-of-day multiplier (peak/off-peak lookup)
    × Occupancy multiplier (Redis: current_occupancy / total_capacity)
    × Event multiplier (event_calendar table, radius-based)
    × Weather multiplier (external weather API signal)
    = Final Price (capped at max_surge_cap per pricing_rules)
```

**Caching Strategy:**
- Computed prices cached in Redis with key `price:{lot_id}:{hour_bucket}`, TTL: 30 seconds
- Pricing rules (infrequently changing) cached with Spring `@Cacheable`, TTL: 5 minutes
- Cache invalidated immediately when an operator updates pricing rules

**Kafka Events Consumed:**
- `parking.slot.availability.changed` — recalculates and republishes surge multiplier
- `external.event.scheduled` — applies event-based pricing boost

---

#### 2.2.6 Payment Service

**Responsibilities:**
- User wallet management (top-up, balance inquiry, deduct on booking)
- Payment gateway integration (Razorpay / Stripe)
- Refund processing triggered by cancellation events
- Loyalty points management (earn on booking, redeem on payment)
- Metro card balance inquiry and deduction (third-party API integration)
- Idempotency: all payment operations carry an `idempotency_key` to prevent duplicate charges

**Data Model:**
```
wallets                       transactions
-------                       ------------
id (UUID)                     id (UUID)
user_id (FK, UNIQUE)          wallet_id (FK)
balance (DECIMAL 12,2)        type (CREDIT/DEBIT)
loyalty_points                amount
version (opt. lock)           idempotency_key (UNIQUE)
                              reference_id (booking/ride ID)
                              status (PENDING/SUCCESS/FAILED)
                              gateway_txn_id
                              created_at
```

**Failure Handling:**
- All payment state transitions are idempotent
- Failed transactions are retried via Kafka with exponential backoff (max 3 retries)
- Dead-letter topic (`payment.failed.dlq`) for manual intervention on permanently failed transactions

---

#### 2.2.7 Notification Service

**Responsibilities:**
- Pure Kafka consumer — listens to events from all services
- Dispatches SMS (Twilio), email (SendGrid), and push notifications (Firebase Cloud Messaging)
- Respects user notification preferences (opt-in/out per channel per event type)
- Retry with exponential backoff on delivery failure

**Event-to-Notification Mapping:**

| Kafka Topic | Notification |
|---|---|
| `parking.booking.confirmed` | "Your slot B-12 is confirmed. QR code attached." |
| `parking.booking.cancelled` | "Booking cancelled. Refund of ₹X initiated." |
| `parking.slot.checked-in` | "Welcome! You've checked into Lot A, Slot 7." |
| `ride.booking.confirmed` | "Your cab arrives in 4 minutes. Track here." |
| `payment.refund.processed` | "₹X has been credited to your wallet." |

---

### 2.3 Kafka Topic Design

| Topic | Partitions | Retention | Consumers |
|---|---|---|---|
| `parking.booking.events` | 12 | 7 days | Pricing, Notification, Payment |
| `parking.availability.updates` | 6 | 1 hour | WebSocket Relay, Pricing |
| `ride.booking.events` | 12 | 7 days | Notification, Payment |
| `payment.events` | 6 | 30 days | Notification, Audit |
| `notification.dispatch` | 6 | 1 day | Notification workers |
| `*.dlq` (dead-letter queues) | 3 each | 30 days | Manual/ops tooling |

**Consumer Group Strategy:** Each logical consumer (e.g., `notification-service-consumers`) has its own consumer group, ensuring each service receives every event independently. Partition count aligns with the expected maximum parallelism for that service.

---

### 2.4 Redis Data Architecture

| Key Pattern | Value | TTL | Purpose |
|---|---|---|---|
| `slot:{slot_id}:status` | `AVAILABLE / RESERVED / OCCUPIED` | None (event-driven invalidation) | Real-time availability |
| `lock:slot:{slot_id}` | `{booking_request_id}` | 30 seconds | Distributed booking lock |
| `price:{lot_id}:{hour_bucket}` | `{computed_price_json}` | 30 seconds | Pricing cache |
| `session:{user_id}:refresh` | `{token_hash}` | 7 days | Refresh token store |
| `rate:{user_id}:requests` | `{count}` | 60 seconds | API rate limiting counter |
| `driver:{id}:location` | `{lat, lng, ts}` | 30 seconds | Live driver location |
| `drivers_geo` | Geospatial sorted set | None | `GEORADIUS` driver search |

---

### 2.5 Offline Mode Architecture

The offline strategy is designed so users can always access their existing bookings and check in, even in underground parking with zero connectivity.

**Client-Side (React PWA):**
- **Service Worker (Workbox):** Intercepts all API requests. Uses a `NetworkFirst` strategy for dynamic data (availability, pricing) and `CacheFirst` for static assets and pre-loaded bookings.
- **IndexedDB (via Dexie.js):** Stores bookings, QR code data, parking lot maps (as GeoJSON), and a pending-actions queue.
- **Offline Sync Queue:** All mutations made offline (e.g., cancellation request) are enqueued in IndexedDB. On reconnect, a sync worker processes them in FIFO order with conflict detection.

**Conflict Resolution on Sync:**
- If an offline action conflicts with a server state change (e.g., user tried to cancel a booking that was already auto-cancelled for no-show), the sync worker surfaces a conflict resolution UI — the user is informed and given context to decide.
- Non-conflicting actions sync transparently.

---

### 2.6 WebSocket Architecture

Real-time updates (slot availability, ride tracking, pricing changes) are delivered to clients via WebSocket using **STOMP protocol over SockJS** (SockJS provides fallback to HTTP long-polling when WebSocket is unavailable).

**Subscription Topics:**

| STOMP Destination | Data | Subscribers |
|---|---|---|
| `/topic/lot/{lotId}/availability` | Slot availability grid | Parking search screen |
| `/topic/ride/{rideId}/tracking` | Driver GPS coordinates | Active ride screen |
| `/user/{userId}/queue/notifications` | Personal notification | Authenticated users |
| `/topic/pricing/{lotId}` | Current surge multiplier | Booking confirmation screen |

**Scale Consideration:** Multiple backend instances handling WebSocket connections require a shared message broker. Spring's STOMP message broker relay is configured to use an external ActiveMQ broker, ensuring messages published by any service instance reach all connected clients regardless of which pod they are connected to.

---

## 3. Phase 2 — Backend Implementation Plan

### 3.1 Project Structure (Maven Multi-Module)

```
park-and-ride/
├── pom.xml                        ← Parent POM (Spring Boot BOM, shared deps)
├── common/                        ← Shared library module
│   ├── dto/                       ← Request/Response DTOs
│   ├── events/                    ← Kafka event schemas (Avro/JSON)
│   ├── exceptions/                ← Shared exception hierarchy
│   └── utils/                     ← Utility classes
├── api-gateway/                   ← Spring Cloud Gateway
├── auth-service/
├── parking-service/
├── ride-service/
├── pricing-service/
├── payment-service/
├── notification-service/
├── docker-compose.yml             ← Local development environment
└── k8s/                           ← Kubernetes manifests
    ├── helm/
    └── manifests/
```

**Shared `common` module dependencies exposed to all services:**
- `ApiResponse<T>` — uniform response envelope: `{status, data, error, traceId}`
- `ParkAndRideException` → `ResourceNotFoundException`, `ConflictException`, `PaymentException` hierarchy
- Kafka event POJOs with JSON schema
- `PageResponse<T>` — cursor-based pagination wrapper

---

### 3.2 API Design Standards

#### 3.2.1 RESTful Conventions

- All APIs versioned under `/api/v1/` in the URL path
- HTTP methods follow REST semantics strictly: `GET` (read), `POST` (create), `PUT` (full replace), `PATCH` (partial update), `DELETE` (remove)
- HTTP status codes are precise: `201` for resource creation, `202` for async operations, `409` for conflicts, `422` for validation errors
- Idempotency headers (`Idempotency-Key`) required for all `POST` endpoints that trigger financial operations

#### 3.2.2 OpenAPI-First Workflow

- OpenAPI 3.0 specs are written first (before implementation)
- `springdoc-openapi` generates Swagger UI automatically from annotations
- Contract changes require spec review before implementation begins
- API specs are committed to the repository alongside service code

#### 3.2.3 Key API Endpoints

**Parking Service:**
```
GET    /api/v1/parking/lots/search?lat=&lng=&radius=&date=&duration=
GET    /api/v1/parking/lots/{lotId}/availability
POST   /api/v1/parking/bookings
GET    /api/v1/parking/bookings/{bookingId}
PATCH  /api/v1/parking/bookings/{bookingId}   ← Modify booking
DELETE /api/v1/parking/bookings/{bookingId}   ← Cancel booking
GET    /api/v1/parking/bookings/{bookingId}/qr
POST   /api/v1/parking/checkin/lpr             ← LPR webhook
POST   /api/v1/parking/checkin/rfid            ← RFID webhook
```

**Ride Service:**
```
GET    /api/v1/rides/options?pickup=&dropoff=&time=
POST   /api/v1/rides/bookings
GET    /api/v1/rides/bookings/{rideId}
GET    /api/v1/rides/bookings/{rideId}/tracking
POST   /api/v1/rides/bookings/{rideId}/cancel
GET    /api/v1/rides/driver/active             ← Driver: current trip
PATCH  /api/v1/rides/driver/location           ← Driver: update GPS
```

**Pricing Service:**
```
GET    /api/v1/pricing/parking?lotId=&duration=&startTime=
GET    /api/v1/pricing/ride?pickup=&dropoff=&vehicleType=
GET    /api/v1/pricing/surge/{lotId}           ← Current surge multiplier
POST   /api/v1/pricing/rules                   ← Operator: create rule
PUT    /api/v1/pricing/rules/{ruleId}          ← Operator: update rule
```

#### 3.2.4 Pagination Strategy

All list endpoints use **cursor-based pagination** (not offset-based). Offset pagination degrades under high data volumes because `OFFSET N` in SQL requires the database to scan all N rows. Cursor pagination uses an indexed field (typically `created_at` + `id`) to jump directly to the next page.

```json
GET /api/v1/parking/bookings?cursor=eyJjcmVhdGVkX...&limit=20

Response:
{
  "data": [...],
  "nextCursor": "eyJjcmVhdGVkX...",
  "hasMore": true
}
```

---

### 3.3 Domain Modeling (DDD)

#### 3.3.1 Bounded Contexts

Each microservice maps to one DDD bounded context. The linguistic boundary is enforced — the word "booking" means something specific in the Parking context (slot reservation) and something different in the Ride context (driver assignment). These are separate aggregates with separate IDs, linked only by a `parkingBookingId` reference in the ride booking.

#### 3.3.2 Booking State Machine (Parking Service)

Managed via **Spring State Machine** for explicit, auditable transitions:

```
         ┌─────────────────────────┐
         │                         ▼
[PENDING] ──(payment confirmed)──► [CONFIRMED] ──(user arrives)──► [CHECKED_IN]
    │                                   │                                │
    │(payment failed/timeout)           │(user cancels)                  │(parking done)
    ▼                                   ▼                                ▼
[FAILED]                          [CANCELLED]                      [COMPLETED]
                                        ▲
                              (no-show after grace period)
                              [CONFIRMED] ──(auto-cancel job)──► [CANCELLED]
```

Each transition fires a state change event, which is published to Kafka. This makes state transitions the source of truth for all downstream effects (notifications, refunds, availability updates) rather than scattered conditional logic in service methods.

---

### 3.4 Service Layer Patterns

#### 3.4.1 Parking Slot Assignment Algorithm

When a user books parking, the system must assign the optimal slot:

```
INPUT:  lot_id, vehicle_type, booking_start, booking_end, user_preferences

STEP 1: Query Redis for all AVAILABLE slots of the requested vehicle_type in the lot
STEP 2: Filter out slots with active locks (SETNX check)
STEP 3: Score remaining slots:
          - Accessibility preference (+10 if user is accessibility-registered)
          - Floor preference (-1 per floor above ground for standard)
          - EV charging availability (+5 if user has EV)
STEP 4: Select highest-scoring slot
STEP 5: Acquire Redisson RLock on slot_id (timeout: 10 seconds)
STEP 6: Persist booking to PostgreSQL (within lock)
STEP 7: Update Redis slot status to RESERVED
STEP 8: Release lock
STEP 9: Publish parking.booking.confirmed event to Kafka
STEP 10: Return booking confirmation with QR code
```

#### 3.4.2 Resilience Patterns

All inter-service HTTP calls are wrapped with **Resilience4j** as follows:

| Pattern | Configuration | Purpose |
|---|---|---|
| **Circuit Breaker** | Opens after 5 failures in 10 calls; half-open after 30s | Prevents cascading failures |
| **Retry** | Max 3 retries, exponential backoff (100ms → 200ms → 400ms) | Handles transient network issues |
| **Rate Limiter** | 50 calls/second per service-to-service call | Protects downstream from overload |
| **Bulkhead** | Thread pool isolation per downstream service | Prevents one slow dependency from exhausting thread pool |
| **Timeout** | 2 seconds for synchronous calls | Fails fast rather than queuing |

#### 3.4.3 Caching Strategy

Three-tier caching applied selectively based on data volatility:

**Tier 1 — Redis (seconds-level freshness):**
- Slot availability, driver locations, surge pricing, rate limit counters

**Tier 2 — Spring `@Cacheable` (minutes-level freshness):**
- Pricing rules, metro station list, vehicle type catalog, lot metadata

**Tier 3 — HTTP `Cache-Control` headers (hours-level freshness):**
- Static reference data APIs (city list, vehicle type list)
- `Cache-Control: public, max-age=3600`

**Cache Eviction:**
- Redis availability: event-driven (invalidated on state change via Kafka consumer)
- Spring cache: TTL-based expiry + explicit `@CacheEvict` on write operations
- HTTP cache: varies by endpoint, short TTL for any data that can change operationally

---

### 3.5 Cross-Cutting Concerns

#### 3.5.1 Structured Logging

All services log in **JSON format** (Logstash Logback Encoder) with mandatory fields:
- `traceId` (injected by API Gateway, propagated via MDC)
- `spanId`
- `serviceId`
- `userId` (from JWT claims)
- `eventType`

Example log entry:
```json
{
  "timestamp": "2025-04-15T14:23:01.456Z",
  "level": "INFO",
  "serviceId": "parking-service",
  "traceId": "abc123def456",
  "userId": "usr_789xyz",
  "eventType": "BOOKING_CONFIRMED",
  "slotId": "slot_a12",
  "bookingId": "bkg_001",
  "message": "Parking booking confirmed successfully"
}
```

#### 3.5.2 Global Exception Handling

A `@RestControllerAdvice` class in each service handles all exceptions and maps them to the standard `ApiResponse<T>` error format. This ensures no raw stack traces ever reach the client, and all error responses follow the same contract.

```
ParkAndRideException (base)
├── ResourceNotFoundException      → HTTP 404
├── ConflictException              → HTTP 409  (double-booking attempt)
├── SlotUnavailableException       → HTTP 409  (specific to parking)
├── PaymentException               → HTTP 402
├── UnauthorizedException          → HTTP 401
└── ValidationException            → HTTP 422  (with field-level errors)
```

#### 3.5.3 Distributed Tracing

**OpenTelemetry** SDK is included in all services. `traceId` and `spanId` are injected automatically by the gateway and propagated via HTTP headers (`traceparent`). This allows a single user request to be traced across six services in Jaeger's UI, from API Gateway ingress to Kafka event consumption in the Notification Service.

---

## 4. Phase 3 — Frontend Implementation Plan

### 4.1 Application Architecture

#### 4.1.1 Technology Decisions

| Concern | Library | Rationale |
|---|---|---|
| UI Framework | React 18 | Component model, hooks ecosystem, concurrent rendering |
| Build Tool | Vite | Fast dev server (ESM-based), better than CRA for modern apps |
| Routing | React Router v6 | Nested routes, data loaders, error boundaries per route |
| Server State | TanStack Query v5 | Caching, background refetch, optimistic updates |
| UI State | Zustand | Lightweight, no boilerplate, replaces Redux for this scope |
| Styling | Tailwind CSS | Utility-first, consistent design tokens |
| Component Library | shadcn/ui | Accessible, unstyled base components, customizable |
| Maps | Leaflet.js + React-Leaflet | Open-source, no per-request billing |
| Charts | Recharts | React-native charting for operator dashboard |
| WebSocket | STOMP.js + SockJS | Protocol-level message routing, fallback support |
| Offline DB | Dexie.js (IndexedDB) | Async IndexedDB wrapper with querying capability |
| Service Worker | Workbox (via vite-plugin-pwa) | Caching strategies, background sync |
| Forms | React Hook Form + Zod | Performance forms, type-safe schema validation |
| HTTP Client | Axios | Interceptors for JWT refresh, error normalization |
| QR Code | qrcode.react | Client-side QR generation, stored offline |

#### 4.1.2 Folder Structure

```
src/
├── app/                        ← App setup, router, providers, global styles
├── features/                   ← Feature modules (the primary code location)
│   ├── auth/
│   │   ├── components/         ← LoginForm, RegisterForm, OTPInput
│   │   ├── hooks/              ← useAuth, useCurrentUser
│   │   ├── api.ts              ← Auth API calls
│   │   └── store.ts            ← Auth Zustand slice
│   ├── parking/
│   │   ├── components/         ← ParkingMap, SlotGrid, BookingWizard, QRCodeDisplay
│   │   ├── hooks/              ← useParkingAvailability, useBooking, useQRCode
│   │   ├── api.ts
│   │   └── offline.ts          ← IndexedDB operations for parking data
│   ├── rides/
│   ├── pricing/
│   ├── payments/
│   └── dashboard/              ← Operator dashboard
├── shared/                     ← Reusable UI components, utilities
│   ├── components/             ← Button, Modal, Toast, Spinner, Badge
│   ├── hooks/                  ← useWebSocket, useOnlineStatus, useGeolocation
│   ├── api/                    ← Axios instance, interceptors
│   └── types/                  ← Global TypeScript types
├── pwa/
│   ├── serviceWorker.ts
│   └── syncQueue.ts            ← Offline action queue manager
└── main.tsx
```

---

### 4.2 Key Feature Implementations

#### 4.2.1 Parking Search & Map View

The central UI is an interactive Leaflet map showing parking lots color-coded by availability:
- **Green**: >50% slots available
- **Yellow**: 20–50% slots available
- **Red**: <20% slots available
- **Grey**: Fully occupied or closed

Clicking a lot opens a side panel with slot-level availability grid and real-time pricing. **WebSocket subscription** to `/topic/lot/{lotId}/availability` updates the grid in real time without polling.

**TanStack Query configuration for availability:**
- `staleTime`: 30 seconds (matches Redis TTL — no point refetching more often)
- `refetchOnWindowFocus`: true (re-fetch when user returns to the tab)
- `refetchInterval`: false (WebSocket handles real-time updates instead)

#### 4.2.2 Booking Wizard

A **5-step wizard** with route-per-step for deep-linking and back-button support:

```
Step 1: /parking/search        → Location, date, duration input
Step 2: /parking/lots          → Map + lot list (availability, pricing)
Step 3: /parking/book/:slotId  → Slot details, pricing breakdown, confirm
Step 4: /parking/pay           → Wallet / gateway / metro card payment
Step 5: /parking/confirmation  → QR code, directions to slot, add-to-calendar
```

Each step validates before proceeding. State is persisted in Zustand so browser refresh on any step does not lose the user's inputs.

**Optimistic Updates:** When a user completes payment, TanStack Query optimistically marks the slot as reserved in the local cache, giving instant visual feedback while the server confirms.

#### 4.2.3 QR Code Offline Access

Immediately after booking confirmation, the QR code is:
1. Rendered client-side by `qrcode.react` (contains a signed, encrypted booking token)
2. Serialized as a data URL and stored in IndexedDB under `bookings:{bookingId}:qr`
3. The full booking object also stored in IndexedDB

On the "My Bookings" screen, a `useEffect` checks IndexedDB for any stored QR codes and displays them even without network connectivity, with an "OFFLINE MODE" badge.

#### 4.2.4 Real-Time Ride Tracking

The active ride screen uses a Leaflet map with two markers: the user's location (via `navigator.geolocation`) and the driver's live position. The driver marker moves smoothly using CSS transitions on lat/lng changes. Driver position updates are received via WebSocket subscription to `/topic/ride/{rideId}/tracking`.

#### 4.2.5 Operator Dashboard

A Recharts-powered analytics dashboard showing:
- **Occupancy over time** (area chart, 24-hour rolling window)
- **Revenue by hour** (bar chart, today vs. last week overlay)
- **Surge pricing timeline** (line chart showing multiplier vs. occupancy correlation)
- **Booking source breakdown** (pie chart: app, walk-in, subscription)
- **No-show rate** (trend line, 7-day rolling average)

Data is fetched via REST (not WebSocket) with a 60-second polling interval — dashboard data does not require sub-second freshness.

---

### 4.3 Progressive Web App (PWA) Configuration

#### 4.3.1 Service Worker Caching Strategies

| Resource Type | Strategy | Details |
|---|---|---|
| Static assets (JS, CSS, fonts) | `CacheFirst` | Cached permanently, versioned by Vite build hash |
| API: Lot metadata, metro stations | `StaleWhileRevalidate` | Serves cached, updates in background |
| API: Availability, pricing | `NetworkFirst` | Tries network, falls back to cache (max 2 seconds) |
| API: My bookings | `NetworkFirst` with IndexedDB fallback | On network fail, reads from IndexedDB |
| Map tiles (Leaflet) | `CacheFirst` with expiry | Tiles cached for 7 days, covering the user's frequent lots |

#### 4.3.2 Background Sync

When the user performs an action offline (e.g., attempts to cancel a booking), it is added to the `syncQueue` in IndexedDB with:
- `actionType`: `CANCEL_BOOKING`
- `payload`: `{bookingId, timestamp}`
- `status`: `PENDING`

On reconnect, the Service Worker's `sync` event triggers the `SyncQueueProcessor`, which:
1. Reads all `PENDING` actions from IndexedDB in order
2. Replays them against the API
3. Marks each as `COMPLETED` or `FAILED`
4. Displays a toast notification with the result for each action

---

### 4.4 Authentication Flow

**Token Management (Axios Interceptors):**
1. Every request attaches the access token from memory (not localStorage — XSS mitigation)
2. On `401` response, the interceptor automatically calls `/auth/refresh` with the refresh token (stored in an `httpOnly` cookie — not accessible to JavaScript)
3. If refresh succeeds, the original request is retried with the new access token
4. If refresh fails (token expired or revoked), the user is redirected to login

**Route Protection:**
- `ProtectedRoute` component wraps all authenticated routes
- `OperatorRoute` additionally checks for `ROLE_OPERATOR` claim
- `AdminRoute` checks for `ROLE_ADMIN` claim
- Unauthenticated access redirects to `/login` with the intended destination preserved as a `redirect` query param

---

## 5. Phase 4 — Testing Strategy

### 5.1 Testing Pyramid

```
                    ┌─────────────┐
                    │    E2E      │  ← 20-30 critical user journeys (Playwright)
                    │  (Playwright)│
                  ┌─┴─────────────┴─┐
                  │  Contract Tests  │  ← Service API contracts (Pact)
                  │     (Pact)       │
                ┌─┴──────────────────┴─┐
                │  Integration Tests   │  ← DB, Redis, Kafka, APIs (Testcontainers)
                │  (Testcontainers)    │
              ┌─┴──────────────────────┴─┐
              │       Unit Tests         │  ← Business logic, algorithms (JUnit 5)
              │    (JUnit 5 + Mockito)   │
            ┌─┴────────────────────────────┴─┐
            │     Performance Tests           │  ← Load, stress, spike (Gatling)
            │         (Gatling)               │
            └────────────────────────────────┘
```

---

### 5.2 Unit Tests (JUnit 5 + Mockito)

**Coverage Target:** ≥ 90% line coverage on service layer and domain classes

**Focus Areas:**
- Slot assignment algorithm (all scoring branches)
- Booking state machine transitions (every valid and invalid transition)
- Pricing calculation engine (base rate, multipliers, capping logic)
- Surge pricing logic (boundary conditions: exactly at threshold, below, above)
- Refund calculation based on cancellation timing
- QR code token generation and validation
- No-show auto-cancellation scheduler logic

**Test Organization:**
- Each service class has a corresponding test class: `SlotAssignmentServiceTest`, `PricingEngineTest`
- Use `@ExtendWith(MockitoExtension.class)` for unit tests (no Spring context, fast)
- Use `@ParameterizedTest` with `@CsvSource` for boundary condition testing of pricing calculations
- Use `@TestFactory` with `DynamicTest` for state machine transition matrices

**Example Test Scenarios (Pricing):**

| Scenario | Input | Expected |
|---|---|---|
| Peak hour weekday | 09:00 Mon, 80% occupancy | Base × 1.5 × 1.3 = 1.95× |
| Off-peak night | 23:00, 20% occupancy | Base × 0.7 |
| Surge cap enforcement | 95% occupancy, peak | Capped at 3× regardless of formula |
| Subscription discount | User has monthly plan | 30% discount applied post-surge |

---

### 5.3 Integration Tests (Spring Boot Test + Testcontainers)

**Philosophy:** Start real infrastructure (PostgreSQL, Redis, Kafka) inside Docker containers managed by Testcontainers. This eliminates the unreliability of mocked infrastructure and catches issues that only appear with a real database (constraint violations, transaction isolation, query plan regressions).

**Infrastructure Setup:**
```
@Testcontainers
@SpringBootTest
class ParkingBookingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
}
```

**Test Scenarios:**

*Parking Service:*
- End-to-end booking flow: create → confirm → check-in → complete (verify all DB state transitions)
- Double-booking prevention: two concurrent booking requests for the same slot → exactly one succeeds
- No-show cancellation: scheduler runs after grace period → slot returns to AVAILABLE in Redis
- Booking modification: change slot → old slot freed in Redis, new slot reserved atomically

*Kafka Integration:*
- Publish `parking.booking.confirmed` → verify Notification Service consumer receives and processes it
- Publish `parking.booking.cancelled` → verify Payment Service triggers refund

*Redis Integration:*
- Slot availability updates propagate correctly on check-in
- Distributed lock prevents concurrent booking
- Expired lock does not leave slot in inconsistent state

---

### 5.4 Contract Tests (Pact)

**Purpose:** Verify that service API contracts are honored as each service evolves independently, without requiring all services to be running simultaneously.

**Workflow:**
1. **Consumer side** (e.g., Pricing Service consuming Parking Service's API) defines the expected request/response contract using the Pact DSL and generates a `.pact` file
2. **Provider side** (Parking Service) runs Pact verification against its actual running service using the `.pact` file
3. Pact files are published to a **Pact Broker** (self-hosted or PactFlow) for version tracking
4. CI/CD can-i-deploy check queries the Pact Broker before any service is deployed: "can Pricing Service v2.1 be deployed given the current Parking Service contract?"

**Contracts to Define:**

| Consumer | Provider | Contract |
|---|---|---|
| Notification Service | Parking Service | `GET /bookings/{id}` response shape |
| Pricing Service | Parking Service | Kafka `parking.availability.changed` event schema |
| Payment Service | Pricing Service | `GET /pricing/parking` response shape |
| React Frontend | All Services | All API contracts via consumer-driven tests |

---

### 5.5 End-to-End Tests (Playwright)

**Target:** Critical user journeys only — not every permutation.

**Critical Journeys (Priority Order):**

1. **Happy path booking**: Search lots → select slot → pay with wallet → receive QR → check-in
2. **Booking cancellation with refund**: Confirm booking → cancel before grace period → verify wallet credited
3. **Last-mile ride booking**: After parking check-in → book cab → track driver → complete ride
4. **Offline QR access**: Book parking → go offline → navigate to bookings → verify QR renders
5. **Double-booking prevention**: Two browser tabs simultaneously booking same slot → one succeeds, one gets error
6. **Operator creates pricing rule**: Login as operator → create surge rule → verify commuter sees updated price
7. **Subscription plan redemption**: Apply monthly subscription → verify discounted price at checkout
8. **Pooled ride matching**: Two users request rides to similar destinations → system pools them

**Playwright Configuration:**
- Tests run against a dedicated **staging environment** (not production)
- Parallelized across three workers (Chromium, Firefox, WebKit)
- Retries: 2 retries on flaky tests before marking as failed
- Screenshots and video recorded on failure
- Network mocking used only for third-party services (payment gateway, maps API) to prevent billing during tests

---

### 5.6 Performance Tests (Gatling)

**Tool:** Gatling (Scala DSL) — chosen over JMeter for its code-based scenarios, better reporting, and CI integration.

**Scenarios:**

*Scenario 1 — Peak Hour Booking Rush:*
- 1,000 concurrent users all attempting to book parking at 08:55 AM
- Ramp: 0 → 1,000 users over 2 minutes, hold for 5 minutes
- Assertions: zero booking conflicts, p99 booking API < 500ms

*Scenario 2 — Availability Polling Load:*
- 5,000 users polling lot availability every 10 seconds
- Validates Redis caching effectiveness (DB should receive < 5% of total requests)

*Scenario 3 — Surge Pricing Calculation:*
- 500 concurrent pricing requests per second
- Assertion: p99 < 50ms (pricing is a hot path)

*Scenario 4 — WebSocket Stress:*
- 2,000 simultaneous WebSocket connections receiving availability updates
- Validates STOMP broker stability under sustained connection load

**Acceptance Criteria:**

| Metric | Threshold |
|---|---|
| p50 booking latency | < 200ms |
| p99 booking latency | < 500ms |
| p99 availability check | < 100ms |
| Error rate | < 0.1% |
| Double-bookings under load | Exactly 0 |
| Database connection pool saturation | < 80% |

---

### 5.7 Frontend Testing

| Test Type | Tool | Coverage |
|---|---|---|
| Unit (component) | Vitest + React Testing Library | All shared components, all hooks |
| Snapshot | Vitest | Key UI components (QR display, booking card) |
| Interaction | Testing Library `userEvent` | Booking wizard form flows |
| API mock | MSW (Mock Service Worker) | All API-dependent components |
| Accessibility | axe-core (via `jest-axe`) | All page-level components |
| E2E | Playwright | See section 5.5 above |

---

## 6. Phase 5 — Deployment & DevOps

### 6.1 Containerization (Docker)

Every service uses a **multi-stage Dockerfile** to minimize production image size and separate build-time tools from runtime.

**Multi-Stage Build Pattern (Spring Boot):**
```
Stage 1 (build):   FROM eclipse-temurin:21-jdk  → Maven build → extract JAR layers
Stage 2 (runtime): FROM eclipse-temurin:21-jre  → Copy layers only → non-root user
Result: ~180MB image vs ~500MB single-stage
```

**Docker Compose (Local Development):**
A single `docker-compose.yml` at the project root starts the complete local environment:
- All microservices (each on a distinct port)
- PostgreSQL with separate databases per service
- Redis (single instance for local dev)
- Kafka + Zookeeper
- Jaeger (all-in-one container)
- Prometheus + Grafana

Local dev services mount their source code as volumes and use Spring Boot DevTools for hot reload.

---

### 6.2 Kubernetes Architecture

#### 6.2.1 Cluster Layout

**Namespaces:**
- `park-ride-prod` — production workloads
- `park-ride-staging` — pre-production environment
- `observability` — Prometheus, Grafana, Jaeger, ELK stack
- `kafka` — Kafka and Zookeeper cluster
- `cert-manager` — TLS certificate automation

#### 6.2.2 Kubernetes Resource Types Per Service

| Resource | Purpose |
|---|---|
| `Deployment` | Stateless microservices (all 6 services) |
| `StatefulSet` | Kafka brokers, Zookeeper, PostgreSQL |
| `HorizontalPodAutoscaler` | Scale Parking + Ride services on CPU and custom metrics |
| `ConfigMap` | Non-secret configuration (log levels, feature flags) |
| `Secret` | Database credentials, API keys, JWT signing keys |
| `Service` | Internal ClusterIP for service discovery |
| `Ingress` | External HTTPS entry point via NGINX Ingress Controller |
| `PodDisruptionBudget` | Ensures at least 1 replica available during rolling updates |
| `NetworkPolicy` | Restricts inter-service communication (least-privilege networking) |

#### 6.2.3 Horizontal Pod Autoscaler Configuration

**Parking Service HPA:**
- Scale up trigger: CPU > 70% OR custom metric `kafka_consumer_lag{topic="parking.booking.events"}` > 1000
- Scale up: +2 pods (not +1, to handle burst aggressively)
- Scale down: gradual (1 pod per 5 minutes, preventing oscillation)
- Min replicas: 2 (always HA), Max replicas: 10

**Pricing Service HPA:**
- Scale up trigger: requests per second > 300 (custom metric via Prometheus adapter)
- Min replicas: 3 (pricing is on the critical booking path)

#### 6.2.4 Zero-Downtime Deployments

All services use **rolling update strategy** with:
- `maxSurge: 1` — creates at most 1 additional pod during update
- `maxUnavailable: 0` — never reduces below desired replicas
- **Readiness probe:** `/actuator/health/readiness` must return `200` before traffic is sent to new pod
- **Liveness probe:** `/actuator/health/liveness` — kubelet restarts pod if this fails consecutively

---

### 6.3 Helm Charts

Each service has a Helm chart in the `k8s/helm/` directory with environment-specific `values-staging.yaml` and `values-prod.yaml` files. A root umbrella chart (`k8s/helm/park-and-ride/`) includes all service charts as sub-charts, enabling `helm upgrade --install` to deploy the entire platform in one command.

**Parameterized values include:**
- Image tag (injected by CI with the commit SHA)
- Replica counts per environment
- Resource requests and limits per environment
- Ingress hostnames
- Secret references (from Kubernetes Secrets, not hardcoded)

---

### 6.4 CI/CD Pipeline (GitHub Actions)

#### 6.4.1 Pipeline Stages

**Trigger:** Push to any branch, pull request to `main`, merge to `main`

```
┌──────────────────────────────────────────────────────────────────┐
│                         GitHub Actions Pipeline                  │
├──────────────┬──────────────┬──────────────┬─────────────────────┤
│   Stage 1    │   Stage 2    │   Stage 3    │      Stage 4        │
│    BUILD     │     TEST     │   SECURITY   │      DEPLOY         │
│              │              │    SCAN      │                     │
│ - Java build │ - Unit tests │ - OWASP dep  │ - Push image to ECR │
│ - React build│ - Integ tests│   check      │ - Helm upgrade      │
│ - Docker     │ - Contract   │ - Trivy image│   (staging auto)    │
│   build      │   tests      │   scan       │ - Manual approval   │
│              │ - E2E tests  │ - SonarQube  │   gate for prod     │
│              │   (staging)  │   analysis   │ - Pact can-i-deploy │
│              │              │              │   check             │
└──────────────┴──────────────┴──────────────┴─────────────────────┘
```

**Branch Strategy:**
- `feature/*` → runs Build + Unit Tests only (fast feedback: < 5 minutes)
- `develop` → runs full pipeline through staging deployment
- `main` → full pipeline with manual approval gate before production

#### 6.4.2 Security Gates

- **OWASP Dependency-Check:** Fails pipeline on any CVSS ≥ 7.0 dependency vulnerability
- **Trivy:** Fails pipeline on `CRITICAL` severity CVEs in Docker images
- **SonarQube:** Fails on Quality Gate (coverage < 80%, blocker/critical issues present)
- **Secrets Scanning:** `gitleaks` scans every commit for accidentally committed credentials

#### 6.4.3 Environment Promotion

```
feature branch → PR → code review → merge to develop
    → automated deploy to staging → E2E tests on staging
    → QA sign-off → merge to main
    → automated deploy to staging (final validation)
    → manual approval → automated deploy to production
    → smoke tests on production → done
```

---

### 6.5 Observability Stack

#### 6.5.1 Metrics (Prometheus + Grafana)

All Spring Boot services expose `/actuator/prometheus` endpoint. Prometheus scrapes every 15 seconds.

**Key Dashboards in Grafana:**

*Service Health Dashboard:*
- JVM heap usage, GC pause times, active thread count (per service)
- API request rate, error rate, p50/p95/p99 latency (per endpoint)
- HTTP 4xx vs 5xx rate trends

*Business Metrics Dashboard:*
- Bookings created per minute
- Slot occupancy per lot (heatmap)
- Active WebSocket connections
- Kafka consumer lag per topic
- Current surge multiplier per lot

*Infrastructure Dashboard:*
- Kubernetes pod count per service
- CPU and memory utilization per namespace
- PostgreSQL connection pool usage, slow query count
- Redis memory usage, hit/miss ratio

**Alerting Rules (PagerDuty integration):**

| Alert | Condition | Severity |
|---|---|---|
| High error rate | 5xx rate > 1% over 5 min | Critical |
| High latency | p99 > 1000ms over 5 min | Warning |
| Double-booking detected | `booking_conflict_total` counter increments | Critical (PagerDuty immediate) |
| Kafka consumer lag | Lag > 5000 messages for > 2 min | Warning |
| Redis memory | > 85% used | Warning |
| Service down | 0 healthy pods for > 1 min | Critical |

#### 6.5.2 Logging (ELK Stack)

- **Logstash:** Ingests JSON-structured logs from all pods via Filebeat sidecar
- **Elasticsearch:** Indexes logs with service, trace ID, user ID, and event type fields
- **Kibana:** Provides log search, dashboards, and saved queries for common debug patterns

**Practical Kibana queries:**
- All events for a specific booking ID across all services
- All errors for a specific user in the last hour
- All double-booking attempts (for fraud analysis)

#### 6.5.3 Distributed Tracing (Jaeger)

Jaeger receives traces from all services via OpenTelemetry SDK. A complete trace for a parking booking shows:
- API Gateway (JWT validation: ~2ms)
- Parking Service (slot search: ~15ms, Redis lock: ~3ms, DB write: ~12ms)
- Kafka publish (async, non-blocking)
- Pricing Service (fare calculation: ~8ms)
- Payment Service (wallet deduction: ~20ms)
- **Total end-to-end: ~60ms** (well within 500ms p99 target)

---

## 7. Phase 6 — Phased Delivery Roadmap

### Milestone Overview

```
Week:   1    2    3    4    5    6    7    8    9    10   11   12   13   14   15   16
        ├────────────────────┤├────────────────────────────┤├─────────────────────────┤
        PHASE 1: CORE MVP    PHASE 2: RIDES & PRICING       PHASE 3: HARDENING & SCALE
```

---

### Phase 1 — Core Booking MVP (Weeks 1–6)

**Goal:** A fully functional, deployable parking booking system.

**Week 1–2: Infrastructure & Foundation**
- Set up GitHub repository with Maven multi-module structure and common library
- Configure Docker Compose for local development (PostgreSQL, Redis, Kafka)
- Bootstrap all 6 Spring Boot service skeletons with Spring Cloud Gateway routing
- Set up GitHub Actions CI pipeline (build + unit test stage)
- Design and review all OpenAPI 3.0 specifications before any feature coding begins
- Write and review Architecture Decision Records for key decisions (see Section 8)

**Week 3–4: Auth + Parking Core**
- Implement Auth Service (register, login, JWT, refresh token, Spring Security config)
- Implement Parking Service core: lot management, slot CRUD, Redis availability layer
- Implement booking state machine with Spring State Machine
- Implement distributed locking strategy (Redisson) + partial unique index
- React: Auth screens (Login, Register, OTP verification)
- React: Parking search + Leaflet map with lot markers

**Week 5–6: Booking Flow + CI/CD**
- Complete booking lifecycle: create, confirm, cancel, QR generation
- Implement Notification Service (Kafka consumers, email + SMS dispatch)
- React: Full booking wizard (5 steps), booking list screen, QR code display
- React: PWA setup, Service Worker, IndexedDB for QR offline storage
- Complete CI/CD pipeline (all stages) and deploy Phase 1 to staging
- Write integration tests for all Phase 1 flows

**Phase 1 Deliverable:** A user can register, search for parking near a metro station, book a slot, receive a QR code, view it offline, and cancel with a refund. A working CI/CD pipeline deploys to staging.

---

### Phase 2 — Ride Integration & Dynamic Pricing (Weeks 7–12)

**Goal:** Full end-to-end commute experience with real-time features.

**Week 7–8: Ride Service**
- Implement Ride Service: driver management, booking, geospatial driver search (PostGIS)
- Implement ride pooling algorithm: match requests by direction + max detour
- Implement WebSocket driver location tracking
- React: Ride booking screen (options, selection, confirmation)
- React: Live ride tracking map

**Week 9–10: Pricing & Payments**
- Implement Pricing Service: base rates, time-of-day multipliers, occupancy-based surge
- Implement external weather/event signals integration
- Implement Payment Service: wallet, Razorpay/Stripe integration, refunds, loyalty points
- React: Pricing breakdown component, surge indicator, wallet management screen

**Week 11–12: Real-Time & Advanced Features**
- WebSocket: slot availability updates to Leaflet map
- WebSocket: surge pricing live updates on booking screen
- Offline sync queue: handle booking actions made offline
- Auto-cancellation scheduler for no-shows
- LPR/RFID check-in webhook stubs (integration points for hardware)
- Operator dashboard: Recharts analytics (occupancy, revenue, surge correlation)
- Expand contract tests (Pact) for all service interactions
- Performance testing with Gatling (Scenario 1 and 2)

**Phase 2 Deliverable:** Full user journey: drive to lot → check in → take metro → book last-mile ride → pay → track driver. Operator can view analytics and manage pricing rules.

---

### Phase 3 — Hardening, Scale & Observability (Weeks 13–16)

**Goal:** Production-ready hardening, full observability, and validated scalability.

**Week 13: Kubernetes & Scaling**
- Write Helm charts for all services
- Configure HPA for Parking and Ride services
- Configure PodDisruptionBudgets, NetworkPolicies, resource limits
- Deploy full observability stack (Prometheus, Grafana, ELK, Jaeger) to `observability` namespace
- Configure all Grafana dashboards and alerting rules with PagerDuty

**Week 14: Security Hardening**
- Enable OWASP and Trivy security gates in CI/CD (were reporting-only in earlier phases)
- Penetration test on Auth service (brute force, token replay, JWT manipulation)
- SQL injection and XSS review on all API inputs
- Secrets rotation process documented
- HTTPS enforcement, HSTS headers, CSP headers on React app

**Week 15: Performance & Resilience**
- Run all four Gatling scenarios; fix any bottlenecks identified
- Chaos engineering: kill individual service pods, verify circuit breakers activate correctly
- Test Redis failure: verify system degrades gracefully (cache miss → DB fallback)
- Test Kafka broker failure: verify consumer lag recovery
- Validate zero-downtime rolling deployment with production-like traffic on staging

**Week 16: Final QA & Documentation**
- Complete Playwright E2E suite (all 8 critical journeys)
- Load test production environment with synthetic traffic pattern
- Final ADR documentation review
- API documentation review (Swagger UI completeness)
- Runbook documentation: how to respond to each alert rule
- Record demonstration video

**Phase 3 Deliverable:** A production-hardened, observable, auto-scaling system ready for real commuters.

---

## 8. Architecture Decision Records (ADRs)

ADRs are stored in `docs/adr/` in the repository. Each follows the standard format: **Context → Decision → Rationale → Consequences → Alternatives Rejected**.

| ADR | Title | Decision |
|---|---|---|
| ADR-001 | Service architecture | Microservices over monolith |
| ADR-002 | Inter-service communication | Kafka (async) + REST (sync) hybrid |
| ADR-003 | Availability data store | Redis (not PostgreSQL) as real-time source of truth |
| ADR-004 | JWT validation location | Gateway-level (not per-service) |
| ADR-005 | Slot locking strategy | Redisson distributed lock + DB partial unique index |
| ADR-006 | Pagination strategy | Cursor-based (not offset) |
| ADR-007 | Frontend state management | TanStack Query + Zustand (not Redux) |
| ADR-008 | Offline strategy | IndexedDB + Service Worker (not native app) |
| ADR-009 | Contract testing | Pact (not integration environment testing) |
| ADR-010 | Deployment packaging | Helm charts (not raw K8s manifests) |

### Key Trade-offs Summary

| Decision | Benefit | Cost |
|---|---|---|
| Microservices | Independent scaling, team autonomy | Operational complexity, distributed tracing required |
| Redis for availability | Sub-ms reads, no DB bottleneck | Brief eventual consistency between Redis and PostgreSQL |
| JWT at gateway | No auth service hot path | Revoked tokens valid until expiry (acceptable for 15-min TTL) |
| Database-per-service | No coupling at data layer | Cross-service queries require API calls or event sourcing |
| Kafka over synchronous calls | Decoupling, replay capability, backpressure | Message ordering complexity, eventual consistency |
| Cursor pagination | Consistent performance at scale | More complex client implementation |

---

## 9. Non-Functional Requirements

### 9.1 Security Requirements

- All communication over HTTPS/TLS 1.3 (no HTTP)
- JWT access tokens: 15-minute TTL, RS256 signed
- Refresh tokens: `httpOnly`, `Secure`, `SameSite=Strict` cookies
- Sensitive data at rest encrypted (PostgreSQL column encryption for payment data)
- PCI-DSS: payment card data never stored — handled entirely by Razorpay/Stripe
- GDPR: user data export and deletion APIs implemented in Auth Service
- Input validation: all API inputs validated with Bean Validation (Jakarta) and Zod (React)
- Rate limiting: 100 requests/minute per user, 10 booking attempts/minute per user

### 9.2 Performance Requirements

| Operation | Target |
|---|---|
| Lot availability search | p99 < 100ms |
| Booking creation | p99 < 500ms |
| QR code generation | p99 < 200ms |
| Pricing calculation | p99 < 50ms |
| Ride driver assignment | p99 < 300ms |
| Operator dashboard load | p99 < 2000ms |

### 9.3 Scalability Requirements

- Stateless services: all microservices are horizontally scalable (no local state)
- Session state in Redis (not JVM memory) — pod restarts do not log out users
- Database connection pooling via HikariCP (max pool size: 20 per service instance)
- Kafka partitioning allows parallel event consumption proportional to replica count

### 9.4 Data Retention & Backup

| Data | Retention | Backup |
|---|---|---|
| Booking records | 7 years (regulatory) | Daily PostgreSQL backups to S3 |
| Payment transactions | 7 years | Daily backups, encrypted |
| Kafka events | 7 days (operational) | 30 days for DLQ topics |
| Application logs | 90 days (ELK) | Cold storage after 30 days |
| Audit logs | 2 years | Immutable S3 storage |

---

*Document End — Park & Ride Implementation Plan v1.0*  
*Prepared for implementation across 16-week delivery roadmap*
