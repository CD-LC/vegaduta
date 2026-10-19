package com.agenticai.core.agents.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Knowledge Stage F1. Empty/null {@code allowedKnowledgeCollectionIds}
 * clears the restriction (back to "no restriction, every collection this
 * tenant has is searchable") - matches
 * {@code Agent.allowedKnowledgeCollectionIds}' semantics exactly, same
 * shape as {@link AgentToolAccessUpdateRequest}.
 */
public record AgentKnowledgeAccessUpdateRequest(Set<UUID> allowedKnowledgeCollectionIds) {
}
