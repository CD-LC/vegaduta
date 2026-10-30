package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * {@link NotificationService}: {@code create} persists a fresh unread {@link Notification}
 * verbatim, the list/count reads pass the caller's visibility arguments straight through to the
 * repository's "mine or broadcast" queries, and {@code markAllRead} marks then saves the whole
 * visible unread set. (Per-id {@code markRead} visibility is covered by {@link
 * NotificationMarkReadVisibilityTest}.)
 */
class NotificationServiceTest {

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        service = new NotificationService(repository);
    }

    @Test
    void createPersistsTheNotificationVerbatim() {
        UUID issueId = UUID.randomUUID();
        when(repository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification saved =
                service.create(
                        "alice",
                        NotificationCategory.values()[0],
                        "Workflow failed",
                        "run 42 blew up",
                        NotificationSeverity.values()[0],
                        "/workflows/1/runs/42",
                        issueId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        Notification stored = captor.getValue();
        assertSame(saved, stored);
        assertEquals("alice", stored.getRecipientUserId());
        assertEquals("Workflow failed", stored.getTitle());
        assertEquals(issueId, stored.getRelatedIssueId());
        assertTrue(!stored.isRead(), "a fresh notification starts unread");
    }

    @Test
    void listAndCountReadsPassTheVisibilityArgumentsThrough() {
        Pageable pageable = mock(Pageable.class);
        List<Notification> rows = List.of(mock(Notification.class));
        when(repository.findVisible("bob", true, true, pageable)).thenReturn(rows);
        when(repository.countVisible("bob", true, true)).thenReturn(7L);
        when(repository.countUnread("bob", true)).thenReturn(3L);

        assertSame(rows, service.listVisible("bob", true, true, pageable));
        assertEquals(7L, service.countVisible("bob", true, true));
        assertEquals(3L, service.countUnread("bob", true));
    }

    @Test
    void markAllReadMarksAndSavesEveryVisibleUnreadRow() {
        Notification first =
                new Notification(
                        "bob",
                        NotificationCategory.values()[0],
                        "t1",
                        "b1",
                        NotificationSeverity.values()[0],
                        null,
                        null);
        Notification second =
                new Notification(
                        null,
                        NotificationCategory.values()[0],
                        "t2",
                        "b2",
                        NotificationSeverity.values()[0],
                        null,
                        null);
        when(repository.findAllUnreadVisible("bob", true)).thenReturn(List.of(first, second));

        service.markAllRead("bob", true);

        assertTrue(first.isRead());
        assertTrue(second.isRead());
        verify(repository).saveAll(List.of(first, second));
    }
}
