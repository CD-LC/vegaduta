package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orphaned npm/uv package-cache sweep extracted from {@link DynamicMcpConnectionManager} (behaviour
 * unchanged; the manager keeps the {@code @Scheduled} entry point and delegates here).
 */
final class McpPackageCacheJanitor {

    private static final Logger log = LoggerFactory.getLogger(McpPackageCacheJanitor.class);

    private final McpRegistryService registryService;
    private final TenantSchemaIterator tenantSchemaIterator;

    /**
     * Every root {@link McpStdioParamsBuilder#isolatedPackageCacheEnv} keys by registration id -
     * the {@link McpStdioParamsBuilder#ISOLATED_CACHE_ROOTS} sub-roots under the owner-only base
     * ({@code app.mcp.package-cache-dir}, see {@link McpStdioParamsBuilder#resolvePackageCacheDir})
     * - swept by {@link #cleanupOrphanedPackageCaches}.
     */
    private final List<Path> isolatedCacheRoots;

    /**
     * @param packageCacheBase the same resolved base the {@link McpStdioParamsBuilder} points every
     *     spawn at - the sweep must look where the caches are actually created
     */
    McpPackageCacheJanitor(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            Path packageCacheBase) {
        this.registryService = registryService;
        this.tenantSchemaIterator = tenantSchemaIterator;
        List<Path> roots = new java.util.ArrayList<>();
        for (String root : McpStdioParamsBuilder.ISOLATED_CACHE_ROOTS) {
            roots.add(packageCacheBase.resolve(root));
        }
        this.isolatedCacheRoots = List.copyOf(roots);
    }

    /**
     * Space-growth follow-up, 2026-08-13: {@link McpStdioParamsBuilder#isolatedPackageCacheEnv}
     * gives every registration its own npm/uv cache+install directories to eliminate the
     * concurrent- first-writer race (see that method's javadoc) - but NOTHING ever deleted one
     * again. There is no DELETE endpoint for a registration anywhere in this codebase ({@code
     * McpRegistryController} only ever disables one via {@code PATCH}), so a periodic orphan sweep
     * - diff every directory actually on disk against every registration id that still exists,
     * across every tenant schema - is the only thing that can ever reclaim this, not a hook on some
     * mutation path that doesn't exist. A disabled-but- still-registered server correctly keeps its
     * cache (re-enabling it shouldn't force a re-download); only a registration whose row is
     * genuinely gone (deleted at the DB level, or never really existed - e.g. a leftover partial
     * download from a crash mid- connect) ever gets reclaimed.
     *
     * <p>Same posture {@code DynamicMcpConnectionManager#healthCheckLiveConnections} already
     * documents for skipping {@code PgAdvisoryJobLock}: these directories are this JVM's own local
     * disk, not shared state - under multi-replica, every replica must sweep its own disk, a
     * cross-replica lock would just leave the losing replica's orphans on disk forever.
     *
     * <p>Best-effort throughout, same as every other method in this class: one unreadable/
     * undeletable directory (permission oddity, or a registration created between this sweep's
     * snapshot and its delete pass - a real but narrow race, self-healing since the worst case is a
     * cold-cache re-download on that registration's next connect, not data loss) is logged and
     * skipped, never allowed to abort the rest of the sweep. Hourly, not on the 5-minute
     * health-check/retry cadence - this is disk hygiene, not a connection-liveness concern, and
     * doesn't need to react quickly.
     */
    void cleanupOrphanedPackageCaches() {
        Set<String> liveRegistrationIds = new java.util.HashSet<>();
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    for (com.agenticai.core.mcp.dto.McpServerRegistrationResponse r :
                            registryService.listAll()) {
                        liveRegistrationIds.add(r.id().toString());
                    }
                });
        int deleted = 0;
        for (Path root : isolatedCacheRoots) {
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (var children = java.nio.file.Files.list(root)) {
                for (java.nio.file.Path dir : children.toList()) {
                    if (!java.nio.file.Files.isDirectory(dir)
                            || liveRegistrationIds.contains(dir.getFileName().toString())) {
                        continue;
                    }
                    if (deleteRecursively(dir)) {
                        deleted++;
                    }
                }
            } catch (java.io.IOException e) {
                log.warn("MCP package cache cleanup could not list {}", root, e);
            }
        }
        if (deleted > 0) {
            log.info(
                    "MCP package cache cleanup removed {} orphaned registration cache dir(s)",
                    deleted);
        }
    }

    /** Best-effort recursive delete for {@link #cleanupOrphanedPackageCaches} - never throws. */
    private boolean deleteRecursively(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(p);
                                } catch (java.io.IOException e) {
                                    log.debug(
                                            "Could not delete {} during MCP package cache cleanup",
                                            p,
                                            e);
                                }
                            });
            return !java.nio.file.Files.exists(dir);
        } catch (java.io.IOException e) {
            log.warn("MCP package cache cleanup could not walk {}", dir, e);
            return false;
        }
    }
}
