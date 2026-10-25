package com.agenticai.core.agents.dto;

import java.util.Set;
import java.util.UUID;

/**
 * The agent's knowledge-WRITE posture ({@code add_to_knowledge_base}): the opt-in flag plus the
 * collection grant list - deny-all when empty. Returned by the {@code knowledge-write} and {@code
 * knowledge-write-access} endpoints instead of {@link AgentResponse}, which deliberately does not
 * carry these fields.
 */
public record AgentKnowledgeWriteAccessResponse(
        boolean knowledgeWriteEnabled, Set<UUID> allowedKnowledgeWriteCollectionIds) {}
