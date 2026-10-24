package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import java.util.Map;

/**
 * What the runtime-limit endpoints return: the layer being edited, the values a turn would run under, the
 * layer each of them came from and the range each may be set to.
 *
 * @param overrides the layer being edited (the agent's own, or the tenant's defaults); only fields set there
 * @param effective the values a turn runs under after every layer is applied
 * @param bounds the least and most a tenant or an agent may set each setting to, keyed by setting name
 */
public record RuntimeLimitsView(
        RuntimeLimits overrides, Effective effective, Map<String, AgentRuntimeProperties.Range> bounds) {

    /** {@link AgentRuntimeSettings} as JSON: durations in seconds, plus the layer of each value. */
    public record Effective(
            int maxToolSteps,
            long turnTimeoutSeconds,
            long toolTimeoutSeconds,
            int toolOutputMaxChars,
            int retrievalTopK,
            double retrievalMinScore,
            int continuationPasses,
            Map<String, String> layers) {

        static Effective of(AgentRuntimeSettings settings) {
            return new Effective(
                    settings.maxToolSteps(),
                    settings.turnTimeout().toSeconds(),
                    settings.toolTimeout().toSeconds(),
                    settings.toolOutputMaxChars(),
                    settings.retrievalTopK(),
                    settings.retrievalMinScore(),
                    settings.continuationPasses(),
                    settings.layerNames());
        }
    }
}
