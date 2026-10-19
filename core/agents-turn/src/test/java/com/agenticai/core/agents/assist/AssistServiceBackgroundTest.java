package com.agenticai.core.agents.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCoolingDownException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Prod 2026-10-05 (R1): the hourly improvement sweep called a Vertex key the provider had refused, 24 failed calls a
 * day for two weeks. Its calls (this class's background completion) must not reach a provider that is cooling down,
 * must use a working alternative when there is one, and a person-driven completion is unchanged.
 */
class AssistServiceBackgroundTest {

    private ProviderCredentialResolver resolver;
    private LlmGatewayService gateway;
    private ProviderModelCatalogService catalog;
    private AssistService service;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        resolver = mock(ProviderCredentialResolver.class);
        gateway = mock(LlmGatewayService.class);
        catalog = mock(ProviderModelCatalogService.class);
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(" done ");
        service = new AssistService(resolver, mock(ChatClientRegistry.class), gateway, catalog, limits,
            mock(FreeTierChatClientService.class), "claude-x", 1024);
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aBackgroundCompletionUsesTheWorkingAlternativeAndThatProvidersDefaultModel() {
        ProviderCredentialResolver.ResolvedProvider openRouter = new ProviderCredentialResolver.ResolvedProvider(
            ProviderName.OPENROUTER, "or-key", "deepseek/deepseek-chat", false);
        when(resolver.resolveBest(true)).thenReturn(openRouter);
        when(catalog.defaultModelFor(ProviderName.OPENROUTER)).thenReturn(Optional.of("openai/gpt-4o-mini"));
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);

        assertEquals("done", service.completeInBackground("system", "user"));

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(anyString(), spec.capture());
        assertEquals(ProviderName.OPENROUTER, spec.getValue().providerName());
        assertEquals("openai/gpt-4o-mini", spec.getValue().model());
        verify(resolver, never()).resolveBest(false);
    }

    @Test
    void aBackgroundCompletionAgainstACoolingDownProviderMakesNoCallAtAll() {
        when(resolver.resolveBest(true)).thenThrow(new ProviderCoolingDownException(
            ProviderName.GOOGLE_VERTEX, Instant.now(), Duration.ofHours(6)));

        assertThrows(ProviderCoolingDownException.class, () -> service.completeInBackground("system", "user"));

        verify(gateway, never()).resolveClient(anyString(), any(GatewayCallSpec.class));
    }

    @Test
    void aPersonDrivenCompletionStillResolvesWithoutTheCooldown() {
        ProviderCredentialResolver.ResolvedProvider vertex = new ProviderCredentialResolver.ResolvedProvider(
            ProviderName.GOOGLE_VERTEX, "key", null, true);
        when(resolver.resolveBest(false)).thenReturn(vertex);
        when(catalog.defaultModelFor(ProviderName.GOOGLE_VERTEX)).thenReturn(Optional.of("gemini-2.0-flash-001"));
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);

        assertEquals("done", service.complete("system", "user"));

        verify(resolver).resolveBest(false);
        verify(resolver, never()).resolveBest(true);
    }
}
