package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What the model is handed when a tool's output is large or does not look like JSON, through the
 * REAL {@link GuardedToolCallback} and the real pattern guardrail (only the tool is a stub).
 *
 * <p>Before: output over 20,000 characters was discarded and replaced by "failed validation and was
 * not returned to the model", and plain text that merely started with {@code [} or {@code {} (a
 * log line, a numbered list) was withheld as "malformed JSON". The side effect had happened; the
 * model never saw what it produced.
 */
class GuardedToolCallbackToolOutputTest {

    private static final String TOOL = "read_report";

    private static GuardedToolCallback guardOver(String toolName, String output, ToolOutputProperties limits) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(toolName);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call("{}")).thenReturn(output);
        GuardedToolCallback guarded = new GuardedToolCallback(
            tool, new AtomicInteger(0), 10, new PatternBasedGuardrailService(limits),
            mock(ApprovalPolicyService.class), mock(HumanApprovalGateService.class), UUID.randomUUID(), "s1");
        return limits == null ? guarded : guarded.withOutputLimits(limits);
    }

    private static GuardedToolCallback guardOver(String output) {
        return guardOver(TOOL, output, new ToolOutputProperties());
    }

    @Test
    void oversizedOutputIsTruncatedWithAMarkerThatStatesTheOriginalLength() {
        String result = guardOver("a".repeat(25_000) + "TAIL").call("{}");

        assertFalse(result.contains("failed validation"), "the output must not be withheld: " + result);
        assertTrue(result.startsWith("aaaa"), "the head of the output is kept");
        assertFalse(result.contains("TAIL"), "the cut-off tail is gone");
        assertTrue(result.contains("25004"), "the marker states the original length: " + tail(result));
        assertTrue(result.length() <= new ToolOutputProperties().getMaxChars(),
            "the cap includes the marker, got " + result.length());
    }

    @Test
    void outputExactlyAtTheCapIsUntouched() {
        String atCap = "b".repeat(new ToolOutputProperties().getMaxChars());

        assertEquals(atCap, guardOver(atCap).call("{}"));
    }

    @Test
    void plainTextThatStartsWithABracketPassesUnchanged() {
        String log = "[INFO] build started\n[INFO] 12 modules\n[WARN] one deprecation";

        assertEquals(log, guardOver(log).call("{}"));
    }

    @Test
    void aNumberedListAndBrokenJsonPassUnchanged() {
        assertEquals("[1] First hit\n[2] Second hit", guardOver("[1] First hit\n[2] Second hit").call("{}"));
        assertEquals("{\"items\":[{\"id\":1},{\"id\":", guardOver("{\"items\":[{\"id\":1},{\"id\":").call("{}"));
    }

    @Test
    void aPerToolLimitOverridesTheDefaultInBothDirections() {
        ToolOutputProperties limits = new ToolOutputProperties();
        limits.getPerTool().put("small_tool", 1_000);
        limits.getPerTool().put("big_tool", 60_000);

        String small = guardOver("small_tool", "c".repeat(5_000), limits).call("{}");
        String big = guardOver("big_tool", "d".repeat(50_000), limits).call("{}");
        String other = guardOver("other_tool", "e".repeat(30_000), limits).call("{}");

        assertTrue(small.length() <= 1_000, "small_tool is capped at its own limit, got " + small.length());
        assertTrue(small.contains("5000"));
        assertEquals("d".repeat(50_000), big, "big_tool may return more than the default");
        assertTrue(other.length() <= limits.getMaxChars(), "an unlisted tool gets the default cap");
    }

    @Test
    void theDefaultCapIsConfigurable() {
        ToolOutputProperties limits = new ToolOutputProperties();
        limits.setMaxChars(3_000);

        String result = guardOver(TOOL, "f".repeat(10_000), limits).call("{}");

        assertTrue(result.length() <= 3_000, "got " + result.length());
        assertTrue(result.contains("10000"));
    }

    private static String tail(String s) {
        return s.substring(Math.max(0, s.length() - 400));
    }
}
