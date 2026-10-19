package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.Capability;
import com.agenticai.core.providers.capability.CapabilityPolicyValidator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The DECISION capability's {@link CapabilityPolicyValidator} - owns the
 * schema of {@code capability_policy} rows where {@code capability='decision'}
 * so the generic {@code PUT /api/capabilities/decision/policy} endpoint can
 * enforce it without knowing what a decision model is.
 *
 * <p>Keys: {@code costRouting} (Boolean - may a decision model answer the
 * per-turn complexity check), {@code agentRouting} (Boolean - may it pick
 * agents in Quick Task), {@code simpleThreshold} (number - the minimum
 * probability required before a "probably complex" turn routes to the
 * economy model). The same bounds {@code JevPolicyRequest} enforces on the
 * {@code /api/jev} shim: 0.5 is the meaningful-agreement floor (below it the
 * model's "yes" is weaker than a coin flip) and 0.99 is "only downgrade when
 * almost certain" - a tenant setting it near 0 would silently degrade
 * quality, which is exactly what the bound exists to prevent.
 */
@Component
public class DecisionPolicyValidator implements CapabilityPolicyValidator {

    private static final Set<String> KNOWN_KEYS = Set.of("costRouting", "agentRouting", "simpleThreshold");
    private static final double THRESHOLD_MIN = 0.5;
    private static final double THRESHOLD_MAX = 0.99;

    @Override
    public String capability() {
        return Capability.DECISION.key();
    }

    @Override
    public void validateOverrides(Map<String, Object> overrides) {
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "unknown decision policy key '" + key + "' - expected one of " + KNOWN_KEYS);
            }
            if (value == null) {
                continue; // null = reset to inherit
            }
            switch (key) {
                case "costRouting", "agentRouting" -> {
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException("'" + key + "' must be a boolean or null");
                    }
                }
                case "simpleThreshold" -> {
                    if (!(value instanceof Number n)) {
                        throw new IllegalArgumentException("'simpleThreshold' must be a number or null");
                    } else if (n.doubleValue() < THRESHOLD_MIN || n.doubleValue() > THRESHOLD_MAX) {
                        throw new IllegalArgumentException(
                            "'simpleThreshold' must be between " + THRESHOLD_MIN + " and " + THRESHOLD_MAX
                                + " - below " + THRESHOLD_MIN + " the model's 'yes' is weaker than a coin flip");
                    }
                }
                default -> throw new IllegalArgumentException("unknown decision policy key '" + key + "'");
            }
        }
    }
}
