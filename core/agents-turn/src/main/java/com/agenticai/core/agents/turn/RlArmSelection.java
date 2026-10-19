package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantPlan;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The five in-core RL bandit selections of one chat turn (MODEL_ROUTE, TOOL_SUBSET, PROMPT_VARIANT,
 * GEN_PARAMS, REASONING_DEPTH) with their validation and drop rules, moved verbatim out of {@link
 * AgentChatService#chat}. Produces the per-turn {@link RlTurnPlan}. The tenant comes from the
 * explicit {@link TurnContext} (ADR-0022 stage 2).
 */
final class RlArmSelection {

    private final AgentChatService svc;

    RlArmSelection(AgentChatService svc) {
        this.svc = svc;
    }

    RlTurnPlan select(TurnContext context, ChatTurnPreflight.Turn turn, String turnRef) {
        ObjectProvider<RlPolicyPort> rlPolicyPort = svc.rlPolicyPort;
        ChatHotpath hotpath = svc.hotpath;
        ChatClientResolver clientResolver = svc.clientResolver;
        AgentService agentService = svc.agentService;
        String currentTenantId = context.tenantId();
        TenantPlan currentTenantPlan = turn.currentTenantPlan();
        UUID agentId = turn.agentId();
        Agent agent = turn.agent();

        // In-core RL MODEL_ROUTE bandit (core/rl): consulted only for a
        // Playground turn (turnRef present) on a BYOK tenant - a FREE/platform-
        // LLM tenant doesn't choose its own model, so there is nothing to route
        // between. Returns empty unless an admin enabled the MODEL_ROUTE policy
        // AND gave it arms, so this is a no-op for every turn until someone
        // opts in. Arms come from POST /api/rl/agents/{id}/policies/MODEL_ROUTE/arms
        // (rl.policy.model-route-arms is a boot-time seed that is empty by
        // default and stays that way - an auto-seeded model route would be a
        // routing decision nobody made).
        RlPolicyPort rlPolicy = rlPolicyPort.getIfAvailable();
        RlArmPrefetch rlArms =
                hotpath.rlArms(agent.getTuningMode() == TuningMode.SIMPLE, currentTenantPlan);
        Optional<RlPolicyPort.ArmSelection> rlModelArm = Optional.empty();
        if (turnRef != null
                && rlPolicy != null
                && !clientResolver.usesPlatformLlm(currentTenantId, currentTenantPlan)) {
            rlModelArm = rlArms.select(rlPolicy, agentId, PolicyDimension.MODEL_ROUTE);
        }
        String rlModelOverride =
                rlModelArm
                        .map(a -> ExperimentArmConfig.of(a.armConfigJson()).model())
                        .orElse(null);
        // Arms are validated against the agent's OWN provider when an admin
        // creates them (RlModelRouteArmValidator), but write-time validation
        // cannot hold forever: the agent's providerName can be changed
        // afterwards, and a vendor can retire a model out of the catalog. A
        // stale arm would otherwise reach the provider as an unknown model and
        // fail the turn outright - callWithProviderFallback only rescues that
        // when the agent happens to have a FALLBACK provider configured, and
        // then on the fallback's model, not the agent's own. So re-check here
        // and, if the arm no longer fits, degrade to the agent's normal model
        // (which is what an unexplored turn would have used anyway).
        //
        // The arm is dropped from the episode too, not just from routing: a
        // reward must never be attributed to a decision that was not actually
        // applied - crediting an arm for a turn it did not influence is how a
        // bandit learns something false.
        if (rlModelOverride != null
                && !agentService.providerServesModel(agent.getProviderName(), rlModelOverride)) {
            AgentChatService.log.warn(
                    "RL MODEL_ROUTE arm '{}' for agent {} names model '{}', which provider {} no"
                        + " longer serves - ignoring the arm for this turn and using the agent's"
                        + " own model. Remove or replace the arm via DELETE"
                        + " /api/rl/agents/{}/policies/MODEL_ROUTE/arms/{}.",
                    rlModelArm.map(RlPolicyPort.ArmSelection::armKey).orElse("?"),
                    agentId,
                    rlModelOverride,
                    agent.getProviderName(),
                    agentId,
                    rlModelArm.map(RlPolicyPort.ArmSelection::armId).orElse(null));
            rlModelOverride = null;
            rlModelArm = Optional.empty();
        }

        // The other 4 RL dimensions (2026-08-15 wiring) - same guard shape as
        // MODEL_ROUTE above (turnRef present, rlPolicy available; selectArm
        // itself already gates on FREE-plan/policy-enabled/arms-exist), same
        // "malformed or otherwise unusable arm is dropped for this turn and
        // for the episode" posture as the MODEL_ROUTE stale-model check above.
        // Collected below into rlSelectedArms for #beginRlEpisode.
        Optional<RlPolicyPort.ArmSelection> rlToolSubsetArm = Optional.empty();
        if (turnRef != null && rlPolicy != null) {
            rlToolSubsetArm = rlArms.select(rlPolicy, agentId, PolicyDimension.TOOL_SUBSET);
        }
        Set<String> rlToolSubsetNames =
                rlToolSubsetArm
                        .map(a -> ExperimentArmConfig.of(a.armConfigJson()).toolNames())
                        .orElse(null);
        if (rlToolSubsetArm.isPresent() && rlToolSubsetNames == null) {
            rlToolSubsetArm = Optional.empty();
        }

        Optional<RlPolicyPort.ArmSelection> rlPromptVariantArm = Optional.empty();
        if (turnRef != null && rlPolicy != null) {
            rlPromptVariantArm = rlArms.select(rlPolicy, agentId, PolicyDimension.PROMPT_VARIANT);
        }
        String rlPromptOverride =
                rlPromptVariantArm
                        .map(a -> ExperimentArmConfig.of(a.armConfigJson()).systemPrompt())
                        .orElse(null);
        if (rlPromptVariantArm.isPresent() && rlPromptOverride == null) {
            rlPromptVariantArm = Optional.empty();
        }

        // GEN_PARAMS/REASONING_DEPTH are gated on TuningMode.SIMPLE - the
        // agent's own existing "let the system decide" signal (already used
        // for temperature/topP/maxTokens auto-inference, see TuningMode's
        // javadoc). MANUAL means the tenant explicitly set these, so their
        // values always win and neither dimension is ever selected.
        boolean rlAutoTuned = agent.getTuningMode() == TuningMode.SIMPLE;

        Optional<RlPolicyPort.ArmSelection> rlGenParamsArm = Optional.empty();
        if (turnRef != null && rlPolicy != null && rlAutoTuned) {
            rlGenParamsArm = rlArms.select(rlPolicy, agentId, PolicyDimension.GEN_PARAMS);
        }
        AgentChatService.GenParams rlGenParams =
                rlGenParamsArm
                        .map(a -> ExperimentArmConfig.of(a.armConfigJson()).genParams())
                        .orElse(null);
        if (rlGenParamsArm.isPresent() && rlGenParams == null) {
            rlGenParamsArm = Optional.empty();
        }
        double effTemperature =
                rlGenParams != null ? rlGenParams.temperature() : agent.getTemperature();
        double effTopP = rlGenParams != null ? rlGenParams.topP() : agent.getTopP();

        Optional<RlPolicyPort.ArmSelection> rlReasoningDepthArm = Optional.empty();
        if (turnRef != null && rlPolicy != null && rlAutoTuned) {
            rlReasoningDepthArm = rlArms.select(rlPolicy, agentId, PolicyDimension.REASONING_DEPTH);
        }
        ReasoningDepth rlReasoningDepth =
                rlReasoningDepthArm
                        .map(a -> ExperimentArmConfig.of(a.armConfigJson()).reasoningDepth())
                        .orElse(null);
        if (rlReasoningDepthArm.isPresent() && rlReasoningDepth == null) {
            rlReasoningDepthArm = Optional.empty();
        }
        ReasoningDepth effReasoningDepth =
                rlReasoningDepth != null ? rlReasoningDepth : agent.getReasoningDepth();

        // Every arm actually applied this turn - what #beginRlEpisode records,
        // so a later human reward folds back into every one of them, not just
        // MODEL_ROUTE's (see EpisodePolicyArm's javadoc).
        Map<PolicyDimension, UUID> rlSelectedArms = new java.util.EnumMap<>(PolicyDimension.class);
        rlModelArm.ifPresent(a -> rlSelectedArms.put(PolicyDimension.MODEL_ROUTE, a.armId()));
        rlToolSubsetArm.ifPresent(a -> rlSelectedArms.put(PolicyDimension.TOOL_SUBSET, a.armId()));
        rlPromptVariantArm.ifPresent(
                a -> rlSelectedArms.put(PolicyDimension.PROMPT_VARIANT, a.armId()));
        rlGenParamsArm.ifPresent(a -> rlSelectedArms.put(PolicyDimension.GEN_PARAMS, a.armId()));
        rlReasoningDepthArm.ifPresent(
                a -> rlSelectedArms.put(PolicyDimension.REASONING_DEPTH, a.armId()));
        return new RlTurnPlan(
                rlModelOverride,
                rlToolSubsetArm,
                rlToolSubsetNames,
                rlPromptOverride,
                effTemperature,
                effTopP,
                effReasoningDepth,
                rlSelectedArms);
    }
}
