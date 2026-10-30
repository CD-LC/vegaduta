package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

/**
 * {@link McpToolHints} carries the four safety hints an MCP server may attach to a tool, each
 * nullable because "absent" is a distinct answer. {@link McpToolHints#hasRiskHint} is the gate on
 * whether the annotations said anything about risk at all - a title or an idempotent hint alone
 * must not change classification.
 */
class McpToolHintsTest {

    @Test
    void noneHasNoHintsAndNoRiskHint() {
        assertNull(McpToolHints.NONE.readOnly());
        assertNull(McpToolHints.NONE.destructive());
        assertNull(McpToolHints.NONE.idempotent());
        assertNull(McpToolHints.NONE.openWorld());
        assertFalse(McpToolHints.NONE.hasRiskHint());
    }

    @Test
    void readOnlyOrDestructiveHintsAlwaysBearOnRisk() {
        assertTrue(new McpToolHints(true, null, null, null).hasRiskHint());
        assertTrue(
                new McpToolHints(false, null, null, null).hasRiskHint(),
                "an explicit readOnlyHint=false is a statement too");
        assertTrue(new McpToolHints(null, true, null, null).hasRiskHint());
        assertTrue(new McpToolHints(null, false, null, null).hasRiskHint());
    }

    @Test
    void onlyAnExplicitOpenWorldTrueBearsOnRisk() {
        assertTrue(new McpToolHints(null, null, null, true).hasRiskHint());
        assertFalse(
                new McpToolHints(null, null, null, false).hasRiskHint(),
                "openWorldHint=false says the tool stays inside its domain - no risk signal");
        assertFalse(
                new McpToolHints(null, null, true, null).hasRiskHint(),
                "an idempotent hint alone says nothing about whether the tool changes anything");
    }

    @Test
    void fromNullAnnotationsYieldsTheSharedNone() {
        assertSame(McpToolHints.NONE, McpToolHints.from(null));
    }

    @Test
    void fromExtractsEveryHintField() {
        McpSchema.ToolAnnotations annotations =
                new McpSchema.ToolAnnotations("t", true, false, true, false, null);

        McpToolHints hints = McpToolHints.from(annotations);

        assertEquals(Boolean.TRUE, hints.readOnly());
        assertEquals(Boolean.FALSE, hints.destructive());
        assertEquals(Boolean.TRUE, hints.idempotent());
        assertEquals(Boolean.FALSE, hints.openWorld());
        assertTrue(hints.hasRiskHint());
    }
}
