package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.guardrails.GuardrailFailMode;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolArgPolicyCallbackTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String TOOL = "fetch_web_page";
    private static final String GOOD = "{\"url\":\"https://example.com/x\"}";
    private static final String BAD = "{\"url\":\"https://evil.test/x\"}";

    private ToolCallback delegate;

    /** A source whose behaviour tests configure; records what was reported. */
    private static final class FakeSource implements ToolArgPolicySource {
        ToolArgPolicyEvaluator evaluator;
        RuntimeException lookupFailure;
        RuntimeException reportFailure;
        int lookups;
        String reportedTool;
        String reportedReason;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ToolArgPolicyEvaluator evaluatorFor(UUID agentId) {
            lookups++;
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return evaluator;
        }

        @Override
        public void onViolation(UUID agentId, String sessionId, String toolName, String reason) {
            reportedTool = toolName;
            reportedReason = reason;
            if (reportFailure != null) {
                throw reportFailure;
            }
        }
    }

    private static ToolArgPolicyEvaluator hostPolicy() {
        return new ToolArgPolicyEvaluator(ToolArgPolicySet.parse(
            "{\"policies\":[{\"tool\":\"" + TOOL + "\",\"rules\":[{\"path\":\"url\",\"urlHostIn\":[\"example.com\"]}]}]}"));
    }

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString())).thenReturn("fetched");
    }

    private ToolArgPolicyCallback wrap(FakeSource source, AtomicInteger steps, int max) {
        return new ToolArgPolicyCallback(delegate, source, AGENT, "s1", steps, max);
    }

    @Test
    void aCompliantCallPassesStraightThrough() {
        FakeSource source = new FakeSource();
        source.evaluator = hostPolicy();
        ToolArgPolicyCallback cb = wrap(source, null, 0);

        assertEquals("fetched", cb.call(GOOD));
        verify(delegate).call(GOOD);
        assertNull(source.reportedTool);
    }

    @Test
    void aViolatingCallIsRefusedBeforeTheToolRunsAndExplainedToTheModel() {
        FakeSource source = new FakeSource();
        source.evaluator = hostPolicy();
        ToolArgPolicyCallback cb = wrap(source, null, 0);

        String result = cb.call(BAD);

        verify(delegate, never()).call(anyString());
        assertTrue(result.startsWith("This tool call ('" + TOOL + "') was not executed"), result);
        assertTrue(result.contains("'url' must be an http(s) URL whose host is one of: example.com"), result);
        assertFalse(result.contains("evil.test"), result);
        assertEquals(TOOL, source.reportedTool);
        assertTrue(source.reportedReason.contains("example.com"));
    }

    @Test
    void theToolContextIsForwardedWhenPresent() {
        FakeSource source = new FakeSource();
        source.evaluator = hostPolicy();
        ToolContext context = new ToolContext(java.util.Map.of());
        when(delegate.call(GOOD, context)).thenReturn("with-context");
        ToolArgPolicyCallback cb = wrap(source, null, 0);

        assertEquals("with-context", cb.call(GOOD, context));
        assertTrue(cb.call(BAD, context).contains("was not executed"));
        verify(delegate, never()).call(BAD, context);
    }

    @Test
    void aToolNoPolicyCoversIsNeverEvaluated() {
        FakeSource source = new FakeSource();
        source.evaluator = new ToolArgPolicyEvaluator(ToolArgPolicySet.parse(
            "{\"policies\":[{\"tool\":\"other\",\"rules\":[{\"path\":\"x\",\"type\":\"string\"}]}]}"));
        when(delegate.call("{not even json")).thenReturn("fetched");
        ToolArgPolicyCallback cb = wrap(source, null, 0);

        assertEquals("fetched", cb.call("{not even json"));
    }

    @Test
    void noPoliciesForTheAgentMeansPassThrough() {
        FakeSource source = new FakeSource();
        source.evaluator = null;
        assertEquals("fetched", wrap(source, null, 0).call(BAD));
        assertEquals(1, source.lookups);
    }

    @AfterEach
    void restoreFailMode() {
        GuardrailFailMode.set(GuardrailFailMode.CLOSED);
    }

    /** Behaviour change: the lookup used to fail open whatever app.guardrails.fail-mode said. */
    @Test
    void aFailingLookupFollowsTheGlobalFailModeAndRefusesByDefault() {
        FakeSource source = new FakeSource();
        source.lookupFailure = new IllegalStateException("db down");

        String result = wrap(source, null, 0).call(GOOD);

        assertTrue(result.contains("was not executed"), result);
        assertTrue(result.contains("could not be checked"), result);
        verify(delegate, never()).call(anyString());
    }

    @Test
    void aFailingLookupIsAllowedOnlyWhenTheGlobalFailModeIsOpen() {
        GuardrailFailMode.set(GuardrailFailMode.OPEN);
        FakeSource source = new FakeSource();
        source.lookupFailure = new IllegalStateException("db down");

        assertEquals("fetched", wrap(source, null, 0).call(BAD));
        verify(delegate).call(BAD);
    }

    @Test
    void aRefusedLookupFailureSpendsAStepLikeAnyOtherRefusal() {
        FakeSource source = new FakeSource();
        source.lookupFailure = new IllegalStateException("db down");
        AtomicInteger steps = new AtomicInteger();

        wrap(source, steps, 10).call(GOOD);

        assertEquals(1, steps.get());
    }

    @Test
    void aFailingAuditReportDoesNotChangeTheRefusal() {
        FakeSource source = new FakeSource();
        source.evaluator = hostPolicy();
        source.reportFailure = new IllegalStateException("audit table gone");

        String result = wrap(source, null, 0).call(BAD);

        assertTrue(result.contains("was not executed"), result);
        verify(delegate, never()).call(anyString());
    }

    @Test
    void refusalsSpendTheTurnsStepBudgetAndHitTheCeiling() {
        FakeSource source = new FakeSource();
        source.evaluator = hostPolicy();
        AtomicInteger steps = new AtomicInteger(0);
        ToolArgPolicyCallback cb = wrap(source, steps, 2);

        assertTrue(cb.call(BAD).contains("was not executed"));
        assertTrue(cb.call(BAD).contains("was not executed"));
        assertEquals(2, steps.get());
        // Past the ceiling the model is told the budget is gone (same result as GuardedToolCallback's),
        // not thrown at; only a model that keeps going past the grace is stopped.
        String exhausted = cb.call(BAD);
        assertTrue(ToolBudgetExhausted.isBudgetExhausted(exhausted), exhausted);
        ToolStepLimitProperties noGrace = new ToolStepLimitProperties();
        noGrace.setGraceCalls(0);
        ToolArgPolicyCallback strict = wrap(source, new AtomicInteger(2), 2).withStepLimits(noGrace);
        assertThrows(ToolStepLimitExceededException.class, () -> strict.call(BAD));
        // A compliant call is NOT counted here - GuardedToolCallback counts it when it actually runs.
        AtomicInteger untouched = new AtomicInteger(0);
        assertEquals("fetched", wrap(source, untouched, 2).call(GOOD));
        assertEquals(0, untouched.get());
    }

    @Test
    void exposesTheWrappedToolsDefinition() {
        ToolArgPolicyCallback cb = wrap(new FakeSource(), null, 0);
        assertSame(delegate.getToolDefinition(), cb.getToolDefinition());
    }
}
