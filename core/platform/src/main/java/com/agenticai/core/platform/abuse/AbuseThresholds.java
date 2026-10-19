package com.agenticai.core.platform.abuse;

/**
 * Phase P5 (Fraud & Anomaly) - an immutable snapshot of every tunable knob the
 * scorer and tier engine need, resolved from {@link
 * com.agenticai.core.tenancy.PlatformSettingsService} at the moment of use (see
 * {@link AbuseConfig}). Passed around as one value object so scoring is a pure
 * function of (signals, thresholds) - trivially unit-testable with no Spring or
 * DB (see {@code AbuseScoreCalculatorTest}), and so a mid-scan admin edit can't
 * change thresholds halfway through a single principal's assessment.
 *
 * <p>Windows are in minutes; weights are relative (the aggregate normalizes by
 * the sum of available weights, so they need not sum to 1). Tier thresholds are
 * on the 0..100 aggregate scale.
 */
public record AbuseThresholds(
    // signal windows
    long recentWindowMinutes,
    long baselineWindowMinutes,
    // spend spike
    double spendSpikeMultiplier,
    double spendSpikeMinUsd,
    double spendSpikeWeight,
    // token burn
    double tokenBurnPerMinThreshold,
    double tokenBurnWeight,
    // message / action flooding
    double messageFloodPerMinThreshold,
    double messageFloodWeight,
    // signup velocity
    double signupVelocityPerHourThreshold,
    double signupVelocityWeight,
    // tool-error abuse
    double toolErrorRatioThreshold,
    long toolErrorMinSample,
    double toolErrorWeight,
    // geo velocity (impossible travel) - weight only applies when a provider is wired
    double geoVelocityWeight,
    // tier boundaries (0..100)
    double watchThreshold,
    double throttleThreshold,
    double suspendThreshold,
    // action config
    long throttleTtlMinutes,
    boolean autoSuspendEnabled,
    boolean scanEnabled
) {}
