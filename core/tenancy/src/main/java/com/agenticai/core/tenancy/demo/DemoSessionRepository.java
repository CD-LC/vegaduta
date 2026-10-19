package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemoSessionRepository extends JpaRepository<DemoSession, UUID> {

    /** The one hot-path lookup: an anonymous request's {@code X-Demo-Token}, hashed. */
    Optional<DemoSession> findByTokenHash(String tokenHash);

    /**
     * The public share surface's lookup ({@code /api/demo/share/{shareId}}) -
     * served by V131's partial unique index. The share id is an unguessable
     * capability handle, stored in the clear on purpose (see {@code
     * DemoSession#shareId}).
     */
    Optional<DemoSession> findByShareId(String shareId);

    /**
     * {@code DemoSessionExpiryJob}'s first scan - sessions whose expiry has
     * passed but which have not yet been retired. Scoped to {@code expiredAt
     * IS NULL} so an already-retired session is never processed twice
     * (retirement suspends the tenant, which is idempotent, but re-logging it
     * hourly forever would be noise).
     */
    @Query("SELECT d FROM DemoSession d WHERE d.expiresAt < :now AND d.expiredAt IS NULL")
    List<DemoSession> findDueForExpiry(@Param("now") Instant now);

    /**
     * {@code DemoSessionExpiryJob}'s second scan - live sessions inside the
     * pre-expiry reminder window that have not been nudged yet. {@code
     * reminderSentAt IS NULL} is what makes the reminder at-most-once across
     * restarts and across multiple {@code core} replicas both ticking.
     */
    @Query(
        "SELECT d FROM DemoSession d WHERE d.expiredAt IS NULL AND d.reminderSentAt IS NULL "
            + "AND d.expiresAt > :now AND d.expiresAt < :reminderCutoff"
    )
    List<DemoSession> findDueForReminder(@Param("now") Instant now, @Param("reminderCutoff") Instant reminderCutoff);

    /**
     * Abuse control (see {@code DemoSessionService#create}): how many live
     * sandboxes this IP already holds. The Redis rate limiter throttles the
     * RATE of creation; this caps the standing FOOTPRINT, which matters more
     * here because each sandbox costs a whole Postgres schema.
     */
    @Query("SELECT COUNT(d) FROM DemoSession d WHERE d.createdIp = :ip AND d.expiredAt IS NULL AND d.expiresAt > :now")
    long countLiveForIp(@Param("ip") String ip, @Param("now") Instant now);
    /**
     * /try Agent Studio visibility (V339): every live sandbox created from
     * this IP. An agent is shown to a session when its {@code created_by}
     * subject belongs to one of these sessions - "only agents created from
     * your own network address", derived rather than stored.
     */
    @Query("SELECT d FROM DemoSession d WHERE d.createdIp = :ip AND d.expiredAt IS NULL AND d.expiresAt > :now")
    List<DemoSession> findLiveForIp(@Param("ip") String ip, @Param("now") Instant now);
}
