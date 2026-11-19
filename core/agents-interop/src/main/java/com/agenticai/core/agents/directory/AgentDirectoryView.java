package com.agenticai.core.agents.directory;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an {@link AgentDirectoryEntry} handed to the controller so the JPA entity never
 * leaves the service layer. JSON columns stay as raw strings; the controller parses them.
 */
public record AgentDirectoryView(
        UUID agentId,
        String displayName,
        String description,
        String capabilities,
        String homepageUrl,
        String status,
        Instant publishedAt,
        Instant updatedAt,
        String cardSnapshot,
        String manifestSnapshot) {

    static AgentDirectoryView of(AgentDirectoryEntry e) {
        return new AgentDirectoryView(
                e.getAgentId(),
                e.getDisplayName(),
                e.getDescription(),
                e.getCapabilities(),
                e.getHomepageUrl(),
                e.getStatus().name(),
                e.getPublishedAt(),
                e.getUpdatedAt(),
                e.getCardSnapshot(),
                e.getManifestSnapshot());
    }
}
