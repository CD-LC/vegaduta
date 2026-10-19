package com.agenticai.core.security;

import org.slf4j.Logger;

/**
 * The single, shared answer to "what should a Redis-backed governance control
 * do when Redis itself is down?" - and, deliberately, the single place to
 * change that answer later.
 *
 * <h2>The correlated-failure risk this makes explicit (audit, 2026-07-29)</h2>
 * <p>Every spend/abuse control in this platform that keeps its counters in
 * Redis independently chose to FAIL OPEN on a Redis error: log a warning,
 * pretend the limit wasn't hit, let the traffic through. Each choice is
 * individually defensible (an infra blip shouldn't hard-down every chat
 * turn), but they are all backed by the SAME single Redis instance, so the
 * failures are perfectly correlated: one Redis outage does not degrade one
 * control, it silently disables ALL of them AT ONCE - LLM gateway rate
 * limits, monthly BYOK budget caps, per-user daily dollar budgets, FREE-tier
 * usage caps, signup abuse limits, demo-sandbox per-IP caps - while traffic
 * (and provider spend) keeps flowing at full speed with nothing but scattered
 * per-class WARN lines to show for it. Known affected classes as of this
 * writing (each has its own fail-open catch block today):
 * <ul>
 *   <li>{@code core/chat} - {@code LlmGatewayRateLimiterService},
 *       {@code LlmGatewayBudgetService}</li>
 *   <li>{@code core/agents} - {@code PerUserBudgetService},
 *       {@code FreeTierUsageLimiterService}</li>
 *   <li>{@code core/tenancy} - {@code SignupRateLimiter},
 *       {@code demo.DemoSessionRateLimiter}</li>
 * </ul>
 *
 * <h2>What this class is (and is not)</h2>
 * <p>This is the cheap, high-value first step, not the redesign: a named,
 * documented constant plus one logging helper, living in {@code core/security}
 * because every module above already depends on this module (for
 * {@link TenantContext}). Migrating a limiter means replacing its private
 * {@code catch (Exception e) { log.warn(...); return allowed; }} decision
 * with a call to {@link #allowOnBackendFailure} - behavior today is
 * byte-for-byte identical (still fail open), but the decision becomes
 * greppable, uniformly logged, and flippable in ONE place. The eventual
 * fail-closed flip (most defensible for the pure spend controls - budgets and
 * FREE-tier caps - where "Redis down" currently means "unlimited free LLM
 * spend") is then a one-line change here plus a deliberate review of each
 * caller, instead of a scavenger hunt across six modules.
 *
 * <p>NOT done here on purpose: per-control policies, half-open probing,
 * local-fallback counters. Those belong to the real redesign; adding them
 * speculatively now would just be a second unowned mechanism.
 */
public final class GovernanceFailOpenPolicy {

    /**
     * When {@code true} (today's behavior, preserved exactly): a spend/abuse
     * control whose Redis backend errors lets the request through. When
     * flipped to {@code false}: {@link #allowOnBackendFailure} returns
     * {@code false} and callers must treat the limit as exceeded - i.e. a
     * Redis outage stops metered traffic instead of unmetering it. Flip only
     * with a review of every caller: fail-closed is right for spend controls,
     * but blanket fail-closed on e.g. the signup limiter turns a Redis blip
     * into a signup outage, which is why this starts as one shared constant
     * and not a silent per-class default.
     */
    public static final boolean SPEND_CONTROLS_FAIL_OPEN = true;

    private GovernanceFailOpenPolicy() {
    }

    /**
     * Central decision + uniform log line for "the control's Redis backend
     * just failed". Returns whether the guarded request should be ALLOWED.
     * The log line always carries the same searchable marker so an operator
     * can see the correlated blast radius of one Redis outage with a single
     * grep, instead of six differently-worded warnings.
     *
     * @param controlName short stable name of the control, e.g. "llm-gateway-rate-limit"
     * @param cause       the backend failure
     * @param log         the CALLER's logger, so the line lands under the
     *                    limiter's own logger name/level config
     */
    public static boolean allowOnBackendFailure(String controlName, Exception cause, Logger log) {
        if (SPEND_CONTROLS_FAIL_OPEN) {
            log.warn("GOVERNANCE_FAIL_OPEN control={} backend failure - allowing traffic UNMETERED; "
                + "if Redis is down, every Redis-backed spend/abuse control is failing open together "
                + "(see GovernanceFailOpenPolicy)", controlName, cause);
            return true;
        }
        log.error("GOVERNANCE_FAIL_CLOSED control={} backend failure - BLOCKING traffic "
            + "(SPEND_CONTROLS_FAIL_OPEN=false, see GovernanceFailOpenPolicy)", controlName, cause);
        return false;
    }
}
