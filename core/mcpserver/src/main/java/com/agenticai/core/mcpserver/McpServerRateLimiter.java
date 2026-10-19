package com.agenticai.core.mcpserver;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-grant fixed-window rate limiter for the inbound MCP server surface -
 * the same shape {@code core.chat}'s {@code LlmGatewayRateLimiterService}
 * already uses (plain Redis {@code INCR} + conditional {@code EXPIRE},
 * fail-open if Redis is unreachable), keyed by grant id rather than tenant
 * id so multiple keys issued by the same tenant don't share one bucket.
 * Applied once per inbound HTTP request in {@link McpApiKeyAuthFilter}
 * (before the MCP transport even parses the JSON-RPC body) rather than
 * inside individual tool callbacks, so it also throttles MCP protocol
 * chatter (repeated tools/list calls, malformed handshakes) from an abusive
 * caller, not just successful agent invocations.
 */
@Component
public class McpServerRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(McpServerRateLimiter.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:mcpserver-rate:";
    // Component tag on GovernanceDegradationSignal.METRIC_NAME - one name for
    // both fail-open branches (Redis exception AND null INCR result), so the
    // dashboard sees "the inbound MCP throttle is degraded" as a single
    // series, same convention as LlmGatewayRateLimiterService's
    // "llm-gateway-rate-limiter" tag.
    private static final String DEGRADATION_COMPONENT = "mcpserver-rate-limiter";

    private final StringRedisTemplate redisTemplate;
    private final GovernanceDegradationSignal degradationSignal;
    private final long maxCallsPerWindow;
    private final long maxPollsPerWindow;
    private final Duration window;

    /** Default poll allowance: ten times the general bucket. */
    static final long DEFAULT_MAX_POLLS_PER_MINUTE = 600;

    public McpServerRateLimiter(
        StringRedisTemplate redisTemplate,
        GovernanceDegradationSignal degradationSignal,
        long maxCallsPerWindow
    ) {
        this(redisTemplate, degradationSignal, maxCallsPerWindow, DEFAULT_MAX_POLLS_PER_MINUTE);
    }

    @Autowired
    public McpServerRateLimiter(
        StringRedisTemplate redisTemplate,
        GovernanceDegradationSignal degradationSignal,
        @Value("${app.mcpserver.rate-limit.max-calls-per-minute:60}") long maxCallsPerWindow,
        @Value("${app.mcpserver.rate-limit.max-task-polls-per-minute:600}") long maxPollsPerWindow
    ) {
        this.redisTemplate = redisTemplate;
        this.degradationSignal = degradationSignal;
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.maxPollsPerWindow = maxPollsPerWindow;
        this.window = Duration.ofMinutes(1);
    }

    /** @return true if this grant is within its rate limit (or Redis is unreachable - fails open, see class javadoc); false if it should be rejected with 429. */
    public boolean allow(UUID grantId) {
        return check(KEY_PREFIX + grantId, maxCallsPerWindow, grantId);
    }

    /**
     * Separate, more generous bucket for {@code get_task} polls (a poll is a cheap store read, and a
     * client waiting on a multi-minute task would otherwise burn the general budget and get 429s
     * on the very call that retrieves its result). Same Redis/fail-open behaviour as {@link #allow}.
     */
    public boolean allowPoll(UUID grantId) {
        return check(KEY_PREFIX + "poll:" + grantId, maxPollsPerWindow, grantId);
    }

    /**
     * Separate bucket for invocations of a tenant's exposed agents/workflows as MCP tools
     * (app.mcp-server.expose-agents) - a real agent turn or workflow run is far more expensive than
     * the protocol chatter the general bucket mostly counts, so it gets its own, tighter cap per
     * grant. Same Redis/fail-open-to-local behaviour as {@link #allow}.
     */
    public boolean allowExposedInvocation(UUID grantId, long maxInvocationsPerWindow) {
        return check(KEY_PREFIX + "invoke:" + grantId, maxInvocationsPerWindow, grantId);
    }

    private boolean check(String key, long maxCallsPerWindow, UUID grantId) {
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Still signalled as a degradation (dashboards must see Redis is
            // gone), but the check is no longer SKIPPED: it runs in-process
            // (security audit 2026-09-18, see LocalRateLimitFallback).
            REDIS_DOWN_LOG.warnf("MCP server rate limiter could not reach Redis for grant {} - limiting in-process", grantId, e);
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return LocalRateLimitFallback.tryAcquire(key, maxCallsPerWindow, window);
        }
        if (count == null) {
            // Reachable-but-odd Redis - no exception to attach, but still a
            // degraded check that dashboards must see (signal accepts null).
            degradationSignal.failOpen(DEGRADATION_COMPONENT, null);
            return LocalRateLimitFallback.tryAcquire(key, maxCallsPerWindow, window);
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        return count <= maxCallsPerWindow;
    }
}
