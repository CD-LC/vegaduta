package com.agenticai.core.agents.job.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A tiny NEGATIVE-ONLY cache answering one question on the webhook hot path:
 * "did this tenant's dispatch query for this event type come back empty a
 * moment ago?"
 *
 * <p><b>Why it exists.</b> {@link AgentEventTriggerDispatcher} costs every
 * inbound channel message one synchronous indexed {@code SELECT} on the webhook
 * request thread, for every tenant, whether or not that tenant has ever
 * configured a trigger - and most tenants never have. Caching the EMPTY answer
 * for a short TTL removes that query from the overwhelmingly common case while
 * keeping the feature exact where it matters:
 *
 * <ul>
 *   <li><b>Only absence is cached.</b> A non-empty result is never stored, so
 *       an existing trigger always gets the live query - firing is never served
 *       stale configuration.</li>
 *   <li><b>Invalidation on write.</b> {@link AgentEventTriggerService}
 *       invalidates the tenant's entries on every upsert/disable, so on this
 *       replica a brand-new trigger fires on the very next event. On today's
 *       single-replica deploy that makes the cache EXACT; with a second replica
 *       the honest cost is that another replica may sit on a stale "none" for
 *       up to {@link #TTL} - a bounded first-firing delay, never a lost event
 *       type and never a wrong firing.</li>
 *   <li><b>Bounded.</b> Entries are (tenant, eventType) pairs against the
 *       nine-item event-type allow-list, evicted lazily on expiry; a paranoid
 *       size cap clears the whole map rather than growing without bound (a
 *       clear only costs queries, never correctness).</li>
 * </ul>
 *
 * <p>A plain {@code ConcurrentHashMap} rather than Redis or Caffeine on
 * purpose: the value protected is one indexed SELECT, so any cache that itself
 * costs I/O or a new dependency would be the joke version of this class.
 */
@Component
public class AgentEventTriggerPresenceCache {

    /** See the class javadoc's honest multi-replica cost before raising this. */
    static final Duration TTL = Duration.ofSeconds(30);

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Instant> knownEmptyUntil = new ConcurrentHashMap<>();

    /** True when the (tenant, eventType) dispatch query returned empty inside the TTL. */
    boolean isKnownEmpty(String tenantId, String eventType, Instant now) {
        Instant until = knownEmptyUntil.get(key(tenantId, eventType));
        if (until == null) {
            return false;
        }
        if (until.isBefore(now)) {
            knownEmptyUntil.remove(key(tenantId, eventType));
            return false;
        }
        return true;
    }

    /** Records that the dispatch query for (tenant, eventType) just returned empty. */
    void recordEmpty(String tenantId, String eventType, Instant now) {
        if (knownEmptyUntil.size() >= MAX_ENTRIES) {
            // Degenerate configuration churn; correctness is unaffected, the
            // next round of empty queries simply repopulates what matters.
            knownEmptyUntil.clear();
        }
        knownEmptyUntil.put(key(tenantId, eventType), now.plus(TTL));
    }

    /**
     * Forgets every cached answer for one tenant. Called by the configuration
     * side on every trigger write, so a just-created trigger's first event is
     * never swallowed by a stale "none" on this replica.
     */
    public void invalidateTenant(String tenantId) {
        String prefix = tenantId + "|";
        knownEmptyUntil.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String key(String tenantId, String eventType) {
        return tenantId + "|" + eventType;
    }
}
