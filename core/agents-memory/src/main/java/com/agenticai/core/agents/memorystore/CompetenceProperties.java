package com.agenticai.core.agents.memorystore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.competence.*}: the SELF_MODEL competence counters (ADR-0028 section 3). Off by default; a tenant or
 * an agent can only switch it off further ({@code memory.competence.enabled}). The counters are fed from verified
 * outcomes only and read back as numbers rendered by a template, never by a model.
 */
@ConfigurationProperties(prefix = "app.memory.competence")
public class CompetenceProperties {

    /** Platform switch: off means nothing is counted and no competence summary is recalled. */
    private boolean enabled = false;

    /** Most distinct task classes counted per agent; further classes share one overflow row. */
    private int maxTaskClassesPerAgent = 50;

    /** Fewest verified outcomes (successes plus failures) before a class is mentioned in the summary. */
    private int minSample = 8;

    /** A class is "good" when the Wilson lower bound of its success rate is at least this. */
    private double goodLowerBound = 0.7;

    /** A class is "unreliable" when its observed success rate is below this. */
    private double unreliableRate = 0.5;

    /** Most classes named per side (good / unreliable) in one summary. */
    private int maxClassesPerSide = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTaskClassesPerAgent() {
        return maxTaskClassesPerAgent;
    }

    public void setMaxTaskClassesPerAgent(int v) {
        this.maxTaskClassesPerAgent = Math.max(1, Math.min(v, 500));
    }

    public int getMinSample() {
        return minSample;
    }

    public void setMinSample(int v) {
        this.minSample = Math.max(1, v);
    }

    public double getGoodLowerBound() {
        return goodLowerBound;
    }

    public void setGoodLowerBound(double v) {
        this.goodLowerBound = Math.max(0.0, Math.min(v, 1.0));
    }

    public double getUnreliableRate() {
        return unreliableRate;
    }

    public void setUnreliableRate(double v) {
        this.unreliableRate = Math.max(0.0, Math.min(v, 1.0));
    }

    public int getMaxClassesPerSide() {
        return maxClassesPerSide;
    }

    public void setMaxClassesPerSide(int v) {
        this.maxClassesPerSide = Math.max(1, Math.min(v, 10));
    }
}
