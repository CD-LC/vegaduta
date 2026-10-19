package com.agenticai.core.agents.job.ownership;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/**
 * The identity a replica stamps into {@code agent_job.owner_instance} when ownership is enabled:
 * {@code <replica-or-host>[/<revision>]:<pid>:<random8>}.
 *
 * <p>The legacy id ({@code host:pid}) is identical for a restarted container (same hostname, JVM usually
 * PID 1), so a replacement could not tell its predecessor's rows from its own. The random suffix is stable for
 * the life of one process and different for every incarnation, which is what the lease/fencing logic needs.
 * Azure Container Apps sets {@code CONTAINER_APP_REPLICA_NAME} and {@code CONTAINER_APP_REVISION}
 * (https://learn.microsoft.com/azure/container-apps/environment-variables); neither is required.
 * {@code owner_instance} is VARCHAR(128): the prefix is trimmed, never the suffix.
 */
public final class JobReplicaId {

    static final int MAX_LENGTH = 128;

    private static volatile String cached;

    private JobReplicaId() {
    }

    /** Pure, for tests. Blank inputs are skipped. */
    public static String compute(String replicaName, String hostName, String revision, String pid, String random) {
        String base = firstNonBlank(replicaName, hostName, "unknown");
        if (revision != null && !revision.isBlank()) {
            base = base + "/" + revision.trim();
        }
        String tail = ":" + firstNonBlank(pid, "0") + ":" + firstNonBlank(random, "00000000");
        int room = Math.max(1, MAX_LENGTH - tail.length());
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return base + tail;
    }

    /** This process's id, computed once. */
    public static String current() {
        String id = cached;
        if (id == null) {
            synchronized (JobReplicaId.class) {
                id = cached;
                if (id == null) {
                    id = computeForThisProcess();
                    cached = id;
                }
            }
        }
        return id;
    }

    private static String computeForThisProcess() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception unresolvable) {
            host = null;
        }
        String pid;
        try {
            pid = ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            if (at > 0) {
                pid = pid.substring(0, at);
            }
        } catch (RuntimeException e) {
            pid = "0";
        }
        return compute(
            System.getenv("CONTAINER_APP_REPLICA_NAME"), host, System.getenv("CONTAINER_APP_REVISION"), pid,
            UUID.randomUUID().toString().substring(0, 8));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
