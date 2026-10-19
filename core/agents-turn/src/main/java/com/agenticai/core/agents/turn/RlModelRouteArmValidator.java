package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.api.RlArmConfigValidator;
import com.agenticai.core.rl.api.RlArmRejectedException;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The write-side gate on RL bandit arms, living in the module that actually
 * consumes them ({@link AgentChatService}) so the two cannot drift apart.
 *
 * <p><b>What the read side does with a {@code MODEL_ROUTE} arm.</b> Exactly
 * two things, both in {@link AgentChatService}:
 * <ol>
 *   <li>{@link AgentChatService#modelFromArmConfig} reads a single
 *       {@code "model"} string out of {@code armConfigJson} - nothing else in
 *       the config is read by anything, ever;</li>
 *   <li>{@code buildChatClientFor} assigns that string to
 *       {@code effectiveModel} while leaving {@code effectiveProvider} as the
 *       AGENT's own {@code providerName} - an arm names a MODEL, not a
 *       provider.</li>
 * </ol>
 * So the only genuinely safe arm is one naming a model that the agent's own
 * provider serves and that this tenant's own credential can call. Everything
 * below follows from those two facts.
 *
 * <p><b>Reused machinery, not a parallel validator.</b> Provider reachability
 * is {@link ProviderCredentialResolver#resolveActiveKey} - the same call
 * {@code buildChatClientFor}'s text-provider branch makes to decide whether a
 * provider is usable this turn, so "the validator said yes" and "chat can
 * actually reach it" are the same question. Model legitimacy is {@link
 * ProviderModelCatalogService#findModel}, the same lookup {@code
 * AgentService#updateModel}/{@code setFallbackProvider} use. Plan/pool routing
 * is {@link TenantLimits#plan}/{@link TenantLimits#usePlatformLlm}, the same
 * pair {@code AgentChatService#usesPlatformLlm} branches on. Agent
 * existence/reachability is {@link AgentService#get}, which runs the normal
 * ownership/sharing check and throws the module's own not-found (404) - so an
 * agent id belonging to someone else is indistinguishable from one that does
 * not exist.
 *
 * <p><b>Deliberately stricter than {@code updateModel} on one point.</b> For a
 * paid BYOK tenant, {@code updateModel} treats the model catalog as a
 * suggestion list and accepts any non-blank model ("activate any provider/LLM,
 * no restrictions"). Here the catalog is a hard allowlist. The difference is
 * that {@code updateModel} is a human explicitly choosing their own agent's
 * model and seeing the failure on their very next chat turn, whereas a bandit
 * arm is chosen PROBABILISTICALLY - a bad arm produces intermittent failures
 * on an unpredictable fraction of turns, which is the hardest possible thing
 * to diagnose. See this class's known-limitation note on OLLAMA below.
 */
@Component
public class RlModelRouteArmValidator implements RlArmConfigValidator {

    private final AgentService agentService;
    private final ProviderCredentialResolver credentialResolver;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;

    public RlModelRouteArmValidator(
        AgentService agentService,
        ProviderCredentialResolver credentialResolver,
        ProviderModelCatalogService providerModelCatalogService,
        TenantLimits tenantLimits
    ) {
        this.agentService = agentService;
        this.credentialResolver = credentialResolver;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
    }

    @Override
    public void validateArm(UUID agentId, PolicyDimension dimension, String armKey, String armConfigJson) {
        // Applies to EVERY dimension: the agent must exist and be reachable by
        // this caller. Throws AgentNotFoundException / ResourceAccessDeniedException,
        // both already mapped to 404 - which is also what makes cross-tenant
        // arm creation a 404 rather than a leak.
        AgentResponse agent = agentService.get(agentId);

        if (dimension != PolicyDimension.MODEL_ROUTE) {
            // REASONING_DEPTH / GEN_PARAMS / TOOL_SUBSET / PROMPT_VARIANT have
            // seeded arms and admin toggles but NO runtime consumer today
            // (AgentChatService reads MODEL_ROUTE only). Their configs are
            // therefore inert rather than dangerous, and inventing a schema for
            // them here would just be guessing at a contract that does not
            // exist yet. Shape-checked as a JSON object by the caller; that is
            // all this can honestly assert.
            return;
        }

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantLimits.usesPlatformPool(tenantId)) {
            // AgentChatService skips MODEL_ROUTE selection entirely for a
            // platform-pool tenant (!usesPlatformLlm guards the selectArm
            // call), and DefaultRlPolicyPort short-circuits FREE before that.
            // An arm here could never be pulled, so say so instead of storing
            // a decoration.
            throw new RlArmRejectedException(
                "This tenant's turns run on the platform's shared hosted pool, which picks its own model - "
                    + "MODEL_ROUTE arms are never consulted for it. Switch the tenant to its own provider keys "
                    + "(BYOK) before adding model-route arms."
            );
        }

        ProviderName provider = agent.providerName();
        if (provider == null || provider == ProviderName.PLATFORM_HOSTED) {
            // A PLATFORM_HOSTED agent is routed to FreeTierChatClientService by
            // buildChatClientFor regardless of plan, so rlModelOverride is
            // never even consulted for it.
            throw new RlArmRejectedException(
                "This agent is set to use the platform-hosted pool, which chooses its own model - a MODEL_ROUTE "
                    + "arm would never be applied. Point the agent at one of your own providers first."
            );
        }

        // The one field the runtime reads - parsed with the runtime's OWN reader.
        String model = ChatArmConfig.modelFromArmConfig(armConfigJson);
        if (model == null || model.isBlank()) {
            throw new RlArmRejectedException(
                "A MODEL_ROUTE arm's armConfigJson must be a JSON object carrying a non-empty \"model\" string - "
                    + "e.g. {\"model\":\"gpt-4o\"}. That single field is the only thing the chat path reads out of "
                    + "an arm; anything else is ignored."
            );
        }

        // Same call the chat path makes to decide a provider is usable this
        // turn. Throws ProviderNotActiveException / SarvamNotConfiguredException;
        // both are already 400s with their own clear copy, but they name the
        // provider without explaining WHY an RL arm needed it, so re-frame.
        try {
            credentialResolver.resolveActiveKey(provider);
        } catch (RuntimeException e) {
            throw new RlArmRejectedException(
                "This agent runs on " + provider + ", and a MODEL_ROUTE arm only swaps the MODEL - it keeps the "
                    + "agent's provider. There is no active " + provider + " credential for this tenant, so every "
                    + "turn that selected this arm would fail. Activate " + provider + " first."
            );
        }

        // Hard allowlist - see the class javadoc for why this is stricter than
        // AgentService#updateModel. Known limitation: OLLAMA's catalog is a
        // hand-curated seed of common tags, not a live read of the tenant's own
        // server, so a locally-pulled tag that isn't in that seed cannot be
        // used as an arm. Same for any model a vendor shipped since the last
        // SEED_MODELS edit. Widening the seed list is the fix, not loosening
        // this check.
        if (providerModelCatalogService.findModel(provider, model).isEmpty()) {
            throw new RlArmRejectedException(
                "\"" + model + "\" isn't in " + provider + "'s model catalog, so this agent's " + provider
                    + " credential probably can't serve it - and a bandit arm fails on an unpredictable fraction "
                    + "of turns, not visibly on the next one. Pick a model from that provider's list."
            );
        }
    }
}
