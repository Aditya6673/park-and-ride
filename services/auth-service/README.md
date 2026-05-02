# Auth Service — Park & Ride Platform

> **Microservice:** `auth-service`  
> **Port:** `8081`  
> **Responsibility:** Identity, authentication, and session management for the entire platform.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Technology Stack](#3-technology-stack)
4. [Project Structure](#4-project-structure)
5. [Domain Model](#5-domain-model)
6. [Database Schema](#6-database-schema)
7. [Security Design](#7-security-design)
8. [API Endpoints](#8-api-endpoints)
9. [Request & Response DTOs](#9-request--response-dtos)
10. [Token Lifecycle](#10-token-lifecycle)
11. [Configuration](#11-configuration)
12. [Running Locally](#12-running-locally)
13. [Testing](#13-testing)
14. [Known Limitations & TODOs](#14-known-limitations--todos)

---

## 1. Overview

The Auth Service is the single source of truth for user identity in the Park & Ride platform. Every other microservice trusts the JWT it issues — they never talk directly to the auth-service to validate tokens; instead they verify the JWT signature using the shared secret from `common-security`.

**What it does:**
- User registration with email verification
- Password-based login with account lockout protection
- Stateless JWT access tokens (15-minute expiry)
- Secure refresh token rotation (7-day expiry, cookie-based)
- Logout with immediate token revocation via Redis blacklist
- Forgot/reset password flow
- User profile read and update

**What it does NOT do:**
- OAuth2 / social login (planned Phase 3)
- Send emails directly — email events will be published to Kafka → Notification Service (TODO)
- Session storage — fully stateless via JWT

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────┐
│                      Auth Service                          │
│                                                            │
│  REST Controllers  →  Service Layer  →  Repositories       │
│  (AuthController       (AuthService      (UserRepository   │
│   UserController)       TokenService)    RefreshToken      │
│                                          RoleRepository)   │
│         ↕                    ↕                             │
│   Spring Security       PostgreSQL (JPA + Flyway)          │
│   (JwtAuthFilter)       Redis (JWT blacklist)              │
│                                                            │
└────────────────────────────────────────────────────────────┘
          ↑                         ↓
   HTTP Clients              Kafka (future)
   (Postman / Frontend)   UserRegisteredEvent
                          PasswordResetEvent
```

### Shared Modules Used

| Module | Purpose |
|---|---|
| `common-dto` | `ApiResponse<T>` — standard response envelope |
| `common-security` | `JwtUtil` (token mint/validate), `SecurityConstants` (public endpoints, claim keys, expiry values) |

---

## 3. Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Framework | Spring Boot 3.4.5 | Spring Framework 6.2 |
| Language | Java 21 | LTS release |
| Security | Spring Security 6 | Stateless, JWT-based |
| JWT | JJWT (io.jsonwebtoken) | RS256-compatible, via `common-security` |
| ORM | Spring Data JPA + Hibernate 6 | `ddl-auto=validate` — Flyway owns schema |
| Schema Migration | Flyway | V1 → V3 applied on startup |
| Database | PostgreSQL 16 | Dedicated instance (`auth_db`) |
| Cache / Blacklist | Redis 7.2 | JWT JTI blacklist; Lettuce client |
| Message Bus | Apache Kafka | Producer only (events not yet published) |
| Boilerplate reduction | Lombok | `@Builder`, `@Getter`, `@Setter`, `@Slf4j` |
| API Docs | SpringDoc OpenAPI 2.8.0 | Swagger UI at `/swagger-ui.html` |
| Observability | Micrometer + Actuator + OTLP | Prometheus, Jaeger-ready |
| Build | Maven 3 (multi-module) | Parent POM at repo root |
| Container | Docker (multi-stage) | Non-root user, layered JARs |

---

## 4. Project Structure

```
services/auth-service/
├── src/main/java/com/parkride/auth/
│   ├── AuthServiceApplication.java          # Spring Boot entry point
│   ├── config/
│   │   ├── RedisConfig.java                 # RedisTemplate<String,String> bean
│   │   └── OpenApiConfig.java               # Swagger info + bearerAuth scheme
│   ├── controller/
│   │   ├── AuthController.java              # /api/v1/auth/** (public)
│   │   └── UserController.java              # /api/v1/users/me (authenticated)
│   ├── domain/
│   │   ├── User.java                        # JPA entity — core user aggregate
│   │   ├── Role.java                        # JPA entity — ROLE_USER / ROLE_ADMIN / ROLE_OPERATOR
│   │   └── RefreshToken.java                # JPA entity — persisted refresh token hash
│   ├── dto/
│   │   ├── RegisterRequest.java             # POST /register body
│   │   ├── LoginRequest.java                # POST /login body
│   │   ├── UpdateProfileRequest.java        # PATCH /users/me body
│   │   ├── PasswordResetRequest.java        # POST /reset-password body
│   │   ├── AuthResponse.java                # Login/register response payload
│   │   └── UserResponse.java                # Profile response payload
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java      # @RestControllerAdvice → ApiResponse errors
│   │   ├── AuthException.java
│   │   ├── UserAlreadyExistsException.java
│   │   └── InvalidTokenException.java
│   ├── repository/
│   │   ├── UserRepository.java              # JPA + custom JPQL queries
│   │   ├── RoleRepository.java
│   │   └── RefreshTokenRepository.java      # revoke / cleanup queries
│   ├── security/
│   │   ├── SecurityConfig.java              # Filter chain, CORS, BCrypt, STATELESS
│   │   ├── JwtAuthFilter.java               # OncePerRequestFilter — validates JWT
│   │   └── UserDetailsServiceImpl.java      # Loads user for DaoAuthenticationProvider
│   └── service/
│       ├── AuthService.java                 # Core business logic
│       └── TokenService.java                # JWT generation + refresh token lifecycle
├── src/main/resources/
│   ├── application.properties               # Main config
│   ├── application-test.properties          # Test profile overrides
│   └── db/migration/
│       ├── V1__create_users.sql
│       ├── V2__create_roles.sql
│       └── V3__create_refresh_tokens.sql
├── src/test/java/com/parkride/auth/
│   ├── service/
│   │   ├── AuthServiceTest.java             # Unit tests (Mockito)
│   │   └── TokenServiceTest.java            # Unit tests (Mockito)
│   └── controller/
│       └── AuthControllerIT.java            # Integration tests (Testcontainers + EmbeddedKafka)
├── Dockerfile                               # Multi-stage production image
└── pom.xml
```

---

## 5. Domain Model

### `User` entity

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Auto-generated primary key |
| `email` | `VARCHAR(255)` | Unique, lowercased on save |
| `passwordHash` | `VARCHAR(255)` | BCrypt cost-12 hash — never plain text |
| `firstName` | `VARCHAR(100)` | Optional |
| `lastName` | `VARCHAR(100)` | Optional |
| `phone` | `VARCHAR(20)` | Optional |
| `verified` | `BOOLEAN` | False until email verified |
| `enabled` | `BOOLEAN` | Soft-disable without deleting |
| `failedLoginAttempts` | `INT` | Incremented on bad password |
| `lockedUntil` | `TIMESTAMPTZ` | Non-null when account is locked |
| `verificationTokenHash` | `VARCHAR(64)` | SHA-256 of emailed token |
| `verificationTokenExpiresAt` | `TIMESTAMPTZ` | 24-hour validity window |
| `passwordResetTokenHash` | `VARCHAR(64)` | SHA-256 of reset token |
| `passwordResetTokenExpiresAt` | `TIMESTAMPTZ` | 1-hour validity window |
| `roles` | `Set<Role>` | Many-to-many via `user_roles` table |
| `createdAt` / `updatedAt` | `TIMESTAMPTZ` | Hibernate auto-managed |

**Account lockout rule:** After **5 consecutive failed login attempts**, `lockedUntil` is set to `now + 15 minutes`. Resets automatically on next successful login.

### `Role` entity

Three roles are seeded by Flyway migration V2:

| Role | Assigned how | Access |
|---|---|---|
| `ROLE_USER` | Automatically on registration | Can book parking, manage own profile |
| `ROLE_ADMIN` | Manual DB update | Full platform access |
| `ROLE_OPERATOR` | Manual DB update | Manage slots for their assigned lot |

### `RefreshToken` entity

Refresh tokens are **never stored raw** in the database. Only the SHA-256 hash is persisted.

| Field | Description |
|---|---|
| `id` | UUID primary key |
| `userId` | FK to `users.id` |
| `tokenHash` | SHA-256(rawToken) — 64-char hex string |
| `expiresAt` | `now + 7 days` at creation |
| `revoked` | Set to `true` on use (rotation) or logout |
| `deviceInfo` | Optional `User-Agent` string |

---

## 6. Database Schema

Three Flyway migrations run automatically on startup:

```
V1__create_users.sql          — users table, partial indexes on token hashes
V2__create_roles.sql          — roles table + user_roles join table, seeds 3 roles
V3__create_refresh_tokens.sql — refresh_tokens table, index on token_hash
```

Hibernate is set to `ddl-auto=validate` — it validates entity mappings against the live schema but **never modifies it**. All schema changes go through Flyway.

---

## 7. Security Design

### JWT Access Tokens

- **Algorithm:** HS256 (HMAC-SHA256)
- **Expiry:** 15 minutes (`ACCESS_TOKEN_EXPIRY_MS = 900_000`)
- **Claims stored:** `userId` (UUID), `email`, `roles` (list), `tokenType=ACCESS`, `jti` (UUID — unique per token)
- **Stateless** — no server-side storage; validated by signature check

### Refresh Tokens

- **Expiry:** 7 days (`REFRESH_TOKEN_EXPIRY_MS = 604_800_000`)
- **Storage:** SHA-256 hash persisted in PostgreSQL `refresh_tokens` table
- **Transport:** `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth/refresh`
- **Rotation:** Every `/refresh` call **revokes the old token** and issues a brand-new one
- **Reuse detection:** If a revoked token is presented again, **all sessions for that user are immediately revoked** (token theft protection)

### JWT Blacklist (Logout)

On logout, the access token's `jti` is written to Redis with a TTL equal to the token's remaining validity. The `JwtAuthFilter` checks Redis before trusting any token:

```
Redis key:   blacklist:<jti>
Redis value: "1"
Redis TTL:   remaining ms until token natural expiry
```

### Filter Chain (`JwtAuthFilter`)

On every request:
1. Extract `Bearer <token>` from `Authorization` header
2. Validate JWT signature and expiry via `JwtUtil`
3. **Reject** refresh tokens on non-`/auth/refresh` endpoints
4. Check `blacklist:<jti>` in Redis — reject if present
5. Build `UsernamePasswordAuthenticationToken` with user ID as principal and roles as authorities
6. Set in `SecurityContextHolder`

### Public Endpoints (no JWT required)

```
POST   /api/v1/auth/login
POST   /api/v1/auth/register
POST   /api/v1/auth/refresh
GET    /api/v1/auth/verify-email
GET    /api/v1/parking/availability
GET    /actuator/health
GET    /actuator/info
GET    /v3/api-docs/**
GET    /swagger-ui/**
GET    /swagger-ui.html
```

### Password Hashing

BCrypt with **cost factor 12** (~250ms per hash on modern hardware). Chosen to make brute-force attacks expensive while staying fast enough for interactive login.

### CORS

Allowed origins: `http://localhost:*` (local dev) and `https://*.parkride.com` (production).  
`allowCredentials = true` is required so browsers include the HttpOnly refresh cookie.

---

## 8. API Endpoints

Base URL: `http://localhost:8081`  
Interactive docs: `http://localhost:8081/swagger-ui.html`

### Authentication (`/api/v1/auth`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/register` | Public | Create account, returns access token |
| `POST` | `/login` | Public | Authenticate, returns access token |
| `POST` | `/refresh` | Cookie | Rotate refresh token, returns new access token |
| `POST` | `/logout` | Bearer | Blacklist access token, revoke refresh token |
| `GET` | `/verify-email?token=` | Public | Verify email address |
| `POST` | `/forgot-password?email=` | Public | Trigger password reset (always returns 200) |
| `POST` | `/reset-password` | Public | Complete password reset with token |

### User Profile (`/api/v1/users`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/me` | Bearer | Get current user's profile |
| `PATCH` | `/me` | Bearer | Update first name, last name, or phone |

---

## 9. Request & Response DTOs

### `RegisterRequest`

```json
{
  "firstName": "Alice",
  "lastName":  "Smith",
  "email":     "alice@example.com",
  "password":  "MyStr0ng@Pass!"
}
```

Validation: `email` must be valid format; `password` min 8 chars (Bean Validation).

### `LoginRequest`

```json
{
  "email":      "alice@example.com",
  "password":   "MyStr0ng@Pass!",
  "deviceInfo": "Mozilla/5.0 ..."
}
```

`deviceInfo` is optional — stored with the refresh token for future session management UI.

### `AuthResponse` (returned by register / login / refresh)

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType":   "Bearer",
    "expiresIn":   900,
    "userId":      "550e8400-e29b-41d4-a716-446655440000",
    "email":       "alice@example.com",
    "firstName":   "Alice",
    "lastName":    "Smith",
    "roles":       ["ROLE_USER"],
    "verified":    false
  }
}
```

> **Note:** The refresh token is **not** in the response body. It is set as a `Set-Cookie` header by the controller.

### `UserResponse` (returned by profile endpoints)

```json
{
  "success": true,
  "data": {
    "id":        "550e8400-e29b-41d4-a716-446655440000",
    "email":     "alice@example.com",
    "firstName": "Alice",
    "lastName":  "Smith",
    "phone":     "+91-9876543210",
    "verified":  true,
    "roles":     ["ROLE_USER"],
    "createdAt": "2026-05-02T09:12:00Z"
  }
}
```

### Error Response (all errors)

```json
{
  "success": false,
  "message": "Invalid email or password"
}
```

HTTP status codes: `400` validation, `401` unauthenticated, `403` forbidden, `409` email conflict, `500` unexpected.

---

## 10. Token Lifecycle

```
REGISTER / LOGIN
     │
     ├─► AccessToken  (JWT, 15 min, returned in body)
     └─► RefreshToken (JWT, 7 days, set as HttpOnly cookie)
              │
              │ stored as SHA-256 hash in refresh_tokens table

AUTHENTICATED REQUEST
     │
     └─► JwtAuthFilter validates AccessToken:
           1. Signature check (JwtUtil)
           2. tokenType == ACCESS (not REFRESH)
           3. Redis blacklist check (JTI not blacklisted)
           4. Sets SecurityContext

TOKEN REFRESH  (POST /auth/refresh)
     │
     ├─► Old RefreshToken cookie read by controller
     ├─► Hash compared against DB record
     ├─► Old record marked revoked=true
     ├─► New AccessToken + new RefreshToken issued
     └─► New RefreshToken set as cookie

        If revoked token is replayed → ALL user sessions revoked (theft detection)

LOGOUT  (POST /auth/logout)
     │
     ├─► AccessToken JTI written to Redis with remaining TTL
     └─► RefreshToken revoked in DB + cookie cleared
```

---

## 11. Configuration

### `application.properties` (key settings)

```properties
server.port=8081
spring.application.name=auth-service

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/auth_db
spring.datasource.username=auth_user
spring.datasource.password=auth_secret

# JPA — Flyway owns the schema
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Redis — JWT blacklist
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=redis_secret

# JWT secret — MUST be overridden in production via environment variable
security.jwt.secret=dev-secret-change-this-in-production-min32chars

# Actuator
management.endpoints.web.exposure.include=health,info,prometheus,metrics
```

### Environment Variables for Production

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `SPRING_DATA_REDIS_PASSWORD` | Redis password |
| `SECURITY_JWT_SECRET` | JWT signing secret (32+ chars) |

---

## 12. Running Locally

### Prerequisites

- Java 21
- Maven 3.9+
- Docker Desktop

### Step 1 — Start infrastructure

```powershell
# From repo root
docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres-auth redis
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
mvn spring-boot:run -pl services/auth-service
```

Service starts at `http://localhost:8081`. Flyway automatically runs all 3 migrations on first start.

### Step 4 — Test with Swagger UI

Open: `http://localhost:8081/swagger-ui.html`

Or use Postman — sample requests:

```
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "firstName": "Alice",
  "lastName":  "Smith",
  "email":     "alice@example.com",
  "password":  "MyStr0ng@Pass!"
}
```

```
POST http://localhost:8081/api/v1/auth/login
Content-Type: application/json

{
  "email":    "alice@example.com",
  "password": "MyStr0ng@Pass!"
}
```

```
GET http://localhost:8081/api/v1/users/me
Authorization: Bearer <accessToken from login response>
```

---

## 13. Testing

### Unit Tests

Located in `src/test/java/com/parkride/auth/service/`.

```powershell
mvn test -pl services/auth-service -Dtest="AuthServiceTest,TokenServiceTest"
```

| Test class | Tests | What it covers |
|---|---|---|
| `AuthServiceTest` | 5 | register, login success, login bad credentials, login locked account, logout |
| `TokenServiceTest` | 4 | access token generation, refresh rotation, unknown token error, revoked token theft detection |

All dependencies are mocked with Mockito — no database or Redis required.

### Integration Tests

Located in `src/test/java/com/parkride/auth/controller/AuthControllerIT.java`.

```powershell
mvn test -pl services/auth-service -Dtest="AuthControllerIT"
```

Uses:
- **Testcontainers** — spins up a real PostgreSQL 16 container
- **EmbeddedKafka** — in-process Kafka broker
- **MockMvc** — HTTP-level assertions against the full Spring context

Requires Docker to be running.

### Run all tests

```powershell
mvn test -pl services/auth-service
```

Expected: **9/9 tests pass**.

---

## 14. Known Limitations & TODOs

| # | Item | Phase |
|---|---|---|
| 1 | **Email not sent on registration** — `UserRegisteredEvent` is not yet published to Kafka. Verification token is generated but the email link is never delivered. | Phase 2 (Notification Service) |
| 2 | **Password reset email not sent** — Same as above; token is generated and stored but no event published. | Phase 2 |
| 3 | **Refresh token not set as cookie on login** — `AuthController.login()` returns the refresh token in the `AuthResponse` body instead of the `Set-Cookie` header. This is a security gap that needs fixing before production. | Immediate |
| 4 | **`UserDetailsService` warning** — Spring Security logs a warning because both a `DaoAuthenticationProvider` bean and a `UserDetailsServiceImpl` are registered. The warning is harmless but should be resolved by removing the explicit `UserDetailsService` bean injection in `SecurityConfig`. | Cleanup |
| 5 | **No rate limiting** — No per-IP or per-user rate limiting on `/login` or `/register`. Planned via Redis-based token bucket in Phase 3. | Phase 3 |
| 6 | **Refresh token missing device fingerprint on rotate** — The `refresh()` method passes `null` for `deviceInfo`, so the rotated token loses the original device association. | Minor fix |
