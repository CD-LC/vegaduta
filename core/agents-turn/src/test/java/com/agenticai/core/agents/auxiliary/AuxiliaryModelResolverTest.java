package com.agenticai.core.agents.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AiModelFeature;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The background model calls (compaction, session summary, assist, judge) picked {@code
 * listModels(provider).get(0)} of whichever credential came first: the first model ALPHABETICALLY, an
 * accident of sort order that once landed on a retired model. The model is now chosen explicitly.
 */
class AuxiliaryModelResolverTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final AgentService agents = mock(AgentService.class);
    private final TenantAiModelPreferenceService tenantPreferences = mock(TenantAiModelPreferenceService.class);
    private final AuxiliaryModelProperties properties = new AuxiliaryModelProperties();

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of("USER")));
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.OPENAI, "sk-best", "gpt-5-pro", false));
        when(credentials.resolveActiveKey(any())).thenAnswer(inv -> "key-" + inv.getArgument(0));
        when(catalog.defaultModelFor(any())).thenAnswer(inv -> Optional.of("default-of-" + inv.getArgument(0)));
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        when(tenantPreferences.getAll("tenant-1")).thenReturn(Map.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private AuxiliaryModelResolver resolver() {
        return new AuxiliaryModelResolver(properties, credentials, catalog, new AgentServiceAuxiliaryAgentModels(agents), provider(tenantPreferences), "claude-default");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void agentOn(ProviderName provider, String model) {
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(provider);
        when(agent.getModel()).thenReturn(model);
        when(agents.getEntity(AGENT_ID)).thenReturn(agent);
    }

    @Test
    void neverTakesTheFirstAlphabeticalCatalogEntry() {
        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.COMPACTION, null, false);

        assertThat(selection.provider()).isEqualTo(ProviderName.OPENAI);
        assertThat(selection.model()).isEqualTo("default-of-OPENAI");
        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
        verify(catalog, never()).listModels(any());
    }

    @Test
    void aCallMadeForAnAgentUsesTheAgentsOwnProviderAndModel() {
        agentOn(ProviderName.ANTHROPIC, "claude-opus-x");

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.SESSION_SUMMARY, AGENT_ID, false);

        assertThat(selection.provider()).isEqualTo(ProviderName.ANTHROPIC);
        assertThat(selection.apiKey()).isEqualTo("key-ANTHROPIC");
        assertThat(selection.model()).isEqualTo("claude-opus-x");
        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);
    }

    @Test
    void aRetiredModelOfTheAgentIsReplacedLikeTheChatPathDoesIt() {
        agentOn(ProviderName.GROK, "grok-3");
        when(catalog.replacementForRetiredModel("grok-3")).thenReturn(Optional.of("grok-4.3"));

        assertThat(resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false).model()).isEqualTo("grok-4.3");
    }

    @Test
    void theAgentsOwnModelCanBeSwitchedOff() {
        agentOn(ProviderName.ANTHROPIC, "claude-opus-x");
        properties.setUseAgentModel(false);

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false);

        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
        verify(agents, never()).getEntity(any());
    }

    @Test
    void thePlatformChoiceBeatsTheAgentsModel() {
        agentOn(ProviderName.ANTHROPIC, "claude-opus-x");
        properties.getCompaction().setProvider(ProviderName.GEMINI);
        properties.getCompaction().setModel("gemini-flash");

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false);

        assertThat(selection.provider()).isEqualTo(ProviderName.GEMINI);
        assertThat(selection.model()).isEqualTo("gemini-flash");
        assertThat(selection.apiKey()).isEqualTo("key-GEMINI");
        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.PLATFORM_SETTING);
    }

    @Test
    void theChoiceIsPerPurpose() {
        properties.getCompaction().setProvider(ProviderName.GEMINI);

        assertThat(resolver().select(AuxiliaryPurpose.COMPACTION, null, false).provider()).isEqualTo(ProviderName.GEMINI);
        assertThat(resolver().select(AuxiliaryPurpose.SESSION_SUMMARY, null, false).provider()).isEqualTo(ProviderName.OPENAI);
    }

    @Test
    void aProviderWithoutAModelMeansThatProvidersCatalogDefault() {
        properties.getJudge().setProvider(ProviderName.MISTRAL);

        assertThat(resolver().select(AuxiliaryPurpose.JUDGE, null, false).model()).isEqualTo("default-of-MISTRAL");
    }

    @Test
    void aModelWithoutAProviderIsIgnored() {
        properties.getAssist().setModel("some-model");

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.ASSIST, null, false);

        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
        assertThat(selection.model()).isNotEqualTo("some-model");
    }

    @Test
    void aPlatformChoiceWhoseCredentialIsNotActiveFallsToTheNextLayer() {
        agentOn(ProviderName.ANTHROPIC, "claude-opus-x");
        properties.getCompaction().setProvider(ProviderName.GEMINI);
        when(credentials.resolveActiveKey(ProviderName.GEMINI)).thenThrow(new ProviderNotActiveException(ProviderName.GEMINI));

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false);

        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);
        assertThat(selection.provider()).isEqualTo(ProviderName.ANTHROPIC);
    }

    @Test
    void theTenantsOwnPreferenceForThePurposeBeatsThePlatformChoice() {
        properties.getAssist().setProvider(ProviderName.GEMINI);
        when(tenantPreferences.getAll("tenant-1"))
                .thenReturn(Map.of("assist", new AiModelPreference("MISTRAL", "mistral-small-x")));

        AuxiliaryModelResolver.Selection selection = resolver().select(AuxiliaryPurpose.ASSIST, null, false);

        assertThat(selection.provider()).isEqualTo(ProviderName.MISTRAL);
        assertThat(selection.model()).isEqualTo("mistral-small-x");
        assertThat(selection.source()).isEqualTo(AuxiliaryModelResolver.Source.TENANT_PREFERENCE);
    }

    /**
     * The compaction and session-summary rows of the AI Model Control page (the {@link AiModelFeature} rows a workspace
     * administrator sets) are the first choice for those background calls: above the platform's choice and the agent's model.
     */
    @Test
    void aTenantRowForCompactionAndForSessionSummaryWinsOverThePlatformChoiceAndTheAgentsModel() {
        agentOn(ProviderName.ANTHROPIC, "claude-opus-x");
        properties.getCompaction().setProvider(ProviderName.GEMINI);
        properties.getCompaction().setModel("gemini-flash");
        properties.getSessionSummary().setProvider(ProviderName.GEMINI);
        properties.getSessionSummary().setModel("gemini-flash");
        when(tenantPreferences.getAll("tenant-1"))
                .thenReturn(Map.of(
                        AiModelFeature.COMPACTION.key(), new AiModelPreference("MISTRAL", "mistral-compactor"),
                        AiModelFeature.SESSION_SUMMARY.key(), new AiModelPreference("DEEPSEEK", "deepseek-summariser")));

        AuxiliaryModelResolver.Selection compaction = resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false);
        AuxiliaryModelResolver.Selection summary = resolver().select(AuxiliaryPurpose.SESSION_SUMMARY, AGENT_ID, false);

        assertThat(compaction.source()).isEqualTo(AuxiliaryModelResolver.Source.TENANT_PREFERENCE);
        assertThat(compaction.provider()).isEqualTo(ProviderName.MISTRAL);
        assertThat(compaction.model()).isEqualTo("mistral-compactor");
        assertThat(summary.source()).isEqualTo(AuxiliaryModelResolver.Source.TENANT_PREFERENCE);
        assertThat(summary.provider()).isEqualTo(ProviderName.DEEPSEEK);
        assertThat(summary.model()).isEqualTo("deepseek-summariser");
    }

    @Test
    void aTenantRowForOnePurposeDoesNotTouchTheOtherBackgroundPurposes() {
        properties.getSessionSummary().setProvider(ProviderName.GEMINI);
        properties.getSessionSummary().setModel("gemini-flash");
        when(tenantPreferences.getAll("tenant-1"))
                .thenReturn(Map.of(AiModelFeature.COMPACTION.key(), new AiModelPreference("MISTRAL", "mistral-compactor")));

        AuxiliaryModelResolver.Selection summary = resolver().select(AuxiliaryPurpose.SESSION_SUMMARY, null, false);

        assertThat(summary.source()).isEqualTo(AuxiliaryModelResolver.Source.PLATFORM_SETTING);
        assertThat(summary.model()).isEqualTo("gemini-flash");
    }

    @Test
    void anAgentThatCannotBeLoadedFallsToTheCatalogDefault() {
        when(agents.getEntity(AGENT_ID)).thenThrow(new IllegalStateException("gone"));

        assertThat(resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false).source())
                .isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
    }

    @Test
    void anAgentOnThePlatformPoolHasNoCredentialOfItsOwnSoTheBestCredentialIsUsed() {
        agentOn(ProviderName.PLATFORM_HOSTED, "platform-pool");

        assertThat(resolver().select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false).source())
                .isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
        verify(credentials, never()).resolveActiveKey(ProviderName.PLATFORM_HOSTED);
    }

    @Test
    void anEmptyCatalogFallsBackToTheConfiguredDefaultModelOnlyForAnthropic() {
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.ANTHROPIC, "k", null, false));
        when(catalog.defaultModelFor(any())).thenReturn(Optional.empty());
        assertThat(resolver().select(AuxiliaryPurpose.COMPACTION, null, false).model()).isEqualTo("claude-default");

        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.GROQ, "k", null, false));
        assertThatThrownBy(() -> resolver().select(AuxiliaryPurpose.COMPACTION, null, false))
                .isInstanceOf(ProviderModelCatalogEmptyException.class);
    }

    @Test
    void noCredentialAtAllIsTheUsualNoProviderError() {
        when(credentials.resolveBest(false)).thenThrow(new NoActiveProviderException());

        assertThatThrownBy(() -> resolver().select(AuxiliaryPurpose.COMPACTION, null, false))
                .isInstanceOf(NoActiveProviderException.class);
    }

    @Test
    void aBackgroundCallerAsksForTheCooldownAwareCredential() {
        when(credentials.resolveBest(true))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.GROQ, "k", null, false));

        assertThat(resolver().select(AuxiliaryPurpose.ASSIST, null, true).provider()).isEqualTo(ProviderName.GROQ);
    }

    @Test
    void theFeatureKeyOfThePurposeIsWhatTheGatewayLooksTheTenantPreferenceUpBy() {
        assertThat(List.of(AuxiliaryPurpose.values()).stream().map(AuxiliaryPurpose::featureKey))
                .containsExactly("compaction", "session-summary", "assist", "eval-judge");
    }
}
