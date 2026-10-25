package com.agenticai.core.agents.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Migration V198 - the write grant half of {@code add_to_knowledge_base}. Empty/null {@code
 * allowedKnowledgeWriteCollectionIds} clears every grant back to DENY-ALL - the OPPOSITE of {@link
 * AgentKnowledgeAccessUpdateRequest}'s "empty means unrestricted" convention, matching {@code
 * Agent.allowedKnowledgeWriteCollectionIds}' javadoc exactly.
 */
public record AgentKnowledgeWriteAccessUpdateRequest(
        Set<UUID> allowedKnowledgeWriteCollectionIds) {}
