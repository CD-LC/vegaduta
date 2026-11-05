package com.agenticai.core.agents.memorystore.decay;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.decay.*}: the knobs of the memory salience function and the nightly
 * consolidation planner (phase 1 design 03, sections 3 and 4). Defaults are the document's. Nothing
 * here switches consolidation on; that is {@code app.memory.consolidation.enabled}, owned by the
 * sweep that arrives with the persistence work package.
 *
 * <p>Setters clamp rather than throw so a bad override degrades to the nearest sane value instead
 * of failing startup. The safety limits can be tightened to zero but never made unlimited.
 */
@ConfigurationProperties(prefix = "app.memory.decay")
public class DecayProperties {

    /** Half-life H in days of the recency term for kinds without an override. */
    private double halfLifeDays = 45;

    /** Per-kind half-life overrides, keys compared case-insensitively. */
    private Map<String, Double> halfLifeDaysByKind = defaultKindHalfLives();

    /** Uses at which the frequency term saturates at 1. */
    private double frequencySaturation = 20;

    private final Weights weights = new Weights();

    /** Salience below this makes a fact a forget candidate. */
    private double forgetThreshold = 0.12;

    /** Salience in [forget, this) folds a fact into a summary. */
    private double summariseThreshold = 0.30;

    /** Nothing younger than this many days (since creation) is touched, whatever its score. */
    private int minAgeDays = 14;

    /** Multiplier on salience for QUARANTINED rows. */
    private double quarantineFactor = 0.5;

    /** Never remove more than this fraction of a subject's rows in one night (circuit breaker). */
    private double maxFractionForgotten = 0.30;

    /** Most rows forgotten outright per subject per night. */
    private int maxForgetPerSubjectPerNight = 25;

    /** Most summaries created per subject per night. */
    private int maxSummariesPerSubject = 3;

    /** Most source rows folded into one summary. */
    private int summaryBatchSize = 12;

    /**
     * Highest lineage generation a derived row may have; inputs that would exceed it are skipped.
     */
    private int maxGeneration = 3;

    /** Unapproved QUARANTINED rows older than this are always forget candidates. */
    private int quarantineTtlDays = 30;

    /** Person-authored rows are never forgotten, merged away or summarised automatically. */
    private boolean protectPersonAuthored = true;

    /** Summarise TRUSTED and QUARANTINED sources in one batch (the output is then QUARANTINED). */
    private boolean mixTrustBatches = false;

    /** Days after consolidation was enabled during which the plan is propose-only. */
    private int dryRunDays = 7;

    /** Model-spend ceiling per tenant per night, USD. */
    private double maxUsdPerTenantPerNight = 0.50;

    /** Model-spend ceiling for one whole run across all tenants, USD. */
    private double maxUsdPerRun = 5.00;

    private static Map<String, Double> defaultKindHalfLives() {
        Map<String, Double> m = new HashMap<>();
        m.put("preference", 120d);
        m.put("episodic", 21d);
        m.put("task-context", 7d);
        return m;
    }

