package com.agenticai.core.common.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IpBlacklistRepository extends JpaRepository<IpBlacklist, Long> {

    /**
     * Find an active blacklist entry for a given IP.
     * Returns empty if the IP is not blacklisted or the block has expired.
     */
    @Query("SELECT b FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId AND b.ipAddress = :ip " +
           "AND b.expiresAt > CURRENT_TIMESTAMP")
    Optional<IpBlacklist> findActiveByIp(@Param("tenantId") UUID tenantId,
                                         @Param("ip") InetAddress ip);

    /**
     * Check if an IP is currently blacklisted.
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END " +
           "FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId AND b.ipAddress = :ip " +
           "AND b.expiresAt > CURRENT_TIMESTAMP")
    boolean isBlacklisted(@Param("tenantId") UUID tenantId,
                          @Param("ip") InetAddress ip);

    /**
     * Find all active blacklist entries for a tenant.
     */
    @Query("SELECT b FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId " +
           "AND b.expiresAt > CURRENT_TIMESTAMP " +
           "ORDER BY b.createdAt DESC")
    List<IpBlacklist> findAllActive(@Param("tenantId") UUID tenantId);

    /**
     * Find recently added blacklist entries (for admin dashboard).
     */
    @Query("SELECT b FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId " +
           "ORDER BY b.createdAt DESC LIMIT :limit")
    List<IpBlacklist> findRecent(@Param("tenantId") UUID tenantId,
                                 @Param("limit") int limit);

    /**
     * Delete expired blacklist entries (cleanup task).
     */
    @Query("DELETE FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId AND b.expiresAt <= CURRENT_TIMESTAMP")
    void deleteExpired(@Param("tenantId") UUID tenantId);

    /**
     * Count active blacklist entries for a tenant.
     */
    @Query("SELECT COUNT(b) FROM IpBlacklist b " +
           "WHERE b.tenantId = :tenantId AND b.expiresAt > CURRENT_TIMESTAMP")
    long countActive(@Param("tenantId") UUID tenantId);
}
