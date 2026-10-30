package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Entity coverage for {@link Notification}: constructor defaults (unread, timestamped, id
 * unassigned until JPA), {@code markRead}, and the targeted-vs-broadcast distinction carried by
 * {@code recipientUserId}.
 */
class NotificationTest {

    @Test
    void constructorDefaultsToUnreadWithACreatedTimestamp() {
        UUID issueId = UUID.randomUUID();

        Notification notification =
                new Notification(
                        "alice",
                        NotificationCategory.values()[0],
                        "Workflow failed",
                        "run 42 blew up",
                        NotificationSeverity.values()[0],
                        "/workflows/1/runs/42",
                        issueId);

        assertEquals("alice", notification.getRecipientUserId());
        assertEquals(NotificationCategory.values()[0], notification.getCategory());
        assertEquals("Workflow failed", notification.getTitle());
        assertEquals("run 42 blew up", notification.getBody());
        assertEquals(NotificationSeverity.values()[0], notification.getSeverity());
        assertEquals("/workflows/1/runs/42", notification.getLink());
        assertEquals(issueId, notification.getRelatedIssueId());
        assertFalse(notification.isRead());
        assertNotNull(notification.getCreatedAt());
        assertNull(notification.getId()); // @GeneratedValue - only JPA assigns it
    }

    @Test
    void aNullRecipientIsTheTenantAdminBroadcast() {
        Notification broadcast =
                new Notification(
                        null,
                        NotificationCategory.values()[0],
                        "t",
                        "b",
                        NotificationSeverity.values()[0],
                        null,
                        null);

        assertNull(broadcast.getRecipientUserId());
    }

    @Test
    void markReadFlipsTheFlag() {
        Notification notification =
                new Notification(
                        "alice",
                        NotificationCategory.values()[0],
                        "t",
                        "b",
                        NotificationSeverity.values()[0],
                        null,
                        null);

        notification.markRead();

        assertTrue(notification.isRead());
    }
}
