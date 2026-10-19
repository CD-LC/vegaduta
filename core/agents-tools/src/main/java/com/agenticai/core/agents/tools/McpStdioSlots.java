package com.agenticai.core.agents.tools;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tenant bound on concurrent STDIO subprocesses (fork-bomb guard). Two independent counters
 * share one cap: the live STDIO registrations ({@link #stdioCapReached}), and the transient,
 * in-flight connection <em>tests</em> that spawn throwaway subprocesses (Pentest H3,
 * {@link #tryAcquireTestSlot}). Plain object owned by {@link DynamicMcpConnectionManager}; all state
 * is keyed tenant-first so another tenant's slots are unreachable by construction.
 */
final class McpStdioSlots {

    private final int maxPerTenant;
    // Which live registrations are STDIO subprocesses (the cap only counts real child processes,
    // not remote HTTP connections) - same tenant-first keying as the live client table.
    private final Map<String, Set<UUID>> liveStdioIds = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlightStdioTests = new ConcurrentHashMap<>();

    McpStdioSlots(int maxPerTenant) {
        this.maxPerTenant = maxPerTenant;
    }

    int maxPerTenant() {
        return maxPerTenant;
    }

    /**
     * Per-tenant fork-bomb bound - only real live STDIO subprocesses count (remotes hold no
     * process).
     */
    boolean stdioCapReached(String tenant, UUID registrationId) {
        Set<UUID> ids = liveStdioIds.getOrDefault(tenant, Set.of());
        return ids.size() >= maxPerTenant && !ids.contains(registrationId);
    }

    void markLive(String tenant, UUID registrationId) {
        liveStdioIds.computeIfAbsent(tenant, t -> ConcurrentHashMap.newKeySet()).add(registrationId);
    }

    void release(String tenant, UUID registrationId) {
        Set<UUID> stdioIds = liveStdioIds.get(tenant);
        if (stdioIds != null) {
            stdioIds.remove(registrationId);
        }
    }

    /**
     * Pentest H3: reserve one of a tenant's in-flight STDIO-test slots; false when the cap is
     * already reached.
     */
    boolean tryAcquireTestSlot(String tenant) {
        AtomicInteger counter = inFlightStdioTests.computeIfAbsent(tenant, t -> new AtomicInteger());
        if (counter.incrementAndGet() > maxPerTenant) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    void releaseTestSlot(String tenant) {
        AtomicInteger counter = inFlightStdioTests.get(tenant);
        if (counter != null && counter.decrementAndGet() <= 0) {
            // Best-effort cleanup so the map doesn't grow unbounded across tenants.
            inFlightStdioTests.remove(tenant, counter);
        }
    }
}
