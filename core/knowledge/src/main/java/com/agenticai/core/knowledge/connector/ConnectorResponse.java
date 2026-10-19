package com.agenticai.core.knowledge.connector;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API view of a connector row. {@code config} is the REDACTED map from
 * {@link KnowledgeConnectorService#redactedConfig} - secret values never
 * leave the server.
 */
public record ConnectorResponse(
    UUID id,
    UUID collectionId,
    String type,
    String name,
    Map<String, String> config,
    boolean enabled,
    Instant lastSyncedAt,
    String lastError,
    Instant createdAt
) {
    public static ConnectorResponse from(KnowledgeConnector c, Map<String, String> redactedConfig) {
        return new ConnectorResponse(
            c.getId(), c.getCollectionId(), c.getType().name(), c.getName(), redactedConfig,
            c.isEnabled(), c.getLastSyncedAt(), c.getLastError(), c.getCreatedAt());
    }
}
