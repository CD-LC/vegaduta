package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link ApprovalContinuationProperties} reads its defaults from {@code
 * /approval/continuation.properties} on the classpath - the values a slim context (or a tenant
 * override) starts from. These pin that the resource is on the module's classpath, that the numeric
 * setters clamp at 0, and that the {@code {x}} placeholders in the note templates are filled
 * exactly as the turn code relies on.
 */
class ApprovalContinuationPropertiesTest {

    private final ApprovalContinuationProperties props = new ApprovalContinuationProperties();

    @Test
    void defaultsComeFromTheClasspathResource() {
        assertTrue(props.isEnabled());
        assertEquals(16_000, props.getMaxResultChars());
        assertEquals(2_000, props.getMaxArgumentsChars());
        assertEquals(20, props.getMaxStickyTools());
        assertEquals(Duration.ofHours(24), props.getStickyToolsWindow());
        assertEquals(Duration.ofDays(7), props.getClaimTtl());
        assertEquals(Duration.ofMinutes(10), props.getTurnTimeout());
        assertEquals(10, props.getMaxOtherPending());
        assertFalse(props.isJobsContinueAfterDenial());
        assertEquals("a reviewer", props.getApproverUnknown());
        assertEquals("no reason was given", props.getNoReasonText());
        assertEquals(500, props.getMaxNoteResultChars());
        assertEquals(160, props.getMaxNoteArgumentsChars());
    }

    @Test
    void messageTemplatesAreLoadedNotNull() {
        assertTrue(props.getDeniedNote().contains("{tool}"));
        assertTrue(props.getCompletedNote().contains("{result}"));
        assertTrue(props.getFailedNote().contains("{failure}"));
        assertTrue(props.getCompletedMessage().contains("{tool}"));
        assertTrue(props.getFailedMessage().contains("{result}"));
        assertTrue(props.getDeniedMessage().contains("{approver}"));
        assertTrue(props.getNoteApproval().contains("{requestId}"));
        assertTrue(props.getOtherPendingHeader().startsWith("Other actions"));
        assertTrue(props.getResultShortenedSuffix().contains("{omitted}"));
        assertTrue(props.getResultNotRecordedText().contains("never recorded"));
        assertTrue(props.getCompletedNoOutputText().contains("no output"));
    }

    @Test
    void numericSettersClampAtZero() {
        props.setMaxResultChars(-1);
        props.setMaxArgumentsChars(-1);
        props.setMaxStickyTools(-1);
        props.setMaxOtherPending(-1);
        props.setMaxNoteResultChars(-1);
        props.setMaxNoteArgumentsChars(-1);

        assertEquals(0, props.getMaxResultChars());
        assertEquals(0, props.getMaxArgumentsChars());
        assertEquals(0, props.getMaxStickyTools());
        assertEquals(0, props.getMaxOtherPending());
        assertEquals(0, props.getMaxNoteResultChars());
        assertEquals(0, props.getMaxNoteArgumentsChars());
    }

    @Test
    void renderDeniedNoteFillsToolApproverAndReason() {
        String note = props.renderDeniedNote("delete_file", "alice@example.com", "too risky");
        assertEquals(
                "[action 'delete_file' was DENIED by alice@example.com: too risky - it did not run]",
                note);
    }

    @Test
    void blankApproverAndReasonFallBackToTheConfiguredStandIns() {
        String note = props.renderDeniedNote("send_email", "  ", null);
        assertEquals(
                "[action 'send_email' was DENIED by a reviewer: no reason was given - it did not run]",
                note);
    }

    @Test
    void renderCompletedAndFailedNotesFillTheirPlaceholders() {
        assertEquals(
                "[approved action 'create_file' completed: file-7]",
                props.renderCompletedNote("create_file", "file-7"));
        assertEquals(
                "[approved action 'create_file' failed: disk full]",
                props.renderFailedNote("create_file", "disk full"));
        // Nulls render as empty text, never the literal "null" or a thrown NPE on a missing result.
        assertEquals("[approved action 't' completed: ]", props.renderCompletedNote("t", null));
        assertEquals("[approved action 't' failed: ]", props.renderFailedNote("t", null));
    }

    @Test
    void renderNoteApprovalNamesTheRequestAndDecider() {
        assertEquals("req-9, decided by bob", props.renderNoteApproval("req-9", "bob"));
        assertEquals(", decided by a reviewer", props.renderNoteApproval(null, null));
    }
}
