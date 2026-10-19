package com.agenticai.core.common.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChallengeNonceRepository extends JpaRepository<ChallengeNonce, String> {

    /**
     * Find a valid (non-expired, not yet verified) challenge nonce by ID.
     */
    @Query("SELECT n FROM ChallengeNonce n " +
           "WHERE n.id = :id AND n.tenantId = :tenantId " +
           "AND n.expiresAt > CURRENT_TIMESTAMP AND n.verifiedAt IS NULL")
    Optional<ChallengeNonce> findValidNonce(@Param("id") String id,
                                            @Param("tenantId") UUID tenantId);

    /**
     * Find recently verified nonces for rate limiting (prevent re-issuing challenges too quickly).
     */
    @Query("SELECT n FROM ChallengeNonce n " +
           "WHERE n.tenantId = :tenantId AND n.userId = :userId " +
           "AND n.verifiedAt IS NOT NULL AND n.verifiedAt > :since " +
           "ORDER BY n.verifiedAt DESC")
    List<ChallengeNonce> findRecentVerifiedForUser(@Param("tenantId") UUID tenantId,
                                                   @Param("userId") UUID userId,
                                                   @Param("since") Instant since);

    /**
     * Delete expired nonces (cleanup task).
     */
    @Query("DELETE FROM ChallengeNonce n " +
           "WHERE n.tenantId = :tenantId AND n.expiresAt <= CURRENT_TIMESTAMP")
    void deleteExpired(@Param("tenantId") UUID tenantId);

    /**
     * Count pending (unverified) challenges for a user.
     */
    @Query("SELECT COUNT(n) FROM ChallengeNonce n " +
           "WHERE n.tenantId = :tenantId AND n.userId = :userId " +
           "AND n.verifiedAt IS NULL AND n.expiresAt > CURRENT_TIMESTAMP")
    long countPendingForUser(@Param("tenantId") UUID tenantId,
                             @Param("userId") UUID userId);
}
