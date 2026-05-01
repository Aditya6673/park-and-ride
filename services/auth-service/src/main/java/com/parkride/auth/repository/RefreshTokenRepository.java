package com.parkride.auth.repository;

import com.parkride.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revoke all active refresh tokens for a user — used on logout-all-devices. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.userId = :userId AND t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /** Purge expired and revoked tokens to keep the table lean. Run as a scheduled job. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.revoked = true OR t.expiresAt < :cutoff")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);
}
