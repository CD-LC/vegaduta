package com.agenticai.core.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The "mine or broadcast" visibility rule from the design contract: a row targets this
     * caller directly ({@code recipientUserId = :userId}), or it's a broadcast
     * ({@code recipientUserId IS NULL}) and the caller is a tenant-admin. {@code unreadOnly}
     * is a single boolean flag rather than two separate query methods so the controller can
     * pass the request param straight through.
     */
    @Query("""
        SELECT n FROM Notification n
        WHERE (n.recipientUserId = :userId OR (n.recipientUserId IS NULL AND :isTenantAdmin = true))
        AND (:unreadOnly = false OR n.read = false)
        ORDER BY n.createdAt DESC, n.id DESC
        """)
    List<Notification> findVisible(
        @Param("userId") String userId,
        @Param("isTenantAdmin") boolean isTenantAdmin,
        @Param("unreadOnly") boolean unreadOnly,
        Pageable pageable
    );

    /** Backs X-Total-Count on GET /api/notifications; same visibility filter as {@link #findVisible}. */
    @Query("""
        SELECT COUNT(n) FROM Notification n
        WHERE (n.recipientUserId = :userId OR (n.recipientUserId IS NULL AND :isTenantAdmin = true))
        AND (:unreadOnly = false OR n.read = false)
        """)
    long countVisible(
        @Param("userId") String userId,
        @Param("isTenantAdmin") boolean isTenantAdmin,
        @Param("unreadOnly") boolean unreadOnly
    );

    /** Backs the cheap, frequently-polled GET /api/notifications/unread-count endpoint. */
    @Query("""
        SELECT COUNT(n) FROM Notification n
        WHERE (n.recipientUserId = :userId OR (n.recipientUserId IS NULL AND :isTenantAdmin = true))
        AND n.read = false
        """)
    long countUnread(@Param("userId") String userId, @Param("isTenantAdmin") boolean isTenantAdmin);

    /** Backs POST /api/notifications/read-all - fetched then saved back by NotificationService, not a bulk @Modifying update, matching this module's existing fetch/mutate/save style (see PlatformIssueService). */
    @Query("""
        SELECT n FROM Notification n
        WHERE (n.recipientUserId = :userId OR (n.recipientUserId IS NULL AND :isTenantAdmin = true))
        AND n.read = false
        """)
    List<Notification> findAllUnreadVisible(@Param("userId") String userId, @Param("isTenantAdmin") boolean isTenantAdmin);
}
