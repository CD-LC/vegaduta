package com.agenticai.core.platform.abuse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase P5 (Fraud & Anomaly) - the computed risk assessment for one
 * (tenant, user) principal at a point in time. This is a pure read model: the
 * admin read endpoints return it, and the sweep uses its {@link #tier} to
 * decide whether to act. Computing it never has a side effect (see {@code
 * AbuseSignalService#score}); acting on it is a separate, explicit step.
 *
 * @param tenantId   the tenant the principal belongs to
 * @param tenantName human-readable tenant name for the admin UI
 * @param userId     the principal (audit {@code actorId}); {@code null}/"*"
 *                   for a tenant-wide assessment when no single user is in scope
 * @param score      aggregate risk, 0 (benign) .. 100 (maximally anomalous)
 * @param tier       the action tier {@code score} maps to under current thresholds
 * @param signals    per-signal breakdown (including unavailable ones, for transparency)
 * @param computedAt when this assessment was computed
 */
public record AbuseRiskScore(
    UUID tenantId,
    String tenantName,
    String userId,
    double score,
    AbuseActionTier tier,
    List<AbuseSignal> signals,
    Instant computedAt
) {}
