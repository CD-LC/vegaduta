package com.agenticai.core.platform.abuse;

/**
 * Phase P5 (Fraud & Anomaly) - the escalating response tier a computed risk
 * score maps to. Deliberately ordered least-to-most severe so a plain {@code
 * compareTo}/ordinal comparison expresses "at least this severe".
 *
 * <ul>
 *   <li>{@link #NORMAL} - below the watch threshold: no action, nothing
 *       recorded (the common case for the overwhelming majority of principals;
 *       recording a row here would just be the firehose the fingerprinted
 *       issue portal exists to avoid).</li>
 *   <li>{@link #WATCH} - record a fingerprinted {@code ABUSE_ANOMALY} issue +
 *       audit entry so an operator can see the principal trending, but take no
 *       enforcement action.</li>
 *   <li>{@link #THROTTLE} - additionally tighten this principal via {@link
 *       AbuseThrottleService} (reversible, TTL-bounded, audited).</li>
 *   <li>{@link #SUSPEND} - the strongest tier: suspend the tenant via {@code
 *       TenantEmergencyControlService} (reversible via emergency-resume,
 *       audited). Auto-suspend is gated behind an admin opt-in flag - by
 *       default this tier throttles + raises an ERROR issue for human action
 *       rather than auto-suspending a whole tenant off one principal's score.</li>
 * </ul>
 */
public enum AbuseActionTier {
    NORMAL,
    WATCH,
    THROTTLE,
    SUSPEND
}
