package com.agenticai.core.quality.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.LlmProviderCredential;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderCredentialResolver.RankedProvider;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderUsageSignal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Which model judges, and why. Prod 2026-10-05: calibration judged with the catalog's first OpenRouter
 * row ({@code aion-labs/aion-2.0}) although the workspace's {@code deepseek/deepseek-chat} was answering.
 */
class JudgeChatClientFactoryTest {

    private static final String CATALOG_DEFAULT = "aion-labs/aion-2.0";

    private ProviderCredentialResolver resolver;
    private ProviderModelCatalogService catalog;
    private LlmGatewayService gateway;
    private TenantAiModelPreferenceService prefs;
    private JudgeChatClientFactory factory;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        resolver = mock(ProviderCredentialResolver.class);
        catalog = mock(ProviderModelCatalogService.class);
        gateway = mock(LlmGatewayService.class);
        prefs = mock(TenantAiModelPreferenceService.class);
        when(catalog.defaultModelFor(ProviderName.OPENROUTER)).thenReturn(Optional.of(CATALOG_DEFAULT));
        when(resolver.resolveAnyActiveKey())
            .thenReturn(new ProviderCredentialResolver.ActiveProvider(ProviderName.OPENROUTER, "key-or"));
        @SuppressWarnings("unchecked")
        ObjectProvider<TenantAiModelPreferenceService> prefProvider = mock(ObjectProvider.class);
        when(prefProvider.getIfAvailable()).thenReturn(prefs);
        factory = new JudgeChatClientFactory(gateway, resolver, catalog, null, prefProvider);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static RankedProvider ranked(ProviderName provider, String lastWorkingModel) {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(provider);
        ProviderUsageSignal.Usage usage = lastWorkingModel == null ? null
            : new ProviderUsageSignal.Usage(Instant.now(), lastWorkingModel, null, false);
        return new RankedProvider(credential, usage);
    }

    private void givenRanked(ProviderName provider, String lastWorkingModel) {
        List<RankedProvider> list = List.of(ranked(provider, lastWorkingModel));
        when(resolver.rankActiveProviders()).thenReturn(list);
    }

    @Test
    void theModelOfTheWorkspacesRecentSuccessfulCallBeatsAnObscureCatalogDefault() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");

        JudgeChatClientFactory.JudgeChoice choice = factory.resolveJudge();

        assertEquals("OPENROUTER/deepseek/deepseek-chat", choice.id());
        assertTrue(choice.why().contains("most recent successful"), choice.why());
        assertEquals("OPENROUTER/deepseek/deepseek-chat", factory.resolveJudgeModelIdOrNull());
    }

    @Test
    void withNoCallHistoryTheProvidersDefaultModelJudges() {
        givenRanked(ProviderName.OPENROUTER, null);

        JudgeChatClientFactory.JudgeChoice choice = factory.resolveJudge();

        assertEquals("OPENROUTER/" + CATALOG_DEFAULT, choice.id());
        assertEquals("provider default model", choice.why());
    }

    @Test
    void aModelSetForThisWorkspaceInAiModelControlWins() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");
        when(prefs.resolve("tenant-1", "eval-judge"))
            .thenReturn(Optional.of(new AiModelPreference("OPENAI", "gpt-4.1-mini")));
        when(resolver.resolveActiveKey(ProviderName.OPENAI)).thenReturn("key-openai");

        JudgeChatClientFactory.JudgeChoice choice = factory.resolveJudge();

        assertEquals("OPENAI/gpt-4.1-mini", choice.id());
        assertEquals("key-openai", choice.apiKey());
        assertTrue(choice.why().contains("AI Model Control"), choice.why());
    }

    @Test
    void aWorkspacePreferenceForAProviderItNoLongerHasFallsBackToTheWorkingModel() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");
        when(prefs.resolve("tenant-1", "eval-judge"))
            .thenReturn(Optional.of(new AiModelPreference("OPENAI", "gpt-4.1-mini")));
        when(resolver.resolveActiveKey(ProviderName.OPENAI))
            .thenThrow(new com.agenticai.core.providers.ProviderNotActiveException(ProviderName.OPENAI));

        assertEquals("OPENROUTER/deepseek/deepseek-chat", factory.resolveJudge().id());
    }

    @Test
    void anExplicitDeploymentJudgeModelBeatsTheCallHistory() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");
        ReflectionTestUtils.setField(factory, "configuredJudgeModel", "anthropic/claude-3.5-haiku");

        JudgeChatClientFactory.JudgeChoice choice = factory.resolveJudge();

        // "anthropic" is not a ProviderName, so the whole string is an OpenRouter-style model id.
        assertEquals("OPENROUTER/anthropic/claude-3.5-haiku", choice.id());
        assertTrue(choice.why().contains("app.quality.eval.judge-model"), choice.why());
    }

    @Test
    void aRunScopedOverrideBeatsEverythingAndIsRemovedWithItsScope() throws Exception {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");

        try (AutoCloseable scope = factory.judgeModelScope("openai/gpt-4o-mini")) {
            assertEquals("OPENROUTER/openai/gpt-4o-mini", factory.resolveJudge().id());
        }
        assertEquals("OPENROUTER/deepseek/deepseek-chat", factory.resolveJudge().id());
    }

    @Test
    void theGatewayIsAskedForTheChosenModelNotTheCatalogsFirstRow() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");

        factory.buildJudgeClient(64);

        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(anyString(), spec.capture());
        assertEquals("deepseek/deepseek-chat", spec.getValue().model());
        assertEquals(ProviderName.OPENROUTER, spec.getValue().providerName());
    }

    @Test
    void theProbeFailsWithATimeoutReasonWhenTheJudgeHangs() {
        givenRanked(ProviderName.OPENROUTER, "deepseek/deepseek-chat");
        org.springframework.ai.chat.client.ChatClient client = mock(org.springframework.ai.chat.client.ChatClient.class);
        when(client.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return null;
        });
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        factory.callTimeoutSeconds = 1;

        Optional<String> reason = factory.probeUnavailableReason();

        assertEquals(Optional.of("timed out after 1 s"), reason);
    }
}
