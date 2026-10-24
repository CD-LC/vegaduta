package com.agenticai.core.agents.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.settings.BehaviourCatalogFixture;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code aux.useAgentModel}: whether a background call (compaction, session summary, assist) falls back to the model of
 * the agent it is made for, for the tenant of the calling thread.
 */
class AuxiliaryModelResolverSettingsTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final AgentService agents = mock(AgentService.class);
    private final TenantAiModelPreferenceService tenantPreferences = mock(TenantAiModelPreferenceService.class);
    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();

    @BeforeEach
    void stubs() {
        when(credentials.resolveBest(false))
                .thenReturn(new ProviderCredentialResolver.ResolvedProvider(ProviderName.OPENAI, "sk-best", "gpt-5-pro", false));
        when(credentials.resolveActiveKey(any())).thenAnswer(inv -> "key-" + inv.getArgument(0));
        when(catalog.defaultModelFor(any())).thenAnswer(inv -> Optional.of("default-of-" + inv.getArgument(0)));
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        when(tenantPreferences.getAll(any())).thenReturn(Map.<String, AiModelPreference>of());
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(ProviderName.ANTHROPIC);
        when(agent.getModel()).thenReturn("claude-opus-x");
        when(agents.getEntity(AGENT_ID)).thenReturn(agent);
    }

    @AfterEach
    void clear() {
        com.agenticai.core.security.TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private AuxiliaryModelResolver resolver(boolean withCatalog) {
        ObjectProvider<TenantAiModelPreferenceService> prefs = mock(ObjectProvider.class);
        when(prefs.getIfAvailable()).thenReturn(tenantPreferences);
        AuxiliaryModelResolver resolver =
                new AuxiliaryModelResolver(f.auxiliary, credentials, catalog, new AgentServiceAuxiliaryAgentModels(agents), prefs, "claude-default");
        if (withCatalog) {
            resolver.setSettingsResolver(f.resolver());
        }
        return resolver;
    }

    private AuxiliaryModelResolver.Source sourceFor(AuxiliaryModelResolver resolver, String tenant) {
        AuxiliaryModelResolver.Source[] out = new AuxiliaryModelResolver.Source[1];
        BehaviourCatalogFixture.inTenant(tenant, () -> out[0] = resolver.select(AuxiliaryPurpose.COMPACTION, AGENT_ID, false).source());
        return out[0];
    }

    @Test
    void withNoOverrideTheAgentsModelIsUsedAsThePropertySays() {
        assertThat(sourceFor(resolver(true), "tenant-a")).isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);

        f.auxiliary.setUseAgentModel(false);
        assertThat(sourceFor(resolver(true), "tenant-a")).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
    }

    @Test
    void aTenantCanSwitchTheAgentModelFallbackOffForItsOwnCalls() {
        f.tenant("tenant-a", "aux.useAgentModel", false);
        AuxiliaryModelResolver resolver = resolver(true);

        assertThat(sourceFor(resolver, "tenant-a")).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
        assertThat(sourceFor(resolver, "tenant-b")).as("another tenant keeps the platform behaviour")
                .isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);
    }

    @Test
    void aTenantCanSwitchItOnAgainstAPlatformThatHasItOff() {
        f.auxiliary.setUseAgentModel(false);
        f.tenant("tenant-a", "aux.useAgentModel", true);
        AuxiliaryModelResolver resolver = resolver(true);

        assertThat(sourceFor(resolver, "tenant-a")).isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);
        assertThat(sourceFor(resolver, "tenant-b")).isEqualTo(AuxiliaryModelResolver.Source.CATALOG_DEFAULT);
    }

    @Test
    void aResolverBuiltWithoutACatalogReadsTheProperty() {
        f.tenant("tenant-a", "aux.useAgentModel", false);

        assertThat(sourceFor(resolver(false), "tenant-a")).isEqualTo(AuxiliaryModelResolver.Source.AGENT_MODEL);
    }
}
