package com.agenticai.core.common.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AbuseEventRepository extends JpaRepository<AbuseEvent, Long> {

    /**
     * Count recent failed auth attempts from a specific IP within a time window.
     */
    @Query("SELECT COUNT(a) FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.clientIp = :ip " +
           "AND a.eventType = 'AUTH_BRUTE_FORCE' AND a.decision = 'BLOCK' " +
           "AND a.createdAt > :since")
    long countRecentFailedAuthFromIp(@Param("tenantId") UUID tenantId,
                                     @Param("ip") InetAddress ip,
                                     @Param("since") Instant since);

    /**
     * Count recent failed auth attempts for a specific user within a time window.
     */
    @Query("SELECT COUNT(a) FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.userId = :userId " +
           "AND a.eventType = 'AUTH_BRUTE_FORCE' AND a.decision IN ('CHALLENGE', 'BLOCK') " +
           "AND a.createdAt > :since")
    long countRecentFailedAuthForUser(@Param("tenantId") UUID tenantId,
                                      @Param("userId") UUID userId,
                                      @Param("since") Instant since);

    /**
     * Get recent abuse events for a given IP address.
     */
    @Query("SELECT a FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.clientIp = :ip " +
           "ORDER BY a.createdAt DESC LIMIT :limit")
    List<AbuseEvent> findRecentByIp(@Param("tenantId") UUID tenantId,
                                    @Param("ip") InetAddress ip,
                                    @Param("limit") int limit);

    /**
     * Get recent abuse events for a given user.
     */
    @Query("SELECT a FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.userId = :userId " +
           "ORDER BY a.createdAt DESC LIMIT :limit")
    List<AbuseEvent> findRecentByUser(@Param("tenantId") UUID tenantId,
                                      @Param("userId") UUID userId,
                                      @Param("limit") int limit);

    /**
     * Get critical abuse events in the last N hours.
     */
    @Query("SELECT a FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.severity = 'CRITICAL' " +
           "AND a.createdAt > :since " +
           "ORDER BY a.createdAt DESC")
    List<AbuseEvent> findRecentCriticalEvents(@Param("tenantId") UUID tenantId,
                                              @Param("since") Instant since);

    /**
     * Count abuse events by type in a given time window.
     */
    @Query("SELECT a.eventType, COUNT(a) FROM AbuseEvent a " +
           "WHERE a.tenantId = :tenantId AND a.createdAt > :since " +
           "GROUP BY a.eventType")
    List<Object[]> countEventsByType(@Param("tenantId") UUID tenantId,
                                     @Param("since") Instant since);
}
