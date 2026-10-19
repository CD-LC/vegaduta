package com.agenticai.core.agents.learning.review;

import java.time.Instant;
import java.util.UUID;

/**
 * One remembered fact as the daily review sees it. {@code scope} is {@code "agent"} (private to {@code agentId})
 * or {@code "shared"} (tenant-wide, {@code agentId} is only the agent that wrote it and is not part of the tier).
 * {@code lastActivity} is the later of when it was saved and when it was last recalled.
 */
public record MemoryFactRef(
        String scope, UUID id, UUID agentId, String content, Instant createdAt, Instant lastActivity) {

    public static final String AGENT = "agent";
    public static final String SHARED = "shared";

    /** Facts only ever merge or drop within one tier: the same agent's, or the shared ones. */
    String tier() {
        return AGENT.equals(scope) ? AGENT + ":" + agentId : SHARED;
    }
}
