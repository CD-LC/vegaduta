package com.agenticai.core.agents.tools.mcpregistry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Which API replica "owns" (coordinates) an MCP registration. Ownership is a short lease, never a
 * place where live clients are stored - the McpSyncClient / subprocess maps stay in the JVM. Keys
 * are tenant-scoped. Every implementation fails OPEN: on a store error {@link #claim} answers true
 * so the caller falls back to today's single-JVM behaviour.
 */
public interface McpOwnershipRegistry {

    /** Stable per-JVM id of this replica. */
    String replicaId();

    /** False for the in-memory (no-op) implementation - callers skip all coordination. */
    boolean active();

    /**
     * True if this replica already owns the registration or just acquired an unowned/expired lease.
     */
    boolean claim(String tenant, UUID registrationId);

    /** The live owner, if any. Empty means unowned OR store unavailable. */
    Optional<String> ownerOf(String tenant, UUID registrationId);

    /**
     * False when the store cannot be reached right now, in which case an empty {@link #ownerOf}
     * answer means "unknown", not "unowned". Callers that act on "no owner" (the orphan adopter)
     * must skip while this is false.
     */
    default boolean storeReachable() {
        return true;
    }

    /** Drops this replica's lease if (and only if) it still holds it. */
    void release(String tenant, UUID registrationId);

    /**
     * Heartbeat: renews every lease this replica holds that is still needed (a live local client,
     * or claimed less than {@code grace} ago while a connect is in flight); leases no longer needed
     * are released.
     */
    void renewOwned(BiPredicate<String, UUID> stillNeeded, Duration grace);

    /** Announces this replica in the replica directory (used for broadcasts). */
    void registerReplica();

    /** Replicas whose directory entry has not expired, including this one. */
    List<String> liveReplicas();
}
