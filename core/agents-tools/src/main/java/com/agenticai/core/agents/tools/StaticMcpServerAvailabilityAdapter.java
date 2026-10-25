package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpStaticServerAvailability;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link McpStaticServerAvailability} over the real static-client list + {@link
 * StaticMcpExposurePolicy}: a connection name is "usable" only when a wired static client carries
 * it AND the exposure policy would actually offer it (stateful shared servers are hidden by
 * default - see that class's javadoc). This is what lets {@code McpRegistryService} tell "static
 * reference enabled but nothing can serve it" from "genuinely wired".
 */
@Component
final class StaticMcpServerAvailabilityAdapter implements McpStaticServerAvailability {

    private final ObjectProvider<List<McpSyncClient>> staticMcpSyncClients;
    private final StaticMcpExposurePolicy exposurePolicy;

    StaticMcpServerAvailabilityAdapter(
            ObjectProvider<List<McpSyncClient>> staticMcpSyncClients,
            StaticMcpExposurePolicy exposurePolicy) {
        this.staticMcpSyncClients = staticMcpSyncClients;
        this.exposurePolicy = exposurePolicy;
    }

    @Override
    public boolean usable(String connectionName) {
        if (connectionName == null) {
            return false;
        }
        List<McpSyncClient> clients = staticMcpSyncClients.getIfAvailable(List::of);
        for (McpSyncClient client : clients) {
            String name = StaticMcpExposurePolicy.serverNameOf(client);
            if (connectionName.equals(name) && exposurePolicy.isExposed(name)) {
                return true;
            }
        }
        return false;
    }
}
