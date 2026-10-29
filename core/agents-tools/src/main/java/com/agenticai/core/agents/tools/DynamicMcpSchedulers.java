package com.agenticai.core.agents.tools;

import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled/event wiring for {@link DynamicMcpConnectionManager}: reconnect-on-boot,
 * liveness re-checks, error retry and package-cache janitor. Split out so the manager stays
 * under the file-size cap; every method delegates to the manager's public entry points, which
 * keep the behaviour (and their javadoc) exactly as before.
 *
 * <p>Deliberately NOT wrapped in {@code PgAdvisoryJobLock}: the state being touched -
 * {@code liveClients}' subprocess/HTTP clients and this host's on-disk caches - is per-JVM, so
 * every replica must run its own sweep. Cross-replica exclusion would leave losing replicas'
 * dead clients reported CONNECTED forever and their caches uncleaned.
 */
@Component
final class DynamicMcpSchedulers {

    private static final long HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long RETRY_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long CACHE_CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;

    private final DynamicMcpConnectionManager manager;

    DynamicMcpSchedulers(DynamicMcpConnectionManager manager) {
        this.manager = manager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconnectEnabledOnStartup() {
        manager.reconnectEnabledOnStartup();
    }

    @AllowUnlockedSchedule(
            reason =
                    "Pings this JVM's own liveClients (subprocess/HTTP clients are per-JVM).")
    @Scheduled(fixedRate = HEALTH_CHECK_INTERVAL_MS)
    public void healthCheckLiveConnections() {
        manager.healthCheckLiveConnections();
    }

    @AllowUnlockedSchedule(
            reason =
                    "Each replica rebuilds its own per-JVM client after an error; the Redis ownership lease (app.mcp.registry-store=redis) already restricts the retry counter to the coordinator.")
    @Scheduled(fixedRate = RETRY_CHECK_INTERVAL_MS)
    public void retryErroredConnections() {
        manager.retryErroredConnections();
    }

    @AllowUnlockedSchedule(
            reason = "Cleans this host's own on-disk MCP package caches; local to the replica.")
    @Scheduled(fixedRate = CACHE_CLEANUP_INTERVAL_MS)
    public void cleanupOrphanedPackageCaches() {
        manager.cleanupOrphanedPackageCaches();
    }
}
