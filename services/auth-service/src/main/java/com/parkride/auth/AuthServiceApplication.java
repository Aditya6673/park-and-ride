package com.parkride.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auth Service — entry point.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>User registration, login, logout</li>
 *   <li>JWT access token issuance (15-minute TTL)</li>
 *   <li>Refresh token rotation (7-day TTL, stored in Redis)</li>
 *   <li>Email verification and password reset</li>
 *   <li>Role-based access control (USER, OPERATOR, ADMIN)</li>
 * </ul>
 *
 * <p>All other services trust the JWT produced here — they never talk to this
 * service at runtime. Auth is stateless after token issuance.
 */
@SpringBootApplication
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
