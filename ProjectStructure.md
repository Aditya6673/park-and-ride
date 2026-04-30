# Park and Ride - Monorepo Structure

## 📦 Root
- `park-and-ride/` — monorepo root

---

## 🧠 Services (Backend Microservices)

### API Gateway
- `api-gateway/` — Spring Cloud Gateway
  - `src/main/java/com/parkride/gateway/`
    - `GatewayApplication.java`
    - `config/`
      - `GatewayConfig.java` (route definitions)
      - `SecurityConfig.java`
      - `RateLimitConfig.java`
    - `filter/`
      - `AuthenticationFilter.java`
      - `RequestLoggingFilter.java`
      - `CorrelationIdFilter.java`
  - `src/main/resources/`
    - `application.yml`
    - `bootstrap.yml`
  - `src/test/`
    - `GatewayRoutingTest.java`
    - `RateLimitTest.java`
  - `Dockerfile`
  - `pom.xml`

---

### Auth Service
- `auth-service/` — JWT · OAuth2 · Spring Security
  - `controller/`
    - `AuthController.java` (login, refresh, logout)
    - `UserController.java`
  - `service/`
    - `AuthService.java`
    - `TokenService.java`
    - `OAuthService.java`
  - `domain/`
    - `User.java`
    - `Role.java`
    - `RefreshToken.java`
  - `repository/`
    - `UserRepository.java`
    - `TokenRepository.java`
  - `security/`
    - `JwtAuthFilter.java`
    - `SecurityConfig.java`
  - `exception/`
    - `GlobalExceptionHandler.java`
    - `AuthException.java`
  - `db/migration/`
    - `V1__create_users.sql`
    - `V2__create_roles.sql`
  - `tests/`
    - Unit, Integration, Contract tests
  - `Dockerfile`
  - `pom.xml`

---

### Parking Service
- `parking-service/` — slot booking & conflict resolution
  - `controller/`
    - `ParkingController.java`
    - `ParkingAvailabilityController.java`
    - `WebSocketController.java`
  - `service/`
    - Booking, Availability, Pricing, QR, Auto-cancel
  - `domain/`
    - `ParkingSlot.java`
    - `Booking.java`
    - `PricingRule.java`
    - `BookingStatus.java`
  - `repository/`
    - Booking & Slot repos
  - `event/`
    - Kafka events
  - `dto/`
  - `config/`
    - Kafka, Redis, WebSocket configs
  - `db/migration/`
  - `tests/` (unit, integration, performance)
  - `Dockerfile`
  - `pom.xml`

---

### Ride Service
- `ride-service/` — ride pooling & tracking
  - `controller/`
  - `service/`
  - `domain/`
  - `event/`
  - `tests/`
  - `Dockerfile`
  - `pom.xml`

---

### Notification Service
- `notification-service/` — push/email/SMS
  - Kafka consumers
  - Email/SMS/Push services
  - Templates
  - `Dockerfile`
  - `pom.xml`

---

### Payment Service
- `payment-service/` — billing & subscriptions
  - Payment, Refund, Loyalty services
  - Domain models
  - `Dockerfile`
  - `pom.xml`

---

### Analytics Service
- `analytics-service/` — reports & pricing intelligence
  - Kafka consumers
  - Analytics services
  - `Dockerfile`
  - `pom.xml`

---

## 🌐 Frontend (React PWA)
- `frontend/`
  - `src/`
    - `api/`
    - `components/`
    - `pages/`
    - `store/`
    - `hooks/`
    - `service-worker/`
    - `i18n/`
  - `public/`
  - `e2e/` (Playwright tests)
  - Config files (`vite`, `tailwind`, etc.)

---

## ⚙️ Infrastructure
- `docker/` — docker-compose
- `kubernetes/` — deployments, services, HPA
- `helm/` — charts & values
- `.github/workflows/` — CI/CD pipelines
- `monitoring/`
  - Prometheus
  - Grafana
  - ELK
  - Jaeger

---

## 🔗 Shared Libraries
- `common-dto/`
- `common-events/`
- `common-security/`

---

## 📚 Docs
- `adr/` — architecture decisions
- `api/` — OpenAPI specs
- `diagrams/`
- `CONTRIBUTING.md`
- `RUNBOOK.md`

---

## 📄 Root Files
- `pom.xml`
- `.gitignore`
- `README.md`