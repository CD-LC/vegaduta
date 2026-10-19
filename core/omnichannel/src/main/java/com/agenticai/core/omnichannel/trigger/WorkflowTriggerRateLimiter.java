package com.agenticai.core.omnichannel.trigger;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct feedback: "if I approve so many whatsapp message, then how do i
 * control? every message might trigger workflow isnt it?" - correct, and
 * previously there was no answer: once a sender passes the one-time pairing
 * gate, EVERY message they send re-triggers the channel-bound workflow,
 * forever, with no cooldown ({@code ChannelWorkflowTriggerService} had no
 * concept of "too often" at all). This is a fixed-window limiter, keyed by
 * tenant + trigger channel + sender, so one chatty (or malicious, or just
 * confused) approved number can't turn a single pairing approval into an
 * unbounded stream of workflow runs.
 *
 * <p>Same shape as {@link com.agenticai.core.tenancy.SignupRateLimiter} -
 * plain Redis {@code INCR} + conditional {@code EXPIRE}, fail-open if Redis
 * is unreachable (an abuse throttle degrading open is the right trade-off;
 * it must never be the reason a legitimate workflow-bound conversation stops
 * responding). Deliberately a single app-wide default rather than
 * per-workflow/per-tenant configuration - the goal is "cheap insurance
 * against runaway triggering," not fine-grained traffic shaping; a tenant
 * that needs a different ceiling can override the two env vars.
 *
 * <p>Fail-open covers both ways Redis can be "unreachable": {@code
 * increment} returning null (handled below) AND {@code increment} throwing -
 * the latter is the actual behavior of a real connection failure under
 * Lettuce/Jedis, and was previously uncaught here (this class's javadoc
 * claimed fail-open unconditionally, but only the null-return path actually
 * did; a thrown exception propagated straight up through the webhook
 * controller). Now matches the {@code try/catch RuntimeException} pattern
 * {@link com.agenticai.core.omnichannel.session.InboundMessageDedupService#tryClaim}
 * already used for the identical Redis-unreachable case.
 */
@Component
public class WorkflowTriggerRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerRateLimiter.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:workflow-trigger-rate:";

    private final StringRedisTemplate redisTemplate;
    private final int maxTriggers;
    private final Duration window;

    public WorkflowTriggerRateLimiter(
        StringRedisTemplate redisTemplate,
        @Value("${app.workflow-trigger.rate-limit.max-triggers:20}") int maxTriggers,
        @Value("${app.workflow-trigger.rate-limit.window-minutes:10}") long windowMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.maxTriggers = maxTriggers;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    /**
     * @return true if this inbound message is within the current window's
     *     trigger budget for this tenant+channel+sender (and has been
     *     counted against it); false if the sender has already exhausted
     *     their triggers for this window - the caller should skip the
     *     {@code workflowTriggerPort.triggerAsync(...)} call for this
     *     message but still return normally (not an error, not a reason to
     *     fall back to the default-agent-chat path).
     */
    public boolean tryAcquire(WorkflowTriggerChannel channel, String externalId) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
        String key = KEY_PREFIX + tenant + ":" + channel.name() + ":" + externalId;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis unreachable (connection failure, timeout, etc.) - fail
            // open, see class javadoc. This is the path a real outage
            // actually takes; the null-return branch below is a defensive
            // fallback for whatever a reachable-but-odd Redis might return.
            REDIS_DOWN_LOG.warnf("Workflow trigger rate limiter could not reach Redis for key {} - limiting in-process", key, e);
            return LocalRateLimitFallback.tryAcquire(key, maxTriggers, window);
        }
        if (count == null) {
            // Redis unreachable. A workflow must not go quiet because of an
            // infra hiccup, but an inbound channel that can start paid runs
            // must not go UNTHROTTLED for the length of one either, so the
            // same limit keeps being applied in-process (security audit
            // 2026-09-18, see LocalRateLimitFallback).
            return LocalRateLimitFallback.tryAcquire(key, maxTriggers, window);
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        return count <= maxTriggers;
    }
}
