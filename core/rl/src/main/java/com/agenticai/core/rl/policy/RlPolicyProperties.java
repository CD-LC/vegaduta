package com.agenticai.core.rl.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code rl.policy.*} - seeding config for {@link PolicyStore}'s default arms.
 *
 * <p>The MODEL_ROUTE arm set (a small operator-curated model pool), the REASONING_DEPTH
 * and GEN_PARAMS arm sets, the starting exploration rate and UCB1's exploration constant
 * are configurable here; TOOL_SUBSET seeds a single ALL arm (see {@link PolicyStore}).
 * PROMPT_VARIANT arms are NOT seeded here -
 * they reference concrete prompt-version ids the orchestrator supplies later,
 * so a fresh PROMPT_VARIANT policy starts with only a DEFAULT arm.
 */
@ConfigurationProperties(prefix = "rl.policy")
public class RlPolicyProperties {

    /**
     * The candidate model keys a MODEL_ROUTE policy explores between. Each
     * becomes a PolicyArm with arm_key = the model string and
     * arm_config_json = {"model": "..."}. Empty by default so no MODEL_ROUTE
     * arms are seeded until an operator configures a real pool.
     *
     * <p><b>Keep it empty.</b> This is a BOOT-TIME seed and it is deliberately
     * not the primary way arms are added - anything set here is applied
     * platform-wide, to every tenant's every agent, with no knowledge of which
     * providers that tenant actually holds credentials for. A model route is a
     * per-tenant decision about live traffic, so the real path is {@code POST
     * /api/rl/agents/{agentId}/policies/MODEL_ROUTE/arms}, which validates the
     * model against that agent's own provider before persisting it (see {@link
     * PolicyArmAdminService} and {@code RlArmConfigValidator}). Setting a
     * default here would bypass that check entirely - which is exactly why the
     * empty default was left alone when arm management was built.
     */
    private List<String> modelRouteArms = new ArrayList<>();

    /**
     * Exploration rate (epsilon floor, 0..1) a new policy starts with. Existing policies keep the rate stored on
     * their row; an admin changes one per agent and dimension.
     */
    private BigDecimal defaultExplorationRate = new BigDecimal("0.100");

    /**
     * Arms seeded for a new REASONING_DEPTH policy (arm key = reasoning depth name). Must be depth names the
     * agent runtime knows (FAST, CAREFUL, RIGOROUS, PLANNED). Used only when a policy is created.
     */
    private List<String> reasoningDepthArms = new ArrayList<>(List.of("FAST", "CAREFUL", "RIGOROUS"));

    /** One GEN_PARAMS arm: the sampling settings a turn runs with when the bandit picks it. */
    public static class GenParamPreset {
        private double temperature;
        private double topP;

        public GenParamPreset() {
        }

        public GenParamPreset(double temperature, double topP) {
            this.temperature = temperature;
            this.topP = topP;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public double getTopP() {
            return topP;
        }

        public void setTopP(double topP) {
            this.topP = topP;
        }
    }

    /**
     * Arms seeded for a new GEN_PARAMS policy, by arm key. Empty means the built-in set (see {@link
     * #genParamPresets()}); a configured set REPLACES it entirely. Used only when a policy is created.
     */
    private Map<String, GenParamPreset> genParamPresets = new LinkedHashMap<>();

    /**
     * UCB1's exploration constant c in {@code avg + sqrt(c * ln(totalPulls) / armPulls)}. 2.0 is the textbook
     * UCB1; a smaller value exploits earlier, a larger one explores longer.
     */
    private double ucb1ExplorationConstant = 2.0;

    /** The GEN_PARAMS arms to seed: the configured ones, else PRECISE / BALANCED / CREATIVE. */
    public Map<String, GenParamPreset> genParamPresets() {
        if (!genParamPresets.isEmpty()) {
            return genParamPresets;
        }
        Map<String, GenParamPreset> builtIn = new LinkedHashMap<>();
        builtIn.put("PRECISE", new GenParamPreset(0.2, 0.8));
        builtIn.put("BALANCED", new GenParamPreset(0.7, 0.9));
        builtIn.put("CREATIVE", new GenParamPreset(1.0, 0.95));
        return builtIn;
    }

    public BigDecimal getDefaultExplorationRate() {
        return defaultExplorationRate;
    }

    public void setDefaultExplorationRate(BigDecimal rate) {
        this.defaultExplorationRate =
                rate == null ? new BigDecimal("0.100") : rate.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    public List<String> getReasoningDepthArms() {
        return reasoningDepthArms;
    }

    public void setReasoningDepthArms(List<String> reasoningDepthArms) {
        this.reasoningDepthArms = reasoningDepthArms == null ? new ArrayList<>() : reasoningDepthArms;
    }

    public Map<String, GenParamPreset> getGenParamPresets() {
        return genParamPresets;
    }

    public void setGenParamPresets(Map<String, GenParamPreset> genParamPresets) {
        this.genParamPresets = genParamPresets == null ? new LinkedHashMap<>() : genParamPresets;
    }

    public double getUcb1ExplorationConstant() {
        return ucb1ExplorationConstant;
    }

    public void setUcb1ExplorationConstant(double constant) {
        this.ucb1ExplorationConstant = Math.max(0.0, constant);
    }

    public List<String> getModelRouteArms() {
        return modelRouteArms;
    }

    public void setModelRouteArms(List<String> modelRouteArms) {
        this.modelRouteArms = modelRouteArms;
    }
}
