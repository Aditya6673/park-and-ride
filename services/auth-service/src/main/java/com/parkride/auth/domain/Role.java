package com.parkride.auth.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Role entity — maps one-to-one with a Spring Security {@code GrantedAuthority}.
 *
 * <p>Roles are seeded by Flyway migration {@code V2__create_roles.sql} and never
 * created at runtime. The application code only reads and assigns existing roles.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, unique = true, length = 50)
    private RoleName name;

    // ── Role name enum ────────────────────────────────────────────────────

    public enum RoleName {
        /**
         * Standard commuter — assigned to every new registration.
         * Can book parking, manage own reservations, view own profile.
         */
        ROLE_USER,

        /**
         * Parking lot operator — can manage slots and view analytics
         * for their assigned lot only.
         */
        ROLE_OPERATOR,

        /**
         * Platform administrator — full access to all resources.
         * Never auto-assigned; set manually in the database.
         */
        ROLE_ADMIN
    }
}
