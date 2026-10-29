package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link EmailMessage}: the per-recipient log row a bulk_email workflow send produces. */
class EmailMessageTest {

    private EmailMessage sent() {
        return new EmailMessage(
                "run-1",
                "node-1",
                "lead@example.com",
                "Hello",
                "msg-id-1",
                "thread-1",
                EmailMessage.STATUS_SENT,
                null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void constructorStoresEveryField() {
        EmailMessage m = sent();
        assertEquals("run-1", m.getRunId());
        assertEquals("node-1", m.getNodeId());
        assertEquals("lead@example.com", m.getRecipient());
        assertEquals("Hello", m.getSubject());
        assertEquals("msg-id-1", m.getMessageId());
        assertEquals("thread-1", m.getThreadKey());
        assertEquals(EmailMessage.STATUS_SENT, m.getStatus());
        assertNull(m.getError());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), m.getSentAt());
        assertNull(m.getRepliedAt());
        assertNull(m.getReplyKind());
        assertNotNull(m.getCreatedAt());
    }

    @Test
    void subjectAndErrorAreCappedAtTheirColumnLengths() {
        String longSubject = "s".repeat(1200);
        String longError = "e".repeat(1200);
        EmailMessage m =
                new EmailMessage(
                        "run",
                        "node",
                        "r@x.test",
                        longSubject,
                        "id",
                        "t",
                        EmailMessage.STATUS_FAILED,
                        longError,
                        Instant.now());
        assertEquals(998, m.getSubject().length());
        assertEquals(1000, m.getError().length());
    }

    @Test
    void nullSubjectAndErrorStayNull() {
        EmailMessage m =
                new EmailMessage(
                        "run",
                        "node",
                        "r@x.test",
                        null,
                        "id",
                        "t",
                        EmailMessage.STATUS_SKIPPED,
                        null,
                        Instant.now());
        assertNull(m.getSubject());
        assertNull(m.getError());
    }

    @Test
    void markRepliedStampsTheReply() {
        EmailMessage m = sent();
        Instant at = Instant.parse("2026-02-02T00:00:00Z");
        m.markReplied(at, "AUTO");
        assertEquals(at, m.getRepliedAt());
        assertEquals("AUTO", m.getReplyKind());
    }
}
