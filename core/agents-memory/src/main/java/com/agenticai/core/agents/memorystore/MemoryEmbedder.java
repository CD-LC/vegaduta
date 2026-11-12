package com.agenticai.core.agents.memorystore;

/**
 * Turns text into the 384-dimension vector {@code memory_item.embedding} holds (the same model as
 * {@code agent_memory}). The store module cannot reach the sidecar client, so the host application
 * contributes the bean; without one, proposals are refused and recall reports itself degraded.
 */
@FunctionalInterface
public interface MemoryEmbedder {

    int DIMENSION = 384;

    float[] embed(String text);
}
