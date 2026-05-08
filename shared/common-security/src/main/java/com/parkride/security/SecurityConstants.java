package com.parkride.security;

/**
 * Central registry of every security-related constant used across services.
 *
 * <p>Motivation: duplicating magic strings like {@code "Authorization"} or
 * {@code "Bearer "} across auth-service, api-gateway, and parking-service
 * creates a silent misconfiguration risk — one service uses {@code "bearer "}
 * (lowercase), another uses {@code "Bearer "} (capitalised), and tokens are
 * rejected with no clear error. One source of truth eliminates that class
 * of bug entirely.
 *
 * <p>This class is intentionally non-instantiable. All fields are
 * {@code public static final} — consumed as compile-time constants.
 *
 * <p>Usage:
 * <pre>
 * {@code
 * String token = request.getHeader(SecurityConstants.AUTH_HEADER);
 * if (token != null && token.startsWith(SecurityConstants.TOKEN_PREFIX)) {
 *     String jwt = token.substring(SecurityConstants.TOKEN_PREFIX.length());
 * }
 * }
 * </pre>
 */
public final class SecurityConstants {

    // ── Non-instantiable ──────────────────────────────────────────────────
    private SecurityConstants() {
        throw new UnsupportedOperationException("SecurityConstants is a constants class");
    }

    // ── HTTP Header ───────────────────────────────────────────────────────

    /**
     * The HTTP request header that carries the JWT.
     * Standard Authorization header per RFC 7235.
     */
    public static final String AUTH_HEADER = "Authorization";

    /**
     * The token type prefix in the Authorization header.
     * Note the trailing space — always strip this before passing to JJWT:
     * {@code token.substring(TOKEN_PREFIX.length())}
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * Header injected by the API Gateway after token validation, carrying
     * the authenticated user's ID downstream to backend services.
     * Services read this header instead of re-validating the token,
     * which keeps auth logic out of the parking/ride/payment services.
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * Header carrying the authenticated user's roles downstream.
     * Comma-separated: {@code "ROLE_USER,ROLE_ADMIN"}
     */
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    /**
     * Correlation ID header for distributed tracing.
     * Injected by {@code CorrelationIdFilter} in the gateway and propagated
     * to every downstream service for log correlation across the ELK stack.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    // ── JWT Claims ────────────────────────────────────────────────────────

    /**
     * JWT claim key for the user's UUID (subject claim alternative).
     * Use {@code jti} (JWT ID) is the token's unique identifier;
     * this custom claim carries the application-level user identity.
     */
    public static final String CLAIM_USER_ID = "userId";

    /**
     * JWT claim key for the user's email address.
     * Used by the frontend to display the logged-in user's identity
     * without a separate profile API call on every page load.
     */
    public static final String CLAIM_EMAIL = "email";

    /**
     * JWT claim key for the user's roles list.
     * Stored as a comma-separated string: {@code "ROLE_USER,ROLE_ADMIN"}.
     * Parsed by {@code JwtUtil.extractRoles()}.
     */
    public static final String CLAIM_ROLES = "roles";

    /**
     * JWT claim key for the user's phone number in E.164 format.
     * Only present when the user has a verified phone number on their account.
     * Used by the Notification Service for SMS delivery without an auth-service lookup.
     */
    public static final String CLAIM_PHONE = "phone";

    /**
     * JWT claim key for the token type — distinguishes access tokens
     * from refresh tokens at the validation layer. A refresh token must
     * never be accepted on an access-token-protected endpoint.
     */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    /** Value of {@link #CLAIM_TOKEN_TYPE} for short-lived access tokens. */
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";

    /** Value of {@link #CLAIM_TOKEN_TYPE} for long-lived refresh tokens. */
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";

    // ── Token Expiry ──────────────────────────────────────────────────────

    /**
     * Access token validity in milliseconds.
     * 15 minutes — short enough to limit exposure if a token is intercepted,
     * long enough to cover typical user session interactions without a refresh.
     */
    public static final long ACCESS_TOKEN_EXPIRY_MS  = 15 * 60 * 1000L;       // 15 min

    /**
     * Refresh token validity in milliseconds.
     * 7 days — allows background silent refresh for returning users.
     * Stored in Redis; explicitly invalidated on logout.
     */
    public static final long REFRESH_TOKEN_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

    // ── RBAC Role names ───────────────────────────────────────────────────

    /**
     * Standard commuter role. Assigned to all users on registration.
     * Can book parking, view own reservations, manage own profile.
     */
    public static final String ROLE_USER     = "ROLE_USER";

    /**
     * Platform administrator role. Can manage lots, slots, and all bookings.
     * Manually assigned — never auto-assigned on registration.
     */
    public static final String ROLE_ADMIN    = "ROLE_ADMIN";

    /**
     * Parking lot operator role. Can view and manage slots for their
     * assigned lot only. Scoped to a specific lot via a separate claim.
     */
    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";

    // ── Public endpoints (bypasses JWT filter) ────────────────────────────

    /**
     * URL patterns that the {@code JwtAuthFilter} and API Gateway
     * {@code AuthenticationFilter} allow without a valid JWT.
     *
     * <p>Reference in {@code SecurityConfig.permitAll()} matchers:
     * <pre>
     * {@code
     * for (String path : SecurityConstants.PUBLIC_ENDPOINTS) {
     *     http.authorizeHttpRequests(auth -> auth.requestMatchers(path).permitAll());
     * }
     * }
     * </pre>
     */
    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/verify-email",
            "/api/v1/parking/availability",   // read-only, no auth required
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };
}