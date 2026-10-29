package com.agenticai.core.rl.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.rl.policy.RlPolicyProperties.GenParamPreset;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RlPolicyProperties}'s built-in posture: an empty model pool (per-tenant arm creation is
 * the real path), the PRECISE/BALANCED/CREATIVE GEN_PARAMS seed, the FAST/CAREFUL/RIGOROUS
 * REASONING_DEPTH seed, a 0.100 exploration floor, textbook UCB1 - plus the clamps that keep a
 * misconfigured value inside a legal range.
 */
class RlPolicyPropertiesTest {

    @Test
    void theDefaultsSeedTheDocumentedPosture() {
        RlPolicyProperties p = new RlPolicyProperties();

        assertTrue(
                p.getModelRouteArms().isEmpty(),
                "no platform-wide model pool: arms are created per tenant instead");
        assertEquals(new BigDecimal("0.100"), p.getDefaultExplorationRate());
        assertEquals(List.of("FAST", "CAREFUL", "RIGOROUS"), p.getReasoningDepthArms());
        assertEquals(2.0, p.getUcb1ExplorationConstant());
        assertTrue(p.getGenParamPresets().isEmpty(), "empty means 'use the built-in set'");
    }

    @Test
    void theBuiltInGenParamSeedIsPreciseBalancedCreative() {
        Map<String, GenParamPreset> presets = new RlPolicyProperties().genParamPresets();

        assertEquals(List.of("PRECISE", "BALANCED", "CREATIVE"), List.copyOf(presets.keySet()));
        assertEquals(0.2, presets.get("PRECISE").getTemperature());
        assertEquals(0.8, presets.get("PRECISE").getTopP());
        assertEquals(0.7, presets.get("BALANCED").getTemperature());
        assertEquals(0.9, presets.get("BALANCED").getTopP());
        assertEquals(1.0, presets.get("CREATIVE").getTemperature());
        assertEquals(0.95, presets.get("CREATIVE").getTopP());
    }

    @Test
    void aConfiguredPresetSetReplacesTheBuiltInOne() {
        RlPolicyProperties p = new RlPolicyProperties();
        Map<String, GenParamPreset> custom = new LinkedHashMap<>();
        custom.put("COLD", new GenParamPreset(0.0, 0.5));
        p.setGenParamPresets(custom);

        assertEquals(List.of("COLD"), List.copyOf(p.genParamPresets().keySet()));
        assertEquals(0.0, p.genParamPresets().get("COLD").getTemperature());
        assertEquals(0.5, p.genParamPresets().get("COLD").getTopP());
    }

    @Test
    void theExplorationRateIsClampedToAProbability() {
        RlPolicyProperties p = new RlPolicyProperties();

        p.setDefaultExplorationRate(new BigDecimal("0.25"));
        assertEquals(new BigDecimal("0.25"), p.getDefaultExplorationRate());
        p.setDefaultExplorationRate(new BigDecimal("7"));
        assertEquals(BigDecimal.ONE, p.getDefaultExplorationRate());
        p.setDefaultExplorationRate(new BigDecimal("-0.5"));
        assertEquals(BigDecimal.ZERO, p.getDefaultExplorationRate());
        p.setDefaultExplorationRate(null);
        assertEquals(new BigDecimal("0.100"), p.getDefaultExplorationRate());
    }

    @Test
    void theUcb1ConstantCannotGoNegative() {
        RlPolicyProperties p = new RlPolicyProperties();

        p.setUcb1ExplorationConstant(3.5);
        assertEquals(3.5, p.getUcb1ExplorationConstant());
        p.setUcb1ExplorationConstant(-1.0);
        assertEquals(0.0, p.getUcb1ExplorationConstant());
    }

    @Test
    void nullListsBecomeEmptyListsAndThePresetBeanIsMutable() {
        RlPolicyProperties p = new RlPolicyProperties();
        p.setReasoningDepthArms(null);
        p.setGenParamPresets(null);
        p.setModelRouteArms(List.of("gpt-x", "claude-y"));

        assertTrue(p.getReasoningDepthArms().isEmpty());
        assertTrue(p.getGenParamPresets().isEmpty());
        assertEquals(List.of("gpt-x", "claude-y"), p.getModelRouteArms());

        GenParamPreset preset = new GenParamPreset();
        preset.setTemperature(1.2);
        preset.setTopP(0.4);
        assertEquals(1.2, preset.getTemperature());
        assertEquals(0.4, preset.getTopP());
    }
}
