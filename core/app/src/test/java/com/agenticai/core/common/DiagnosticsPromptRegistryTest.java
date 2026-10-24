package com.agenticai.core.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.common.diagnostics.SensitiveDataMasker;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** The failure-triage system prompt is the registry key {@code diagnostics.failure_triage}. */
class DiagnosticsPromptRegistryTest {

    private static final String GOLDEN =
            "You are an automated triage assistant analyzing one detected platform failure (a guardrail\n"
                    + "block or an upstream LLM provider failure) so it can be categorized and, when warranted,\n"
                    + "surfaced to a tenant admin as a notification. You are given the failure's source and a\n"
                    + "redacted excerpt of its raw context - never ask for more detail or unredacted data, just\n"
                    + "analyze what's given.\n"
                    + "\n"
                    + "Respond with EXACTLY two lines, no other text:\n"
                    + "CATEGORY: <one of TOOL_FAILURE|SAFETY|SYSTEM|WORKFLOW|BILLING>\n"
                    + "EXPLANATION: <one or two plain-English sentences a support engineer or the affected tenant could act on>\n";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String systemSent(boolean withResolver) {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(any(String.class)).user(any(String.class)).call().content())
                .thenReturn("CATEGORY: SYSTEM\nEXPLANATION: something broke");
        AiDiagnosticAnalysisService service =
                new AiDiagnosticAnalysisService(
                        mock(PlatformIssueService.class), mock(NotificationService.class), new SensitiveDataMasker(), Runnable::run);
        if (withResolver) {
            service.setPromptResolver(resolver);
        }
        service.analyzeAsync(client, UUID.randomUUID(), IssueSource.UPSTREAM_PROVIDER_FAILURE, "openai", "HTTP 500", null);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).system(system.capture());
        return system.getValue();
    }

    @Test
    void theShippedPromptIsByteIdenticalToTheTextBlockItReplaced() {
        assertThat(PromptResolver.defaultsOnly().text(AiDiagnosticAnalysisService.ANALYSIS_PROMPT_KEY)).isEqualTo(GOLDEN);
        assertThat(systemSent(false)).isEqualTo(GOLDEN);
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void aTenantOverrideReachesTheRealCallPath() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", AiDiagnosticAnalysisService.ANALYSIS_PROMPT_KEY, "en", "Triage it.\nCATEGORY: <x>\nEXPLANATION: <y>", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(systemSent(true)).startsWith("Triage it.");
    }
}
