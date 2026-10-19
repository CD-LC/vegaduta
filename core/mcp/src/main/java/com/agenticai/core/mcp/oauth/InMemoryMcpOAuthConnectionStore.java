package com.agenticai.core.mcp.oauth;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Non-durable store: connections are lost on restart and not shared across
 * replicas. Production uses {@link JpaMcpOAuthConnectionStore} (table
 * {@code mcp_oauth_connection}, V424), which is {@code @Primary} and therefore
 * wins injection; this bean is kept deliberately as the DB-free implementation
 * the unit tests construct directly.
 * See docs/architecture/ADR-mcp-generic-oauth.md.
 */
@Component
public class InMemoryMcpOAuthConnectionStore implements McpOAuthConnectionStore {

    private final ConcurrentHashMap<String, McpOAuthConnection> rows = new ConcurrentHashMap<>();

    private static String key(String tenantId, String resourceUrl) {
        return tenantId + "\n" + resourceUrl;
    }

    @Override
    public Optional<McpOAuthConnection> find(String tenantId, String resourceUrl) {
        return Optional.ofNullable(rows.get(key(tenantId, resourceUrl)));
    }

    @Override
    public void save(McpOAuthConnection connection) {
        rows.put(key(connection.tenantId(), connection.resourceUrl()), connection);
    }

    @Override
    public void delete(String tenantId, String resourceUrl) {
        rows.remove(key(tenantId, resourceUrl));
    }
}
