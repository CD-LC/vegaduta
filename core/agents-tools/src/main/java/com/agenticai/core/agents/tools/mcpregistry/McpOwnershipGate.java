package com.agenticai.core.agents.tools.mcpregistry;

import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The narrow surface {@code DynamicMcpConnectionManager} consults. Every default is today's
 * single-JVM behaviour, so {@link #NOOP} (the memory mode) changes nothing and touches no Redis.
 * {@code tenant} is the tenant schema key the manager already keys its maps by.
 */
public interface McpOwnershipGate {

    McpOwnershipGate NOOP = new McpOwnershipGate() {};

    /**
     * Called once by the manager: the local connector to run routed commands on, and its
     * live-client view.
     */
    default void attach(McpDynamicConnector local, BiPredicate<String, UUID> hasLiveClient) {}

    /**
     * True if the connect was handed to the owning replica and the caller must NOT connect locally.
     */
    default boolean routeConnect(String tenant, UUID registrationId) {
        return false;
    }

    /**
     * Tells every other replica to drop its client for this registration (call before the local
     * disconnect).
     */
    default void broadcastDisconnect(String tenant, UUID registrationId) {}

    /** A test result produced by the owning replica, or empty to run the throwaway test locally. */
    default Optional<McpConnectionTestResult> routeTest(String tenant, UUID registrationId) {
        return Optional.empty();
    }

    /**
     * Called once by the manager: how to run a tool on a client this JVM holds (owner side of
     * {@link #routeToolCall}).
     */
    default void attachToolCaller(McpToolCaller caller) {}

    /**
     * Stateless-friendly call routing: when this replica holds no client for the registration but a
     * peer owns it (and {@code app.mcp.registry-route-tool-calls=true}), runs the tool there and
     * returns its result text. Empty = not routed, run locally (today's behaviour).
     *
     * @throws IllegalStateException when routed but the owner failed or did not answer; the caller
     *     must NOT retry locally, because the tool may already have run (side effects).
     */
    default Optional<String> routeToolCall(
            String tenant, UUID registrationId, String toolName, String argsJson) {
        return Optional.empty();
    }

    /**
     * Retry sweep: true if this replica is (or just became) the one that should retry this
     * registration.
     */
    default boolean mayRetry(String tenant, UUID registrationId) {
        return true;
    }

    /**
     * Health sweep: false if another replica owns the registration, so only the owner writes its
     * diagnosis to the DB.
     */
    default boolean mayPersistHealth(String tenant, UUID registrationId) {
        return true;
    }
}
