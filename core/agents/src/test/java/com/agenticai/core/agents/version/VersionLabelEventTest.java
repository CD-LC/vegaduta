package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link VersionLabelEvent} is the append-only audit row behind every label move. These pin that
 * {@code of} stamps its own timestamp and that an over-long detail is trimmed to the 500-char
 * column rather than rejected - an audit row is worth more than a perfect detail string.
 */
class VersionLabelEventTest {

    @Test
    void ofRecordsTheMoveAndStampsTheTime() {
        UUID entityId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        VersionLabelEvent e =
                VersionLabelEvent.of(
                        VersionEntityType.AGENT,
                        entityId,
                        "PRODUCTION",
                        versionId,
                        LabelEventAction.PROMOTE,
                        "user-sub",
                        "promoted via the console");

        assertEquals(VersionEntityType.AGENT, e.getEntityType());
        assertEquals(entityId, e.getEntityId());
        assertEquals("PRODUCTION", e.getLabel());
        assertEquals(versionId, e.getVersionId());
        assertEquals(LabelEventAction.PROMOTE, e.getAction());
        assertEquals("user-sub", e.getAppliedBy());
        assertEquals("promoted via the console", e.getDetail());
        assertNotNull(e.getAppliedAt());
    }

    @Test
    void workflowRowsAndEveryActionShareTheOneTable() {
        VersionLabelEvent e =
                VersionLabelEvent.of(
                        VersionEntityType.WORKFLOW,
                        UUID.randomUUID(),
                        "STAGING",
                        UUID.randomUUID(),
                        LabelEventAction.BACKFILL,
                        null,
                        null);

        assertEquals(VersionEntityType.WORKFLOW, e.getEntityType());
        assertEquals(LabelEventAction.BACKFILL, e.getAction());
        assertNull(e.getAppliedBy());
        assertNull(e.getDetail());
    }

    @Test
    void anOverLongDetailIsTrimmedToTheColumn() {
        VersionLabelEvent e =
                VersionLabelEvent.of(
                        VersionEntityType.AGENT,
                        UUID.randomUUID(),
                        "PRODUCTION",
                        UUID.randomUUID(),
                        LabelEventAction.ROLLBACK,
                        "u",
                        "d".repeat(600));

        assertEquals(500, e.getDetail().length());
    }
}
