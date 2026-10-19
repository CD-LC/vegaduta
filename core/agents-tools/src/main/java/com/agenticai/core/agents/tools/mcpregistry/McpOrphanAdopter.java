package com.agenticai.core.agents.tools.mcpregistry;

import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistration;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Only for {@code registry-store=redis} + {@code registry-exclusive-clients=true}: when an owner
 * dies, its lease expires and nobody holds a client for its registrations. This sweep connects
 * enabled registrations that have no live owner; {@code connect} itself claims the lease (SET NX),
 * so at most one replica wins each registration. Only CONNECTED registrations are adopted: ERROR
 * ones belong to the retry sweep (backoff + attempt cap) and a connect already in flight is left
 * alone. The sweep is skipped while the store is unreachable (empty {@code ownerOf} then means
 * "unknown", not "unowned"), and adopts on a small bounded pool with one in-flight adoption per
 * registration.
 */
@Component
@ConditionalOnProperty(name = "app.mcp.registry-exclusive-clients", havingValue = "true")
public class McpOrphanAdopter {

    private static final Logger log = LoggerFactory.getLogger(McpOrphanAdopter.class);
    private static final String ACTOR = "system:mcp-adopt";

    private final McpRegistryService registryService;
    private final TenantSchemaIterator tenantSchemaIterator;
    private final McpOwnershipRegistry ownership;
    private final ObjectProvider<McpDynamicConnector> connector;
    private final ExecutorService pool =
            TenantAwareExecutors.newFixedThreadPool(
                    2,
                    TenantAwareExecutors.threadFactory("mcp-adopt", true));
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Adoption threads stop with the bean (JAVA-026); a half-done adoption is retried by the next
     * owner.
     */
    @jakarta.annotation.PreDestroy
    void shutdownPool() {
        pool.shutdownNow();
    }

    public McpOrphanAdopter(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            McpOwnershipRegistry ownership,
            ObjectProvider<McpDynamicConnector> connector) {
        this.registryService = registryService;
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.ownership = ownership;
        this.connector = connector;
    }

    @AllowUnlockedSchedule(
            reason =
                    "Coordinated by the Redis ownership lease (adopt takes mcp:owner:* before connecting), not by a per-tick lock; exclusive-clients mode needs every replica to look for orphans.")
    @Scheduled(
            fixedDelayString = "${app.mcp.registry-adopt-interval-ms:60000}",
            initialDelayString = "${app.mcp.registry-adopt-interval-ms:60000}")
    public void adoptOrphans() {
        McpDynamicConnector c = connector.getIfAvailable();
        if (c == null || !ownership.active()) {
            return;
        }
        if (!ownership.storeReachable()) {
            return;
        }
        tenantSchemaIterator.forEachActiveTenantSchema(
                schema -> {
                    for (McpServerRegistration r : registryService.enabledDynamicRegistrations()) {
                        UUID id = r.getId();
                        if (r.getConnectionStatus() == McpConnectionStatus.CONNECTED
                                && ownership.ownerOf(schema, id).isEmpty()) {
                            log.info(
                                    "Adopting ownerless MCP registration {} for tenant '{}'",
                                    id,
                                    schema);
                            adopt(c, schema, id);
                        }
                    }
                });
    }

    /** Bounded pool with an explicit tenant, same shape as the startup reconnect. */
    void adopt(McpDynamicConnector c, String schema, UUID id) {
        String key = schema + ":" + id;
        if (!inFlight.add(key)) {
            return;
        }
        try {
            pool.execute(
                    () -> {
                        TenantContext.set(TenantContext.of(schema, ACTOR, Set.of()));
                        try {
                            c.connect(id);
                        } finally {
                            TenantContext.clear();
                            inFlight.remove(key);
                        }
                    });
        } catch (RuntimeException e) {
            inFlight.remove(key);
        }
    }
}
