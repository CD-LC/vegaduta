package com.agenticai.core.platform.abuse;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase P5 (Fraud & Anomaly) - the payload handed to every {@link
 * AbuseAlertSink} when a principal crosses into an actionable tier. A plain
 * value object so a webhook sink can serialize it straight to JSON and an
 * email sink can template it, without either reaching back into the scoring
 * internals.
 */
public record AbuseAlert(
    UUID tenantId,
    String tenantName,
    String userId,
    double score,
    AbuseActionTier tier,
    boolean actionApplied,
    String summary,
    Instant at
) {}
