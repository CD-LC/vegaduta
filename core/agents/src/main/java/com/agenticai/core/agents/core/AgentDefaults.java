package com.agenticai.core.agents.core;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code app.agents.*} defaults a NEW agent is born with, read once at startup. Extracted from
 * {@link AgentService}'s constructor so the model resolver and the creation service share one
 * source for them instead of each taking four {@code @Value} parameters.
 *
 * <p>{@code guardrailIds} is the set every new agent is seeded with ({@code
 * app.agents.default-guardrails}, default {@code jailbreak}); an empty property restores the
 * born-unguarded behavior.
 */
@Component
public class AgentDefaults {

    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final double topP;
    private final Set<String> guardrailIds;

    public AgentDefaults(
            @Value("${app.agents.default-model}") String model,
            @Value("${app.agents.default-max-tokens:1024}") int maxTokens,
            @Value("${app.agents.default-temperature:0.7}") double temperature,
            @Value("${app.agents.default-top-p:1.0}") double topP,
            @Value("${app.agents.default-guardrails:jailbreak}") String guardrails) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
        Set<String> parsed = new HashSet<>();
        for (String id : guardrails.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        this.guardrailIds = Set.copyOf(parsed);
    }

    public String model() {
        return model;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public double temperature() {
        return temperature;
    }

    public double topP() {
        return topP;
    }

    public Set<String> guardrailIds() {
        return guardrailIds;
    }
}
