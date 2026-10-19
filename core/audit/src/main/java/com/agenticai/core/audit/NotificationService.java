package com.agenticai.core.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Notification bell service - see {@link Notification}'s javadoc for how
 * this relates to {@link PlatformIssueService}'s admin-facing issues. Every
 * read here honours the same "mine or broadcast, and only a tenant-admin
 * sees broadcasts" visibility rule {@link NotificationRepository} encodes at
 * the query level, so a non-admin can never see another user's targeted
 * notification or an admin-only broadcast by calling this service directly.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a notification. {@code recipientUserId} null broadcasts to
     * every tenant-admin (see {@link Notification#Notification}). Best-effort
     * by convention at call sites, same posture as {@code DiagnosticService}'s
     * issue recording - a notification-write failure must never become a
     * second failure on top of whatever the caller was already handling.
     */
    public Notification create(
        String recipientUserId, NotificationCategory category, String title, String body,
        NotificationSeverity severity, String link, UUID relatedIssueId
    ) {
        return repository.save(
            new Notification(recipientUserId, category, title, body, severity, link, relatedIssueId)
        );
    }

    public List<Notification> listVisible(String userId, boolean isTenantAdmin, boolean unreadOnly, Pageable pageable) {
        return repository.findVisible(userId, isTenantAdmin, unreadOnly, pageable);
    }

    /** Total notifications visible to the caller (same rule as {@link #listVisible}); backs X-Total-Count. */
    public long countVisible(String userId, boolean isTenantAdmin, boolean unreadOnly) {
        return repository.countVisible(userId, isTenantAdmin, unreadOnly);
    }

    public long countUnread(String userId, boolean isTenantAdmin) {
        return repository.countUnread(userId, isTenantAdmin);
    }

    /**
     * Marks one notification read. Deliberately does NOT check that the
     * caller is the notification's recipient - the id is opaque (a random
     * UUID never listed to anyone but a visible recipient in the first
     * place, see {@link #listVisible}), so there is nothing meaningful to
     * enumerate or forge; this matches {@code PlatformIssueService#acknowledge}'s
     * equally permissive-by-id posture.
     */
    public void markRead(UUID id) {
        repository.findById(id).ifPresent(n -> {
            n.markRead();
            repository.save(n);
        });
    }

    public void markAllRead(String userId, boolean isTenantAdmin) {
        List<Notification> unread = repository.findAllUnreadVisible(userId, isTenantAdmin);
        unread.forEach(Notification::markRead);
        repository.saveAll(unread);
    }
}
