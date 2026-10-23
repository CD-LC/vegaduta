package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One notification can be marked read only by someone who can see it (the bell's own rule). */
class NotificationMarkReadVisibilityTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(repository);
    private final UUID id = UUID.randomUUID();

    private Notification notificationFor(String recipient) {
        Notification n =
                new Notification(
                        recipient,
                        NotificationCategory.values()[0],
                        "t",
                        "b",
                        NotificationSeverity.values()[0],
                        null,
                        null);
        when(repository.findById(id)).thenReturn(Optional.of(n));
        return n;
    }

    @Test
    void theRecipientCanMarkTheirOwnNotificationRead() {
        Notification n = notificationFor("alice");

        service.markRead(id, "alice", false);

        assertTrue(n.isRead());
        verify(repository).save(n);
    }

    @Test
    void anotherMemberCannotMarkSomeoneElsesNotificationRead() {
        Notification n = notificationFor("alice");

        service.markRead(id, "bob", false);

        assertFalse(n.isRead());
        verify(repository, never()).save(any());
    }

    @Test
    void evenATenantAdminCannotMarkAMembersTargetedNotificationRead() {
        Notification n = notificationFor("alice");

        service.markRead(id, "admin", true);

        assertFalse(n.isRead());
        verify(repository, never()).save(any());
    }

    @Test
    void aBroadcastCanBeMarkedReadByATenantAdminButNotByAMember() {
        Notification n = notificationFor(null);

        service.markRead(id, "bob", false);
        assertFalse(n.isRead());

        service.markRead(id, "admin", true);
        assertTrue(n.isRead());
        verify(repository).save(n);
    }

    @Test
    void anUnknownIdIsAQuietNoOp() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.markRead(id, "alice", false);

        verify(repository, never()).save(any());
    }
}
