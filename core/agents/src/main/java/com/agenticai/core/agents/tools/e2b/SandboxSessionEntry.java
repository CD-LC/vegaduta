package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One session's mounted files in {@link SandboxFileRegistry}, plus the capacity accounting over all
 * held sessions (per-tenant and platform-wide session counts and bytes) the registry's eviction
 * uses. Mutable and not thread-safe: only touched while holding the registry's monitor.
 */
final class SandboxSessionEntry {

    final List<SandboxFile> files = new ArrayList<>();
    long totalBytes;
    long touchedAt;
    String tenantId = SandboxFileRegistry.NO_TENANT;

    /**
     * This replica queued Redis writes for the session, so it also owns deleting them on
     * capacity eviction.
     */
    boolean wroteRemote;

    /**
     * Raw bytes queued for Redis by this replica (what a delete gives back to the Redis byte
     * budgets).
     */
    long remoteBytes;

    static int countSessions(Collection<SandboxSessionEntry> sessions, String tenant) {
        int n = 0;
        for (SandboxSessionEntry e : sessions) {
            if (tenant.equals(e.tenantId)) {
                n++;
            }
        }
        return n;
    }

    static String heaviestTenantBySessions(Collection<SandboxSessionEntry> sessions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SandboxSessionEntry e : sessions) {
            counts.merge(e.tenantId, 1, Integer::sum);
        }
        String best = null;
        int bestCount = -1;
        for (var c : counts.entrySet()) {
            if (c.getValue() > bestCount) {
                best = c.getKey();
                bestCount = c.getValue();
            }
        }
        return best;
    }

    static long tenantBytes(Collection<SandboxSessionEntry> sessions, String tenant) {
        long total = 0;
        for (SandboxSessionEntry e : sessions) {
            if (tenant.equals(e.tenantId)) {
                total += e.totalBytes;
            }
        }
        return total;
    }

    static long totalBytes(Collection<SandboxSessionEntry> sessions) {
        long total = 0;
        for (SandboxSessionEntry e : sessions) {
            total += e.totalBytes;
        }
        return total;
    }

    static String heaviestTenantByBytes(Collection<SandboxSessionEntry> sessions) {
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (SandboxSessionEntry e : sessions) {
            bytes.merge(e.tenantId, e.totalBytes, Long::sum);
        }
        String best = null;
        long bestBytes = -1;
        for (var b : bytes.entrySet()) {
            if (b.getValue() > bestBytes) {
                best = b.getKey();
                bestBytes = b.getValue();
            }
        }
        return best;
    }
}
