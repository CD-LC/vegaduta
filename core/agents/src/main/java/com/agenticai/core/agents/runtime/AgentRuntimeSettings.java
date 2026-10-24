package com.agenticai.core.agents.runtime;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The runtime limits one agent's turn runs under, after the platform, tenant, agent and request layers
 * have been applied by {@code AgentRuntimeSettingsResolver}. Immutable and complete: every field has a
 * value. The one object every path reads (stream, buffered, hosted, agent jobs, the turn after an
 * approval), so a limit set on an agent is the limit on all of them.
 *
 * @param maxToolSteps tool calls the turn may make; autonomy-tier scaling is already applied
 * @param turnTimeout wall-clock limit of a turn; for a FAST streamed turn it only applies when
 *     {@link #turnTimeoutLayer()} is above {@link RuntimeLayer#PLATFORM} (the platform adds no overall cap
 *     to a stream, only an idle timer)
 * @param toolTimeout wall-clock limit of one tool call; {@link Duration#ZERO} means no limit
 * @param toolOutputMaxChars characters of one tool result that reach the model
 * @param retrievalTopK knowledge-base chunks one search returns
 * @param retrievalMinScore least relevance a returned chunk needs; 0 returns every hit
 * @param continuationPasses extra model passes after a reply that stopped at an announced, untaken step
 * @param layers which layer set each value, keyed by {@link RuntimeSetting#apiName()}
 * @param graceCalls calls past the ceiling that are answered with the budget-exhausted result before the turn is
 *     stopped, as the tenant's or the agent's {@code agent.steps.graceCalls} setting gives it for this turn; negative
 *     = not resolved (the platform's {@code app.guardrails.step-limit.grace-calls} applies)
 */
public record AgentRuntimeSettings(
        int maxToolSteps,
        Duration turnTimeout,
        Duration toolTimeout,
        int toolOutputMaxChars,
        int retrievalTopK,
        double retrievalMinScore,
        int continuationPasses,
        Map<RuntimeSetting, RuntimeLayer> layers,
        int graceCalls) {

    /** The settings of a turn whose grace past the step ceiling is the platform's (not resolved per tenant or agent). */
    public AgentRuntimeSettings(
            int maxToolSteps,
            Duration turnTimeout,
            Duration toolTimeout,
            int toolOutputMaxChars,
            int retrievalTopK,
            double retrievalMinScore,
            int continuationPasses,
            Map<RuntimeSetting, RuntimeLayer> layers) {
        this(maxToolSteps, turnTimeout, toolTimeout, toolOutputMaxChars, retrievalTopK, retrievalMinScore,
                continuationPasses, layers, -1);
    }

    public AgentRuntimeSettings {
        EnumMap<RuntimeSetting, RuntimeLayer> copy = new EnumMap<>(RuntimeSetting.class);
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            copy.put(setting, layers == null ? RuntimeLayer.PLATFORM : layers.getOrDefault(setting, RuntimeLayer.PLATFORM));
        }
        layers = Map.copyOf(copy);
    }

    /** The layer that set {@code setting}. */
    public RuntimeLayer layerOf(RuntimeSetting setting) {
        return layers.get(setting);
    }

    /** The layer that set the turn timeout. */
    public RuntimeLayer turnTimeoutLayer() {
        return layers.get(RuntimeSetting.TURN_TIMEOUT_SECONDS);
    }

    /** True when a tenant, agent or request layer set the turn timeout (not just the platform default). */
    public boolean turnTimeoutOverridden() {
        return turnTimeoutLayer() != RuntimeLayer.PLATFORM;
    }

    /** True when this turn's grace past the step ceiling was resolved (a tenant or agent may have set it). */
    public boolean hasGraceCalls() {
        return graceCalls >= 0;
    }

    /** True when a per-call tool timeout is in force. */
    public boolean hasToolTimeout() {
        return toolTimeout != null && !toolTimeout.isZero() && !toolTimeout.isNegative();
    }

    /** The layers keyed by the API name of each setting, for the API view. */
    public Map<String, String> layerNames() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            out.put(setting.apiName(), layers.get(setting).name());
        }
        return out;
    }
}
