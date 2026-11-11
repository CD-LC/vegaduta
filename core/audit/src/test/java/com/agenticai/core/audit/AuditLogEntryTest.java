package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Entity coverage for {@link AuditLogEntry}: the append-only record's constructor stores every
 * column verbatim (including a deliberately-null {@code actorId} - an honest "unknown", never a
 * fake "system") and stamps {@code createdAt} itself.
 */
class AuditLogEntryTest {

    @Test
    void constructorStoresEveryFieldAndStampsCreatedAt() {
        AuditLogEntry entry =
                new AuditLogEntry(
                        "alice",
                        "agent.share.grant",
                        "agent",
                        "agent-1",
                        AuditOutcome.SUCCESS,
                        "granted VIEWER to bob");

        assertEquals("alice", entry.getActorId());
        assertEquals("agent.share.grant", entry.getActionType());
        assertEquals("agent", entry.getTargetType());
        assertEquals("agent-1", entry.getTargetId());
        assertEquals(AuditOutcome.SUCCESS, entry.getOutcome());
        assertEquals("granted VIEWER to bob", entry.getDetail());
        assertNotNull(entry.getCreatedAt());
        assertNull(entry.getId()); // @GeneratedValue - only JPA assigns it
    }

    @Test
    void aNullActorAndDetailAreStoredAsIs() {
        AuditLogEntry entry =
                new AuditLogEntry(null, "system.tick", null, null, AuditOutcome.values()[0], null);

        assertNull(entry.getActorId());
        assertNull(entry.getTargetType());
        assertNull(entry.getTargetId());
        assertNull(entry.getDetail());
        assertEquals("system.tick", entry.getActionType());
    }
}
