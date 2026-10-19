package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The 2026-09-28 wrapper-chain fixes: Spring AI's {@link ToolContext} is forwarded rather than
 * dropped, a failed call's message is secret-scrubbed before the model sees it, and the tenant's
 * PII redactor runs on the model-facing result inside the restored tenant scope.
 */
class ToolWrapperContextAndRedactionTest {

    private static final String INPUT = "{}";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantPropagatingWrapperForwardsTheToolContext() {
        ToolCallback delegate = fakeDelegate();
        ToolContext context = new ToolContext(Map.of("k", "v"));
        when(delegate.call(INPUT, context)).thenReturn("{\"ok\":true}");

        String result =
                new TenantContextPropagatingToolCallback(delegate, null).call(INPUT, context);

        assertEquals("{\"ok\":true}", result);
        verify(delegate).call(INPUT, context);
        verify(delegate, never()).call(INPUT);
    }

    @Test
    void stepEventWrapperForwardsTheToolContext() {
        ToolCallback delegate = fakeDelegate();
        ToolContext context = new ToolContext(Map.of("k", "v"));
        when(delegate.call(INPUT, context)).thenReturn("done");

        String result =
                new StepEventToolCallback(delegate, AgentStepEventEmitter.disabled())
                        .call(INPUT, context);

        assertEquals("done", result);
        verify(delegate).call(INPUT, context);
    }

    @Test
    void aFailureMessageIsSecretScrubbedBeforeTheModelSeesIt() {
        ToolCallback delegate = fakeDelegate();
        when(delegate.call(INPUT))
                .thenThrow(
                        new IllegalStateException(
                                "401 from https://api.x.test/v1?api_key=live_abcdef123456 Bearer abcdefghijklmnop"));

        String result = new TenantContextPropagatingToolCallback(delegate, null).call(INPUT);

        assertFalse(result.contains("live_abcdef123456"), result);
        assertFalse(result.contains("abcdefghijklmnop"), result);
        assertTrue(result.contains("401"), "the failure reason itself must survive: " + result);
    }

    @Test
    void theOutputRedactorRunsOnTheFinalResultWithTheTenantInScope() {
        ToolCallback delegate = fakeDelegate();
        when(delegate.call(INPUT)).thenReturn("reach me at a@b.com");
        TenantContext captured = TenantContext.of("tenant-a", "sub", Set.of());
        AtomicReference<Optional<TenantContext>> seenTenant = new AtomicReference<>();

        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(
                        delegate,
                        captured,
                        output -> {
                            seenTenant.set(TenantContext.current());
                            return output.replace("a@b.com", "[REDACTED_EMAIL]");
                        });

        String result = wrapped.call(INPUT);

        assertEquals("{\"result\":\"reach me at [REDACTED_EMAIL]\"}", result);
        assertEquals("tenant-a", seenTenant.get().orElseThrow().getTenantId());
    }

    @Test
    void aThrowingRedactorFailsOpen() {
        ToolCallback delegate = fakeDelegate();
        when(delegate.call(INPUT)).thenReturn("{\"ok\":true}");

        String result =
                new TenantContextPropagatingToolCallback(
                                delegate,
                                null,
                                output -> {
                                    throw new IllegalStateException("policy lookup failed");
                                })
                        .call(INPUT);

        assertEquals("{\"ok\":true}", result);
    }

    private static ToolCallback fakeDelegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("fake_tool");
        when(delegate.getToolDefinition()).thenReturn(definition);
        return delegate;
    }
}
