package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolLedgerProperties} loads the ledger's limits and every model-facing wording from
 * {@code /chat/tool-ledger.properties}. These pin the shipped defaults, the clamping rules the
 * setters enforce (0 floor, {@link ToolLedgerSummary#MAX_FIELD_CHARS} ceiling on the field limits),
 * and that {@link #with} copies rather than mutates the shared platform object.
 */
class ToolLedgerPropertiesTest {

    private final ToolLedgerProperties props = new ToolLedgerProperties();

    @Test
    void defaultsComeFromTheClasspathResource() {
        assertTrue(props.isEnabled());
        assertTrue(props.isReplayEnabled());
        assertEquals(20, props.getMaxEntriesPerTurn());
        assertEquals(160, props.getMaxArgumentChars());
        assertEquals(200, props.getMaxResultChars());
        assertEquals(2_000, props.getMaxReplayCharsPerTurn());
        assertEquals(6, props.getReplayTurns());
        assertEquals("...", props.getCutMarker());
        assertEquals("ok", props.getStatusOk());
        assertEquals("failed", props.getStatusFailed());
        assertEquals("not run", props.getStatusNotRun());
        assertEquals("not run yet, awaiting human approval", props.getStatusAwaitingApproval());
        assertTrue(props.getStatusReplayed().contains("replayed")
            || props.getStatusReplayed().contains("earlier identical call"));
        assertTrue(props.getHeader().startsWith("[Platform tool record"));
        assertTrue(props.getLine().contains("{tool}"));
        assertTrue(props.getDetail().contains("{result}"));
        assertTrue(props.getApprovalDetail().contains("{approval}"));
        assertTrue(props.getOmittedLine().contains("{count}"));
        assertTrue(props.getFailedReplyNote().startsWith("[The reply"));
        assertTrue(props.getTrustNote().contains("platform tool record"));
    }

    @Test
    void statusLabelMapsEveryStatusToItsConfiguredWording() {
        assertEquals("ok", props.statusLabel(ToolLedger.Status.OK));
        assertEquals("failed", props.statusLabel(ToolLedger.Status.FAILED));
        assertEquals("not run", props.statusLabel(ToolLedger.Status.NOT_RUN));
        // A simulated call (offline eval) reads as "not run" to the model, same as a skipped call.
        assertEquals("not run", props.statusLabel(ToolLedger.Status.SIMULATED));
        assertEquals("not run yet, awaiting human approval",
            props.statusLabel(ToolLedger.Status.AWAITING_APPROVAL));
        assertEquals(props.getStatusReplayed(), props.statusLabel(ToolLedger.Status.REPLAYED));
    }

    @Test
    void numericSettersClampAtZeroAndFieldLimitsAtTheScrubCap() {
        props.setMaxEntriesPerTurn(-5);
        props.setMaxReplayCharsPerTurn(-5);
        props.setReplayTurns(-5);
        props.setMaxArgumentChars(-5);
        props.setMaxResultChars(-5);
        assertEquals(0, props.getMaxEntriesPerTurn());
        assertEquals(0, props.getMaxReplayCharsPerTurn());
        assertEquals(0, props.getReplayTurns());
        assertEquals(0, props.getMaxArgumentChars());
        assertEquals(0, props.getMaxResultChars());

        // The credential scrub never inspects past MAX_FIELD_CHARS, so a higher limit would be a lie.
        props.setMaxArgumentChars(100_000);
        props.setMaxResultChars(100_000);
        assertEquals(ToolLedgerSummary.MAX_FIELD_CHARS, props.getMaxArgumentChars());
        assertEquals(ToolLedgerSummary.MAX_FIELD_CHARS, props.getMaxResultChars());
    }

    @Test
    void nullWordingSettersCollapseToEmptyStrings() {
        props.setCutMarker(null);
        props.setHeader(null);
        props.setLine(null);
        props.setDetail(null);
        props.setApprovalDetail(null);
        props.setOmittedLine(null);
        props.setStatusOk(null);
        props.setStatusFailed(null);
        props.setStatusNotRun(null);
        props.setStatusAwaitingApproval(null);
        props.setStatusReplayed(null);
        props.setFailedReplyNote(null);
        props.setTrustNote(null);

        assertEquals("", props.getCutMarker());
        assertEquals("", props.getHeader());
        assertEquals("", props.getLine());
        assertEquals("", props.getDetail());
        assertEquals("", props.getApprovalDetail());
        assertEquals("", props.getOmittedLine());
        assertEquals("", props.getStatusOk());
        assertEquals("", props.getStatusFailed());
        assertEquals("", props.getStatusNotRun());
        assertEquals("", props.getStatusAwaitingApproval());
        assertEquals("", props.getStatusReplayed());
        assertEquals("", props.getFailedReplyNote());
        assertEquals("", props.getTrustNote());
    }

    @Test
    void withCopiesTheWordingAndReplacesOnlyTheTenantSettableNumbers() {
        ToolLedgerProperties tenant = props.with(false, false, 7, 2, 900);

        assertNotSame(props, tenant);
        // The tenant-overridable fields took the new values.
        assertFalse(tenant.isEnabled());
        assertFalse(tenant.isReplayEnabled());
        assertEquals(7, tenant.getMaxEntriesPerTurn());
        assertEquals(2, tenant.getReplayTurns());
        assertEquals(900, tenant.getMaxReplayCharsPerTurn());
        // The wording and the platform's own field limits carried over untouched.
        assertEquals(props.getMaxArgumentChars(), tenant.getMaxArgumentChars());
        assertEquals(props.getMaxResultChars(), tenant.getMaxResultChars());
        assertEquals(props.getCutMarker(), tenant.getCutMarker());
        assertEquals(props.getHeader(), tenant.getHeader());
        assertEquals(props.getStatusOk(), tenant.getStatusOk());
        assertEquals(props.getTrustNote(), tenant.getTrustNote());
        // The shared platform object was not mutated.
        assertTrue(props.isEnabled());
        assertEquals(20, props.getMaxEntriesPerTurn());
    }
}
