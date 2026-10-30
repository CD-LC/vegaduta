package com.agenticai.core.rl.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link RlRewardProperties} holds the composite-reward blending defaults: the [0,1] clip bounds,
 * the {@code 0.1} safety-veto ceiling (a violated SAFETY component can never be bought back by
 * positive signals), and the per-source default weights. The values are the contract - a change to
 * the veto cap is a safety posture change, not a tuning detail.
 */
class RlRewardPropertiesTest {

    @Test
    void theDefaultsMatchTheRewardHackingMitigationPosture() {
        RlRewardProperties p = new RlRewardProperties();

        assertEquals(0.0, p.getClipMin());
        assertEquals(1.0, p.getClipMax());
        assertEquals(0.1, p.getSafetyVetoCap());
        assertEquals(1.0, p.getHumanWeight());
        assertEquals(0.6, p.getJudgeWeight());
        assertEquals(1.0, p.getTaskSuccessWeight());
        assertEquals(0.2, p.getCostWeight());
        assertEquals(0.2, p.getLatencyWeight());
    }

    @Test
    void everyFieldIsConfigurable() {
        RlRewardProperties p = new RlRewardProperties();

        p.setClipMin(0.1);
        p.setClipMax(0.9);
        p.setSafetyVetoCap(0.05);
        p.setHumanWeight(2.0);
        p.setJudgeWeight(0.4);
        p.setTaskSuccessWeight(1.5);
        p.setCostWeight(0.1);
        p.setLatencyWeight(0.3);

        assertEquals(0.1, p.getClipMin());
        assertEquals(0.9, p.getClipMax());
        assertEquals(0.05, p.getSafetyVetoCap());
        assertEquals(2.0, p.getHumanWeight());
        assertEquals(0.4, p.getJudgeWeight());
        assertEquals(1.5, p.getTaskSuccessWeight());
        assertEquals(0.1, p.getCostWeight());
        assertEquals(0.3, p.getLatencyWeight());
    }
}
