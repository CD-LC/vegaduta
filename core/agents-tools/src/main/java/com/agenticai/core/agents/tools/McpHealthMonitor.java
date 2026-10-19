package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.mcpregistry.McpOwnershipGate;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The background sweeps over dynamic MCP connections: startup reconnect, periodic liveness
 * re-check and bounded auto-retry. The {@code @Scheduled}/{@code @AllowUnlockedSchedule} entry
 * points stay on {@link DynamicMcpConnectionManager} (the scheduled-job lock rules and the jobs
 * pool coverage are keyed on that bean), which delegates here; see those methods for why the sweeps
 * are deliberately NOT advisory-locked (the state is per-JVM).
 *
 * <p>Tenant context: each reconnect/retry thread captures its tenant schema on the iterating thread
 * and re-applies it itself, clearing it in {@code finally} (a bare {@code new Thread} never
 * inherits the ThreadLocal).
 */
final class McpHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(McpHealthMonitor.class);
    private static final String RECONNECT_ACTOR = "system:mcp-reconnect";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final McpRegistryService registryService;
    private final McpConnectionLifecycle lifecycle;
    private final Supplier<McpOwnershipGate> ownership;
    private final Map<String, Map<UUID, McpSyncClient>> liveClients;
    private final Consumer<UUID> connect;
    private final Consumer<UUID> disconnectLocal;

    McpHealthMonitor(
            TenantSchemaIterator tenantSchemaIterator,
            McpRegistryService registryService,
            McpConnectionLifecycle lifecycle,
            Supplier<McpOwnershipGate> ownership,
            Map<String, Map<UUID, McpSyncClient>> liveClients,
            Consumer<UUID> connect,
            Consumer<UUID> disconnectLocal) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.registryService = registryService;
        this.lifecycle = lifecycle;
        this.ownership = ownership;
        this.liveClients = liveClients;
        this.connect = connect;
        this.disconnectLocal = disconnectLocal;
    }

    void reconnectEnabledOnStartup() {
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<McpServerRegistration> registrations =
                            registryService.enabledDynamicRegistrations();
                    if (registrations.isEmpty()) {
                        return;
                    }
                    log.info(
                            "Reconnecting {} enabled dynamic MCP registration(s) for tenant '{}' after startup",
                            registrations.size(),
                            schema);
                    for (McpServerRegistration registration : registrations) {
                        UUID id = registration.getId();
                        startTenantThread("mcp-reconnect-" + schema + "-" + id, schema, id);
                    }
                });
    }

    void healthCheckLiveConnections() {
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    Map<UUID, McpSyncClient> tenantClients = liveClients.get(schema);
                    if (tenantClients == null || tenantClients.isEmpty()) {
                        return;
                    }
                    // Copy the key set first - disconnect()/connect() called concurrently must
                    // never trigger a ConcurrentModificationException on this pass.
                    for (UUID registrationId : List.copyOf(tenantClients.keySet())) {
                        McpSyncClient client = tenantClients.get(registrationId);
                        if (client == null) {
                            continue; // disconnected between the snapshot and now
                        }
                        try {
                            client.listTools();
                            // Still alive - no state change (markConnected would only churn updatedAt).
                        } catch (Throwable t) {
                            log.warn(
                                    "MCP dynamic connection {} for tenant '{}' failed its periodic health check - marking ERROR",
                                    registrationId,
                                    schema,
                                    t);
                            lifecycle.countConnectionLost();
                            // Full teardown first (evicts the tool cache, closes the client, frees
                            // the STDIO slot), then persist the diagnosis: teardown touches no DB.
                            disconnectLocal.accept(registrationId);
                            if (!ownership.get().mayPersistHealth(schema, registrationId)) {
                                continue;
                            }
                            lifecycle.recordConnectionLost(registrationId, t);
                        }
                    }
                });
    }

    void retryErroredConnections() {
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<McpServerRegistration> due =
                            registryService.registrationsEligibleForRetry(Instant.now());
                    if (due.isEmpty()) {
                        return;
                    }
                    log.info(
                            "Auto-retrying {} ERROR'd dynamic MCP registration(s) for tenant '{}' (attempt count(s): {})",
                            due.size(),
                            schema,
                            due.stream().map(r -> r.getRetryAttemptCount() + 1).toList());
                    for (McpServerRegistration registration : due) {
                        UUID id = registration.getId();
                        if (!ownership.get().mayRetry(schema, id)) {
                            continue;
                        }
                        startTenantThread("mcp-auto-retry-" + schema + "-" + id, schema, id);
                    }
                });
    }

    /** Daemon thread that sets the captured tenant, connects, and always clears it. */
    private void startTenantThread(String name, String schema, UUID id) {
        TenantAwareExecutors.startThread(
                name,
                true,
                () -> {
                            TenantContext.set(
                                    TenantContext.of(schema, RECONNECT_ACTOR, Set.of()));
                            try {
                                connect.accept(id);
                            } finally {
                                TenantContext.clear();
                            }
                        });
    }
}
