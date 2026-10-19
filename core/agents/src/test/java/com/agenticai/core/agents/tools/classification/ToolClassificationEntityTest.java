package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolClassificationEntity#decide()} - the per-row
 * parse-and-validate step that turns a database row into a
 * {@link ToolClassificationDecision} <b>without ever throwing</b>.
 *
 * <p>The reason this is tested in isolation, with no Spring context and no
 * database, is that the interesting cases are all malformed rows, and a malformed
 * row is precisely what a repository-level test cannot easily produce. The cases
 * below are the real ones: a quarantined tool, and a column value written by a
 * deploy whose enum was wider than this build's - the rolling-deploy hazard that is
 * the whole reason those columns are read as raw strings instead of
 * {@code @Enumerated}.
 */
class ToolClassificationEntityTest {

    private static ToolClassificationEntity entity(ToolClassificationStatus status) {
        return new ToolClassificationEntity(
            "google_gmail",
            ToolTrustTier.UNTRUSTED_EXTERNAL,
            ToolSensitivity.EGRESS,
            ToolDeterminism.DURABLE,
            status,
            "https://www.googleapis.com/auth/gmail.send",
            true,
            true,
            "BUILT_IN_SEED",
            "reads an inbound mailbox and can send",
            "platform:built-in");
    }

    /**
     * Overwrites one column with a value the typed constructor cannot produce.
     *
     * <p>This is the point: the constructor takes enums, so the only way a bad
     * value reaches the row is from outside Java - a newer deploy with a wider
     * enum, a hand-written data fix, a bad import. Reflection is how a unit test
     * simulates that, and it is preferable to widening the production constructor
     * to accept raw strings purely so a test can misuse it.
     */
    private static void corrupt(ToolClassificationEntity target, String fieldName, String value) {
        try {
            Field field = ToolClassificationEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("test fixture could not set " + fieldName, e);
        }
    }

    @Test
    void activeRowDecidesToItsClassification() {
        ToolClassificationDecision decision = entity(ToolClassificationStatus.ACTIVE).decide();

        ToolClassification classification = assertInstanceOf(
            ToolClassificationDecision.Classified.class, decision).classification();
        assertEquals("google_gmail", classification.toolName());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, classification.trust());
        assertEquals(ToolSensitivity.EGRESS, classification.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, classification.determinism());
        assertEquals("https://www.googleapis.com/auth/gmail.send", classification.requiredScope());
        assertTrue(classification.irreversible());
        assertTrue(classification.needsToolContext());
        assertTrue(classification.requiresHumanConfirmation());
        assertEquals("UNTRUSTED_EXTERNAL/EGRESS/DURABLE+irreversible", decision.describe());
    }

    @Test
    void quarantinedRowIsBlockedEvenThoughEveryOtherColumnIsValid() {
        // Status is checked FIRST for exactly this reason: a perfectly well-formed
        // quarantined row must not be resolvable as allowed.
        ToolClassificationDecision decision = entity(ToolClassificationStatus.QUARANTINED).decide();

        ToolClassificationDecision.Blocked blocked = assertInstanceOf(
            ToolClassificationDecision.Blocked.class, decision);
        assertEquals(ToolClassificationDecision.Reason.QUARANTINED, blocked.reason());
        assertEquals("google_gmail", blocked.toolName());
        assertTrue(blocked.detail().contains("quarantined"));
        assertFalse(decision.isClassified());
    }

    @Test
    void unrecognisedStatusIsBlockedRatherThanTreatedAsActive() {
        // The tempting shortcut - "anything that is not the literal string
        // QUARANTINED is active" - fails open on a typo and on any future third
        // status whose entire purpose would be to block.
        ToolClassificationEntity row = entity(ToolClassificationStatus.ACTIVE);
        corrupt(row, "status", "PENDING_REVIEW");

        ToolClassificationDecision.Blocked blocked = assertInstanceOf(
            ToolClassificationDecision.Blocked.class, row.decide());
        assertEquals(ToolClassificationDecision.Reason.INVALID_DECLARATION, blocked.reason());
        assertTrue(blocked.detail().contains("PENDING_REVIEW"),
            "the operator needs to see which value was rejected");
    }

    @Test
    void unrecognisedTrustTierIsBlockedPerRowAndDoesNotThrow() {
        // This is the rolling-deploy case: a newer pod introduced PARTNER_ATTESTED
        // and widened the CHECK constraint; this older pod must block that one tool,
        // not fail every tool resolution for the tenant with a Hibernate
        // materialisation error.
        ToolClassificationEntity row = entity(ToolClassificationStatus.ACTIVE);
        corrupt(row, "trust", "PARTNER_ATTESTED");

        ToolClassificationDecision.Blocked blocked = assertInstanceOf(
            ToolClassificationDecision.Blocked.class, row.decide());
        assertEquals(ToolClassificationDecision.Reason.INVALID_DECLARATION, blocked.reason());
        assertTrue(blocked.detail().contains("PARTNER_ATTESTED"));
    }

    @Test
    void unrecognisedSensitivityAndDeterminismAreAlsoBlocked() {
        ToolClassificationEntity badSensitivity = entity(ToolClassificationStatus.ACTIVE);
        corrupt(badSensitivity, "sensitivity", "egress");
        assertEquals(
            ToolClassificationDecision.Reason.INVALID_DECLARATION,
            assertInstanceOf(ToolClassificationDecision.Blocked.class, badSensitivity.decide()).reason(),
            "parsing is case-sensitive on purpose - a lowercase row was written without checking");

        ToolClassificationEntity badDeterminism = entity(ToolClassificationStatus.ACTIVE);
        corrupt(badDeterminism, "determinism", "IDEMPOTENT");
        assertEquals(
            ToolClassificationDecision.Reason.INVALID_DECLARATION,
            assertInstanceOf(ToolClassificationDecision.Blocked.class, badDeterminism.decide()).reason());
    }

    @Test
    void blockedDecisionRefusesToHandOverAClassification() {
        ToolClassificationDecision decision = entity(ToolClassificationStatus.QUARANTINED).decide();

        ToolNotClassifiedException thrown =
            assertThrows(ToolNotClassifiedException.class, decision::classificationOrThrow);
        assertEquals("google_gmail", thrown.getToolName());
        assertEquals(ToolClassificationDecision.Reason.QUARANTINED, thrown.getReason());
    }

    @Test
    void patternRowsAreRecognisedByContainingAStar() {
        ToolClassificationEntity pattern = new ToolClassificationEntity(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, ToolClassificationStatus.ACTIVE, null, false, true,
            "MCP_IMPORT", null, "admin@example.com");

        assertTrue(pattern.isPattern());
        assertFalse(entity(ToolClassificationStatus.ACTIVE).isPattern());
    }

    @Test
    void toStringNeverLeaksNotesOrCredentials() {
        // Entities get logged by accident; the notes field is tenant-authored free
        // text and required_scope can name internal systems.
        String rendered = entity(ToolClassificationStatus.ACTIVE).toString();
        assertTrue(rendered.contains("google_gmail"));
        assertFalse(rendered.contains("gmail.send"));
        assertFalse(rendered.contains("inbound mailbox"));
    }
}
