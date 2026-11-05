package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.memorystore.MemoryEmbedder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@link MemoryEmbedder} of the unified memory store (ADR-0028 WP4c): the very same sidecar
 * query embedding that {@code agent_memory} uses ({@link AgentMemoryService#embedShared}), 384
 * dimensions, no new service. It shares the per-turn embedding with the legacy recall, so the user
 * message crosses to the sidecar once, not twice.
 *
 * <p>Present only when {@code app.memory.unified.enabled=true}; the store reports itself degraded
 * without an embedder. Fail closed: no {@link AgentMemoryService} (the {@code knowledge} profile is
 * off), an unavailable sidecar or a vector of the wrong size throws, and the callers (store, sink,
 * review service) refuse or degrade rather than store a malformed vector.
 */
@Component
@ConditionalOnProperty(prefix = "app.memory.unified", name = "enabled", havingValue = "true")
public class SidecarMemoryEmbedder implements MemoryEmbedder {

    private final ObjectProvider<AgentMemoryService> memory;

    public SidecarMemoryEmbedder(ObjectProvider<AgentMemoryService> memory) {
        this.memory = memory;
    }

    @Override
    public float[] embed(String text) {
        AgentMemoryService service = memory.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("no embedding service available");
        }
        float[] vector = service.embedShared(text);
        if (vector == null || vector.length != DIMENSION) {
            throw new IllegalStateException(
                    "embedding has "
                            + (vector == null ? 0 : vector.length)
                            + " dimensions, expected "
                            + DIMENSION);
        }
        return vector;
    }
}
