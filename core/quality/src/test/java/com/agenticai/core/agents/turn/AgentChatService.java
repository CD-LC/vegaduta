package com.agenticai.core.agents.turn;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;

/** Test-only stand-in with the signatures ReflectiveAgentChatTurnReplayer binds to. */
public class AgentChatService {
    public record StructuredAgentInput(
            String query, Map<String, Object> contextData, List<UUID> collections, boolean approval) {}

    public Flux<String> chatTextOnly(UUID agentId, String sessionId, String subject, String message) {
        // A single-element Flux makes collectList() return the package-private MonoCallable that broke reflection
        return Flux.just("hi " + message);
    }

    public Object chatWithParams(
            UUID agentId, String sessionId, String subject, StructuredAgentInput in, String x, Duration t) {
        return null;
    }
}
