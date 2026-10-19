package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.agenticai.core.rl.api.RlArmRejectedException;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The safety gate on RL {@code MODEL_ROUTE} arms. A stored arm overrides the
 * agent's MODEL for a turn while keeping the agent's own PROVIDER, so the only
 * question that matters is "does this agent's current provider serve this
 * model, for this tenant" - not "is this provider/model pair usable in the
 * abstract". These tests pin that framing, because getting it wrong is exactly
 * how a BYOK agent ends up routed at a model its key cannot call, on an
 * unpredictable fraction of turns.
 *
 * <p>Every check here delegates to machinery the chat path itself uses -
 * {@code ProviderCredentialResolver#resolveActiveKey}, {@code
 * ProviderModelCatalogService#findModel}, {@code TenantLimits} - so a "yes"
 * from this validator means the same thing a "yes" at chat time would.
 */
class RlModelRouteArmValidatorTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentService agentService;
    private ProviderCredentialResolver credentialResolver;
    private ProviderModelCatalogService providerModelCatalogService;
    private TenantLimits tenantLimits;
    private RlModelRouteArmValidator validator;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        credentialResolver = mock(ProviderCredentialResolver.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        tenantLimits = mock(TenantLimits.class);
        validator = new RlModelRouteArmValidator(
            agentService, credentialResolver, providerModelCatalogService, tenantLimits);

        TenantContext.set(TenantContext.of("acme", "admin-1", Set.of("tenant-admin")));
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(false);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void stubAgentOn(ProviderName provider) {
        Agent agent = new Agent(
            "Test Agent", "You are helpful.", provider, "gpt-4o",
            1024, 0.7, 1.0, false, ExperienceMode.ADVANCED
        );
        when(agentService.get(AGENT_ID)).thenReturn(AgentResponse.from(agent));
    }

    private void stubCatalogHas(ProviderName provider, String model) {
        when(providerModelCatalogService.findModel(provider, model))
            .thenReturn(Optional.of(mock(ProviderModelResponse.class)));
    }

    private void validate(String armConfigJson) {
        validator.validateArm(AGENT_ID, PolicyDimension.MODEL_ROUTE, "an-arm", armConfigJson);
    }

    // --- happy path ---

    @Test
    void acceptsAModelTheAgentsOwnProviderServesAndTheTenantHasAKeyFor() {
        stubAgentOn(ProviderName.OPENAI);
        stubCatalogHas(ProviderName.OPENAI, "gpt-4o-mini");

        assertThatCode(() -> validate("{\"model\":\"gpt-4o-mini\"}")).doesNotThrowAnyException();

        // The credential check is the same call buildChatClientFor makes, so
        // "validator says yes" and "chat can reach it" are one question.
        verify(credentialResolver).resolveActiveKey(ProviderName.OPENAI);
    }

    // --- the crux: the model must fit the AGENT's provider, not just some provider ---

    @Test
    void rejectsAModelThisTenantCannotUseBecauseTheAgentsProviderDoesNotServeIt() {
        // The tenant may well hold an Anthropic key too - irrelevant. An arm
        // names a model, not a provider, so this agent would try to call
        // claude-opus-4-8 through its OPENAI credential.
        stubAgentOn(ProviderName.OPENAI);
        when(providerModelCatalogService.findModel(ProviderName.OPENAI, "claude-opus-4-8"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> validate("{\"model\":\"claude-opus-4-8\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("isn't in OPENAI's model catalog");
    }

    @Test
    void rejectsWhenTheTenantHasNoActiveCredentialForTheAgentsProvider() {
        stubAgentOn(ProviderName.GROK);
        when(credentialResolver.resolveActiveKey(ProviderName.GROK))
            .thenThrow(new ProviderNotActiveException(ProviderName.GROK));

        assertThatThrownBy(() -> validate("{\"model\":\"grok-4\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("no active GROK credential");

        // Fails before the catalog is consulted - no key, nothing to check.
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    // --- configs the runtime would silently read as "nothing" ---

    @Test
    void rejectsAConfigWithNoModelField() {
        stubAgentOn(ProviderName.OPENAI);

        assertThatThrownBy(() -> validate("{\"provider\":\"OPENAI\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("non-empty \"model\" string");
    }

    @Test
    void rejectsAConfigWhoseModelIsBlank() {
        stubAgentOn(ProviderName.OPENAI);

        assertThatThrownBy(() -> validate("{\"model\":\"   \"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("non-empty \"model\" string");
    }

    @Test
    void rejectsMalformedJsonRatherThanBlowingUp() {
        // PolicyArmAdminService normally catches this first; asserted here too
        // because AgentChatService#modelFromArmConfig swallows parse failures
        // and returns null by design, and a null model must never mean "fine".
        stubAgentOn(ProviderName.OPENAI);

        assertThatThrownBy(() -> validate("{\"model\": "))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("non-empty \"model\" string");
    }

    // --- arms that could never be selected ---

    @Test
    void rejectsWhenTheAgentItselfIsPointedAtThePlatformPool() {
        // buildChatClientFor routes a PLATFORM_HOSTED agent to the free-tier
        // client and never reads rlModelOverride at all.
        stubAgentOn(ProviderName.PLATFORM_HOSTED);

        assertThatThrownBy(() -> validate("{\"model\":\"gpt-4o\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("platform-hosted pool");
    }

    @Test
    void rejectsForAFreeTenantWhoseTurnsNeverConsultTheBandit() {
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        stubAgentOn(ProviderName.OPENAI);

        assertThatThrownBy(() -> validate("{\"model\":\"gpt-4o\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("shared hosted pool");
    }

    @Test
    void rejectsForAPaidTenantOptedIntoTheSharedPool() {
        // AgentChatService guards selectArm with !usesPlatformLlm, which is
        // true for an opted-in PAID tenant just as it is for FREE - and for a
        // tenant inside a live complimentary grant.
        when(tenantLimits.usesPlatformPool("acme")).thenReturn(true);
        stubAgentOn(ProviderName.OPENAI);

        assertThatThrownBy(() -> validate("{\"model\":\"gpt-4o\"}"))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("shared hosted pool");
    }

    // --- cross-tenant isolation ---

    @Test
    void anotherTenantsAgentIdIsNotFoundAndNothingIsValidated() {
        // AgentService#get is tenant-scoped (schema-per-tenant) and runs the
        // ownership/sharing check, so another tenant's agent id is simply
        // absent - indistinguishable from a made-up id.
        when(agentService.get(AGENT_ID)).thenThrow(new AgentNotFoundException(AGENT_ID));

        assertThatThrownBy(() -> validate("{\"model\":\"gpt-4o\"}"))
            .isInstanceOf(AgentNotFoundException.class);

        verify(credentialResolver, never()).resolveActiveKey(any());
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }

    // --- dimensions this validator doesn't police ---

    @Test
    void nonModelRouteDimensionsAreOnlyAgentExistenceChecked() {
        // REASONING_DEPTH/GEN_PARAMS/TOOL_SUBSET/PROMPT_VARIANT are now all
        // consulted at request time (AgentChatService#chat, 2026-08-15 wiring)
        // and each has a defined arm_config_json shape (see
        // AgentChatService#genParamsFromArmConfig et al.) - but THIS validator
        // (arm-CREATION time) still only guards MODEL_ROUTE, the one dimension
        // where a bad arm reaches a real provider/model call. The other four
        // degrade safely at read time instead (a malformed/unparseable config
        // is dropped for that turn - see e.g. genParamsFromArmConfig returning
        // null - never thrown), so skipping write-time validation for them is
        // a deliberate scope choice, not an oversight.
        stubAgentOn(ProviderName.OPENAI);

        assertThatCode(() -> validator.validateArm(
            AGENT_ID, PolicyDimension.GEN_PARAMS, "SPICY", "{\"temperature\":1.2}"))
            .doesNotThrowAnyException();

        verify(agentService).get(AGENT_ID);
        verify(credentialResolver, never()).resolveActiveKey(any());
        verify(providerModelCatalogService, never()).findModel(any(), any());
    }
}
