package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes persisted {@code run_code} output files (charts, workbooks, scripts) after a TTL so
 * object storage cannot grow without bound. Object keys are tenant-prefixed physically, so a bucket
 * lifecycle rule on {@code sandbox-artifacts/} would never match; instead each stored artifact is
 * recorded here (with its tenant) and a scheduled sweep deletes it, inside that tenant's {@link
 * TenantContext}, once it is older than {@code app.sandbox.data-analysis.artifact-ttl-hours}.
 *
 * <p>Best-effort and in-memory: a restart forgets the pending list, so artifacts written just
 * before a deploy are orphaned (bounded - a day's worth at most - and they stay unguessable and
 * ownership-checked). The list itself is capped; when full, new artifacts simply are not tracked.
 */
@Component
public class SandboxArtifactRetention {

    private static final Logger log = LoggerFactory.getLogger(SandboxArtifactRetention.class);
    private static final int MAX_TRACKED = 20_000;

    private record Pending(String tenantId, UUID id, String extension, long createdAt) {}

    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final FileStorageService storage;
    private final long ttlMillis;
    private final LongSupplier clock;

    /**
     * Only run the storage listing on boot when the feature is on (default-off = no extra S3
     * calls).
     */
    private final boolean startupSweepEnabled;

    @Autowired
    public SandboxArtifactRetention(
            FileStorageService storage,
            @Value("${app.sandbox.data-analysis.artifact-ttl-hours:24}") long ttlHours,
            @Value("${app.sandbox.data-analysis.enabled:false}") boolean featureEnabled,
            @Value("${app.sandbox.data-analysis.startup-sweep-enabled:true}")
                    boolean startupSweep) {
        this(
                storage,
                ttlHours * 3_600_000L,
                System::currentTimeMillis,
                featureEnabled && startupSweep);
    }

    SandboxArtifactRetention(FileStorageService storage, long ttlMillis, LongSupplier clock) {
        this(storage, ttlMillis, clock, true);
    }

    SandboxArtifactRetention(
            FileStorageService storage,
            long ttlMillis,
            LongSupplier clock,
            boolean startupSweepEnabled) {
        this.storage = storage;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.startupSweepEnabled = startupSweepEnabled;
    }

    /**
     * Runs {@link #recoverOnStartup} off the startup thread so a slow bucket listing never delays
     * readiness.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!startupSweepEnabled) {
            return;
        }
        TenantAwareExecutors.startThread(
                "sandbox-artifact-startup-sweep",
                true,
                () -> {
                            try {
                                recoverOnStartup();
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Sandbox artifact startup sweep failed: {}",
                                        e.getMessage());
                            }
                        });
    }

    /**
     * Rebuilds the in-memory retention queue after a restart by listing what is actually in object
     * storage: objects under {@code sandbox-artifacts/} are grouped per artifact id (data file +
     * {@code .meta} sidecar); a group whose newest object is older than the TTL is deleted (inside
     * its tenant's context), a younger one is re-registered so the normal sweep expires it on time.
     * Returns the number of artifacts deleted. Best-effort - a listing failure just leaves things
     * as they were (the pre-existing orphan behaviour).
     */
    int recoverOnStartup() {
        if (!startupSweepEnabled) {
            return 0;
        }
        List<FileStorageService.StoredObject> objects;
        try {
            objects = storage.listAcrossTenants(SandboxArtifacts.KEY_PREFIX);
        } catch (RuntimeException e) {
            log.warn("Could not list stored sandbox artifacts at startup: {}", e.getMessage());
            return 0;
        }
        record Group(String tenantId, UUID id, List<String> keys, String[] ext, long[] newest) {}
        Map<String, Group> groups = new LinkedHashMap<>();
        for (FileStorageService.StoredObject o : objects) {
            String key = o.key();
            if (key == null || !key.startsWith(SandboxArtifacts.KEY_PREFIX)) {
                continue;
            }
            String leaf = key.substring(SandboxArtifacts.KEY_PREFIX.length());
            int dot = leaf.lastIndexOf('.');
            if (dot <= 0 || dot == leaf.length() - 1) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(leaf.substring(0, dot));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String ext = leaf.substring(dot + 1);
            Group g =
                    groups.computeIfAbsent(
                            o.tenantId() + "|" + id,
                            k ->
                                    new Group(
                                            o.tenantId(),
                                            id,
                                            new ArrayList<>(),
                                            new String[1],
                                            new long[1]));
            g.keys().add(key);
            if (!"meta".equals(ext)) {
                g.ext()[0] = ext;
            }
            g.newest()[0] = Math.max(g.newest()[0], o.lastModifiedMillis());
        }
        long now = clock.getAsLong();
        int deleted = 0;
        List<Pending> keep = new ArrayList<>();
        for (Group g : groups.values()) {
            if (now - g.newest()[0] > ttlMillis) {
                deleteKeys(g.tenantId(), g.id(), g.keys());
                deleted++;
            } else if (g.ext()[0] != null) {
                keep.add(new Pending(g.tenantId(), g.id(), g.ext()[0], g.newest()[0]));
            }
        }
        // The queue is swept head-first by age, so it must stay in creation order.
        keep.sort(java.util.Comparator.comparingLong(Pending::createdAt));
        for (Pending p : keep) {
            if (pending.size() < MAX_TRACKED) {
                pending.add(p);
            }
        }
        log.info(
                "Sandbox artifact startup sweep: {} expired artifact(s) deleted, {} re-registered for retention",
                deleted,
                keep.size());
        return deleted;
    }

    private void deleteKeys(String tenantId, UUID id, List<String> keys) {
        try {
            if (tenantId != null) {
                TenantContext.set(
                        TenantContext.of(tenantId, "sandbox-artifact-retention", Set.of()));
            }
            for (String key : keys) {
                storage.delete(key);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete expired sandbox artifact {}: {}", id, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Tracks a just-stored artifact for later deletion; {@code tenantId} may be null (no tenant
     * prefix).
     */
    public void track(String tenantId, UUID id, String extension) {
        if (pending.size() < MAX_TRACKED) {
            pending.add(new Pending(tenantId, id, extension, clock.getAsLong()));
        }
    }

    int pendingCount() {
        return pending.size();
    }

    @AllowUnlockedSchedule(
            reason =
                    "Drains this JVM's own in-memory queue of artifacts it stored (pending); every replica deletes the artifacts it tracked. See docs/plans/CORE-STATE-AUDIT.md.")
    @Scheduled(
            fixedDelayString = "${app.sandbox.data-analysis.artifact-sweep-ms:600000}",
            initialDelay = 600_000)
    public void sweep() {
        long cutoff = clock.getAsLong() - ttlMillis;
        Pending head;
        while ((head = pending.peek()) != null && head.createdAt() <= cutoff) {
            pending.poll();
            delete(head);
        }
    }

    private void delete(Pending p) {
        try {
            if (p.tenantId() != null) {
                TenantContext.set(
                        TenantContext.of(p.tenantId(), "sandbox-artifact-retention", Set.of()));
            }
            storage.delete(SandboxArtifacts.dataKey(p.id(), p.extension()));
            storage.delete(SandboxArtifacts.metaKey(p.id()));
        } catch (RuntimeException e) {
            log.warn("Could not delete expired sandbox artifact {}: {}", p.id(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
