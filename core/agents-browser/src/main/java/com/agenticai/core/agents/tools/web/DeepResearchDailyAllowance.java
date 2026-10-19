package com.agenticai.core.agents.tools.web;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-caller daily allowance of platform-paid searches made by {@code deep_research}, enforced
 * where the spend happens: inside the tool call. It therefore holds on every path that can reach
 * the tool - a research run, a chat turn with the research agent, a direct agent job, or any agent
 * the user switched the "Deep research" capability on for - not only on the {@code
 * /api/research/runs} facade.
 *
 * <p>Counted per (tenant, user); a call with no user (a channel message, a system trigger) is
 * counted per (tenant, agent) instead, so such traffic cannot share one tenant-wide bucket nor
 * escape the cap. The unit is search queries actually reserved, because that is what the platform
 * pays for.
 *
 * <p>Tool calls use {@link #reserve}: one atomic increment BEFORE searching (so simultaneous calls
 * cannot all pass a check-then-act and overshoot the cap), with {@link #refund} returning what was
 * not used. On a Redis outage the counter keeps counting in-process (see {@link
 * DailyWindowCounter}). {@code app.research.deep.max-searches-per-user-per-day <= 0} switches the
 * allowance off.
 */
@Component
public class DeepResearchDailyAllowance {

    static final String KEY_PREFIX = "agentic:research-searches:daily:";

    private final DailyWindowCounter counter;
    private final int maxPerDay;

    public DeepResearchDailyAllowance(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.research.deep.max-searches-per-user-per-day:60}") int maxPerDay) {
        // Local fallback: a Redis outage degrades to a per-replica cap, not to no cap on
        // platform-paid searches.
        this.counter = new DailyWindowCounter(redis, true);
        this.maxPerDay = Math.max(0, maxPerDay);
    }

    /** Searches one caller may run per 24h window; 0 = no allowance is enforced. */
    public int maxPerDay() {
        return maxPerDay;
    }

    /**
     * Searches this caller may still run in the current window ({@link Integer#MAX_VALUE} when
     * uncapped). Read-only.
     */
    public int remaining(String tenantId, String caller) {
        if (maxPerDay <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, maxPerDay - counter.current(key(tenantId, caller)));
    }

    /** Counts searches a deep_research call reserved. Never throws. */
    public void record(String tenantId, String caller, int searches) {
        if (maxPerDay <= 0 || searches <= 0) {
            return;
        }
        counter.add(key(tenantId, caller), searches);
    }

    /**
     * Atomically reserves up to {@code wanted} searches for this caller and returns how many were
     * granted (0 when the window is used up). Increments first and trims back, so two concurrent
     * callers can never both be granted the last search. Never throws.
     */
    public int reserve(String tenantId, String caller, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        if (maxPerDay <= 0) {
            return wanted;
        }
        String key = key(tenantId, caller);
        long after = counter.add(key, wanted);
        if (after <= 0) {
            // Nothing could be counted anywhere (not expected with the local fallback): behave like
            // check-then-count.
            return Math.min(wanted, remaining(tenantId, caller));
        }
        long before = after - wanted;
        int granted = (int) Math.max(0, Math.min(wanted, maxPerDay - before));
        if (granted < wanted) {
            counter.undo(key, wanted - granted);
        }
        return granted;
    }

    /** Gives back reserved-but-unused searches. Never throws. */
    public void refund(String tenantId, String caller, int searches) {
        if (maxPerDay <= 0 || searches <= 0) {
            return;
        }
        counter.giveBack(key(tenantId, caller), searches);
    }

    /**
     * The allowance of one tool resolution's caller, bound to its identity. Identity is captured by
     * the resolver from {@code TenantContext} on the resolving thread - never from a tool argument.
     */
    public DeepResearchToolCallback.SearchAllowance forCaller(
            String tenantId, String subject, UUID agentId) {
        String caller = callerKey(subject, agentId);
        return new DeepResearchToolCallback.SearchAllowance() {
            @Override
            public int remaining() {
                return DeepResearchDailyAllowance.this.remaining(tenantId, caller);
            }

            @Override
            public void record(int searches) {
                DeepResearchDailyAllowance.this.record(tenantId, caller, searches);
            }

            @Override
            public int reserve(int wanted) {
                return DeepResearchDailyAllowance.this.reserve(tenantId, caller, wanted);
            }

            @Override
            public void refund(int searches) {
                DeepResearchDailyAllowance.this.refund(tenantId, caller, searches);
            }
        };
    }

    /** The user when there is one; otherwise the agent, so user-less traffic is still bounded. */
    public static String callerKey(String subject, UUID agentId) {
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        return "agent:" + agentId;
    }

    private static String key(String tenantId, String caller) {
        return KEY_PREFIX + tenantId + ":" + caller;
    }
}
