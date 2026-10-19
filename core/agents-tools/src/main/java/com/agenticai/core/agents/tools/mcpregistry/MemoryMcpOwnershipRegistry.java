package com.agenticai.core.agents.tools.mcpregistry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Default ({@code app.mcp.registry-store=memory}): single-JVM behaviour, never touches Redis. */
public final class MemoryMcpOwnershipRegistry implements McpOwnershipRegistry {

    @Override
    public String replicaId() {
        return "local";
    }

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public boolean claim(String tenant, UUID registrationId) {
        return true;
    }

    @Override
    public Optional<String> ownerOf(String tenant, UUID registrationId) {
        return Optional.empty();
    }

    @Override
    public void release(String tenant, UUID registrationId) {}

    @Override
    public void renewOwned(BiPredicate<String, UUID> stillNeeded, Duration grace) {}

    @Override
    public void registerReplica() {}

    @Override
    public List<String> liveReplicas() {
        return List.of();
    }
}
