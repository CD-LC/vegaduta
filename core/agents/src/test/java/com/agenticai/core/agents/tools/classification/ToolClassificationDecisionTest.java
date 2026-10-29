package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolClassificationDecision} exists to make "no usable classification" unskippable: {@link
 * ToolClassificationDecision.Blocked} must deny, and getting at the classification anyway must go
 * through the throwing path. These pin that contract.
 */
class ToolClassificationDecisionTest {

    private static ToolClassification classification() {
        return new ToolClassification(
                "fetch_web_page",
                ToolTrustTier.UNTRUSTED_EXTERNAL,
                ToolSensitivity.READ_PUBLIC,
                ToolDeterminism.SIDE_EFFECT,
                null,
                false,
                true);
    }

    @Test
    void aClassifiedDecisionHandsBackTheDeclaration() {
        ToolClassification c = classification();

        ToolClassificationDecision decision = new ToolClassificationDecision.Classified(c);

        assertTrue(decision.isClassified());
        assertEquals("fetch_web_page", decision.toolName());
        assertEquals(c, decision.classificationOrThrow());
        assertEquals(c.riskLabel(), decision.describe());
    }

    @Test
    void aClassifiedDecisionRejectsANullClassification() {
        assertThrows(
                NullPointerException.class, () -> new ToolClassificationDecision.Classified(null));
    }

    @Test
    void aBlockedDecisionDeniesAndThrowsOnTheHappyPath() {
        ToolClassificationDecision decision =
                new ToolClassificationDecision.Blocked(
                        "mcp__github__create_issue",
                        ToolClassificationDecision.Reason.UNCLASSIFIED,
                        "no declaration");

        assertFalse(decision.isClassified());
        assertEquals("mcp__github__create_issue", decision.toolName());
        assertEquals("unclassified - blocked (UNCLASSIFIED)", decision.describe());

        ToolNotClassifiedException e =
                assertThrows(ToolNotClassifiedException.class, decision::classificationOrThrow);
        assertEquals("mcp__github__create_issue", e.getToolName());
        assertEquals(ToolClassificationDecision.Reason.UNCLASSIFIED, e.getReason());
        assertEquals("no declaration", e.getMessage());
    }

    @Test
    void aBlockedDecisionWithBlankDetailGetsTheDefaultExplanation() {
        ToolClassificationDecision.Blocked blocked =
                new ToolClassificationDecision.Blocked(
                        "some_tool", ToolClassificationDecision.Reason.QUARANTINED, "  ");

        assertEquals(
                "Tool 'some_tool' has no usable classification (QUARANTINED).", blocked.detail());
        assertEquals("unclassified - blocked (QUARANTINED)", blocked.describe());
    }

    @Test
    void blockedRejectsNullNameAndReason() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new ToolClassificationDecision.Blocked(
                                null, ToolClassificationDecision.Reason.UNCLASSIFIED, "d"));
        assertThrows(
                NullPointerException.class,
                () -> new ToolClassificationDecision.Blocked("t", null, "d"));
    }

    @Test
    void everyReasonRendersIntoTheDescribe() {
        for (ToolClassificationDecision.Reason reason :
                ToolClassificationDecision.Reason.values()) {
            ToolClassificationDecision decision =
                    new ToolClassificationDecision.Blocked("t", reason, "x");
            assertEquals("unclassified - blocked (" + reason + ")", decision.describe());
        }
    }
}
