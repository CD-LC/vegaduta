package com.agenticai.core.rl.reward;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code rl.reward.*} - how {@link RewardService} blends the per-source
 * {@code rl_reward_record} components into one verifiable composite reward.
 *
 * <p>The composite is a WEIGHTED MEAN of the non-SAFETY components (each
 * record carries its own stored weight; these values are only the DEFAULT
 * weight a caller uses when it doesn't specify one), then CLIPPED to
 * [{@link #clipMin}, {@link #clipMax}], then subjected to a SAFETY VETO: if
 * any SAFETY-source component reports a violation, the result is capped at
 * {@link #safetyVetoCap}. Clipping + veto + per-source verifiable components
 * is the industry reward-hacking mitigation posture.
 */
@ConfigurationProperties(prefix = "rl.reward")
public class RlRewardProperties {

    /** Lower clip bound for the composite reward fed to the bandit. */
    private double clipMin = 0.0;

    /** Upper clip bound for the composite reward fed to the bandit. */
    private double clipMax = 1.0;

    /**
     * The hard ceiling a SAFETY violation imposes on the final reward. No
     * amount of positive HUMAN/JUDGE/TASK_SUCCESS reward can buy back an
     * unsafe turn past this value.
     */
    private double safetyVetoCap = 0.1;

    /** Default weight for a HUMAN reward component. */
    private double humanWeight = 1.0;
    /** Default weight for a JUDGE (LLM-as-judge) reward component. */
    private double judgeWeight = 0.6;
    /** Default weight for a TASK_SUCCESS reward component. */
    private double taskSuccessWeight = 1.0;
    /** Default weight for a COST-efficiency reward component. */
    private double costWeight = 0.2;
    /** Default weight for a LATENCY-efficiency reward component. */
    private double latencyWeight = 0.2;

    public double getClipMin() {
        return clipMin;
    }

    public void setClipMin(double clipMin) {
        this.clipMin = clipMin;
    }

    public double getClipMax() {
        return clipMax;
    }

    public void setClipMax(double clipMax) {
        this.clipMax = clipMax;
    }

    public double getSafetyVetoCap() {
        return safetyVetoCap;
    }

    public void setSafetyVetoCap(double safetyVetoCap) {
        this.safetyVetoCap = safetyVetoCap;
    }

    public double getHumanWeight() {
        return humanWeight;
    }

    public void setHumanWeight(double humanWeight) {
        this.humanWeight = humanWeight;
    }

    public double getJudgeWeight() {
        return judgeWeight;
    }

    public void setJudgeWeight(double judgeWeight) {
        this.judgeWeight = judgeWeight;
    }

    public double getTaskSuccessWeight() {
        return taskSuccessWeight;
    }

    public void setTaskSuccessWeight(double taskSuccessWeight) {
        this.taskSuccessWeight = taskSuccessWeight;
    }

    public double getCostWeight() {
        return costWeight;
    }

    public void setCostWeight(double costWeight) {
        this.costWeight = costWeight;
    }

    public double getLatencyWeight() {
        return latencyWeight;
    }

    public void setLatencyWeight(double latencyWeight) {
        this.latencyWeight = latencyWeight;
    }
}
