package com.agenticai.core.agents.memorystore;

/**
 * Who performed a state change, recorded in {@code memory_event}. Review operations (approve,
 * reject, edit, delete) require a {@link Type#HUMAN} actor with a reference (the authenticated
 * principal); the services refuse every other type.
 */
public record MemoryActor(Type type, String ref) {

    public enum Type {
        HUMAN,
        AGENT,
        SYSTEM
    }

    public MemoryActor {
        if (type == null) {
            throw new IllegalArgumentException("actor type is required");
        }
        ref = ref == null || ref.isBlank() ? null : (ref.length() > 255 ? ref.substring(0, 255) : ref);
    }

    public static MemoryActor human(String principal) {
        return new MemoryActor(Type.HUMAN, principal);
    }

    public static MemoryActor agent(String ref) {
        return new MemoryActor(Type.AGENT, ref);
    }

    public static MemoryActor system(String ref) {
        return new MemoryActor(Type.SYSTEM, ref);
    }

    /** An authenticated person: HUMAN with a non-blank principal. */
    public boolean isIdentifiedHuman() {
        return type == Type.HUMAN && ref != null;
    }
}