    private static double unit(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static double positive(double v, double fallback) {
        return Double.isNaN(v) || v <= 0 ? fallback : v;
    }

    /** Half-life in days for a fact kind. */
    public double halfLifeFor(String kind) {
        if (kind != null) {
            Double h = halfLifeDaysByKind.get(kind.toLowerCase(Locale.ROOT));
            if (h != null) {
                return h;
            }
        }
        return halfLifeDays;
    }

    public double getHalfLifeDays() {
        return halfLifeDays;
    }

    public void setHalfLifeDays(double v) {
        this.halfLifeDays = positive(v, 45);
    }

    public Map<String, Double> getHalfLifeDaysByKind() {
        return halfLifeDaysByKind;
    }

    public void setHalfLifeDaysByKind(Map<String, Double> m) {
        Map<String, Double> copy = new HashMap<>();
        if (m != null) {
            m.forEach(
                    (k, v) -> {
                        if (k != null && v != null && v > 0) {
                            copy.put(k.toLowerCase(Locale.ROOT), v);
                        }
                    });
        }
        this.halfLifeDaysByKind = copy;
    }

    public double getFrequencySaturation() {
        return frequencySaturation;
    }

    public void setFrequencySaturation(double v) {
        this.frequencySaturation = positive(v, 20);
    }

    public Weights getWeights() {
        return weights;
    }

    public double getForgetThreshold() {
        return forgetThreshold;
    }

    public void setForgetThreshold(double v) {
        this.forgetThreshold = unit(v);
    }

    public double getSummariseThreshold() {
        return summariseThreshold;
    }

    public void setSummariseThreshold(double v) {
        this.summariseThreshold = unit(v);
    }

    public int getMinAgeDays() {
        return minAgeDays;
    }

    public void setMinAgeDays(int v) {
        this.minAgeDays = Math.max(0, v);
    }

    public double getQuarantineFactor() {
        return quarantineFactor;
    }

    public void setQuarantineFactor(double v) {
        this.quarantineFactor = unit(v);
    }

    public double getMaxFractionForgotten() {
        return maxFractionForgotten;
    }

    public void setMaxFractionForgotten(double v) {
        this.maxFractionForgotten = unit(v);
    }

    public int getMaxForgetPerSubjectPerNight() {
        return maxForgetPerSubjectPerNight;
    }

    public void setMaxForgetPerSubjectPerNight(int v) {
        this.maxForgetPerSubjectPerNight = Math.max(0, v);
    }

    public int getMaxSummariesPerSubject() {
        return maxSummariesPerSubject;
    }

    public void setMaxSummariesPerSubject(int v) {
        this.maxSummariesPerSubject = Math.max(0, v);
    }

    public int getSummaryBatchSize() {
        return summaryBatchSize;
    }

    public void setSummaryBatchSize(int v) {
        this.summaryBatchSize = Math.max(2, v);
    }

    public int getMaxGeneration() {
        return maxGeneration;
    }

    public void setMaxGeneration(int v) {
        this.maxGeneration = Math.max(1, v);
    }

    public int getQuarantineTtlDays() {
        return quarantineTtlDays;
    }

    public void setQuarantineTtlDays(int v) {
        this.quarantineTtlDays = Math.max(1, v);
    }

    public boolean isProtectPersonAuthored() {
        return protectPersonAuthored;
    }

    public void setProtectPersonAuthored(boolean v) {
        this.protectPersonAuthored = v;
    }

    public boolean isMixTrustBatches() {
        return mixTrustBatches;
    }

    public void setMixTrustBatches(boolean v) {
        this.mixTrustBatches = v;
    }

    public int getDryRunDays() {
        return dryRunDays;
    }

    public void setDryRunDays(int v) {
        this.dryRunDays = Math.max(0, v);
    }

    public double getMaxUsdPerTenantPerNight() {
        return maxUsdPerTenantPerNight;
    }

    public void setMaxUsdPerTenantPerNight(double v) {
        this.maxUsdPerTenantPerNight = Double.isNaN(v) ? 0 : Math.max(0, v);
    }

    public double getMaxUsdPerRun() {
        return maxUsdPerRun;
    }

    public void setMaxUsdPerRun(double v) {
        this.maxUsdPerRun = Double.isNaN(v) ? 0 : Math.max(0, v);
    }

    /** {@code app.memory.decay.weights.*}: wI, wR, wF. Documented defaults sum to 1. */
    public static class Weights {
        private double importance = 0.45;
        private double recency = 0.35;
        private double frequency = 0.20;

        public double getImportance() {
            return importance;
        }

        public void setImportance(double v) {
            this.importance = unit(v);
        }

        public double getRecency() {
            return recency;
        }

        public void setRecency(double v) {
            this.recency = unit(v);
        }

        public double getFrequency() {
            return frequency;
        }

        public void setFrequency(double v) {
            this.frequency = unit(v);
        }
    }
}
