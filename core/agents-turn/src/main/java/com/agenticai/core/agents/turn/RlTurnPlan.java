package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.tools.ToolSearchToolNames;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.domain.PolicyDimension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * The RL arms applied to one chat turn (see {@link RlArmSelection}) and the effective generation
 * settings they resolve to. Mutable on purpose: {@link #filterToolSubset} can still drop the
 * TOOL_SUBSET arm once the agent's real tool list is known, and {@link #rlSelectedArms} is the very
 * map {@code RlEpisodeRecorder#beginRlEpisode} records.
 */
final class RlTurnPlan {

    final String rlModelOverride;
    Optional<RlPolicyPort.ArmSelection> rlToolSubsetArm;
    final Set<String> rlToolSubsetNames;
    final String rlPromptOverride;
    final double effTemperature;
    final double effTopP;
    final ReasoningDepth effReasoningDepth;
    final Map<PolicyDimension, UUID> rlSelectedArms;

    RlTurnPlan(
            String rlModelOverride,
            Optional<RlPolicyPort.ArmSelection> rlToolSubsetArm,
            Set<String> rlToolSubsetNames,
            String rlPromptOverride,
            double effTemperature,
            double effTopP,
            ReasoningDepth effReasoningDepth,
            Map<PolicyDimension, UUID> rlSelectedArms) {
        this.rlModelOverride = rlModelOverride;
        this.rlToolSubsetArm = rlToolSubsetArm;
        this.rlToolSubsetNames = rlToolSubsetNames;
        this.rlPromptOverride = rlPromptOverride;
        this.effTemperature = effTemperature;
        this.effTopP = effTopP;
        this.effReasoningDepth = effReasoningDepth;
        this.rlSelectedArms = rlSelectedArms;
    }

    /**
     * Forgets the TOOL_SUBSET arm for this turn: the person narrowed the run's tools themselves, so the bandit
     * neither filters them further nor is credited with a choice that did not influence the turn.
     */
    void dropToolSubsetArm() {
        if (rlToolSubsetNames != null) {
            rlToolSubsetArm = Optional.empty();
            rlSelectedArms.remove(PolicyDimension.TOOL_SUBSET);
        }
    }

    /**
     * Applies the TOOL_SUBSET arm to the agent's own resolved tools, mutating {@code tools} in
     * place (never reassigning it - the dispatch lambda captures the list). Moved verbatim from the
     * tool-resolution step of {@link AgentChatService#chat}.
     */
    void filterToolSubset(List<ToolCallback> tools) {
        // RL TOOL_SUBSET (2026-08-15 wiring): filters the agent's OWN resolved
        // tools only, before extraTools (QuickTaskService's ad-hoc delegation
        // tools below) are added - those are essential to that specific call,
        // not part of the agent's normal toolset a bandit arm is choosing a
        // subset of.
        if (rlToolSubsetNames != null) {
            List<ToolCallback> filtered =
                    tools.stream()
                            .filter(t -> rlToolSubsetNames.contains(t.getToolDefinition().name()))
                            .toList();
            if (filtered.isEmpty()) {
                // Arm names tools this agent no longer has - same defensive
                // fallback as a stale MODEL_ROUTE arm above: ignore it for
                // this turn AND drop it from the episode (crediting an arm
                // that never actually influenced the turn teaches the bandit
                // something false).
                rlToolSubsetArm = Optional.empty();
                rlSelectedArms.remove(PolicyDimension.TOOL_SUBSET);
            } else {
                // Mutate the existing list in place, don't reassign `tools`
                // itself - dispatchTurn below is a lambda that captures it,
                // and a reassignment (even conditional) would make it not
                // effectively final.
                // search_tools and call_tool ride along: with on-demand tool loading they are the
                // only way to
                // reach the deferred tools, so a bandit subset must not silently remove them.
                tools.removeIf(
                        t ->
                                !filtered.contains(t)
                                        && !ToolSearchToolNames.SEARCH_TOOLS.equals(
                                                t.getToolDefinition().name())
                                        && !ToolSearchToolNames.CALL_TOOL.equals(
                                                t.getToolDefinition().name()));
            }
        }
    }
}
