package com.agenticai.core.guardrails;

import java.util.UUID;

/**
 * Tells the guardrail judges which agent a check is for, so an agent-scope prompt override
 * ({@code judge.injection}, {@code judge.tool_verifier}, the refusal texts) applies to the
 * call that is running and not only the tenant's.
 *
 * <p>{@link GuardrailService#sanitizeToolOutput} and {@link ToolCallVerifierService#verify} are public
 * seams with many implementations and mocks; they carry no agent. Rather than widening them, the caller
 * that knows the agent ({@link GuardedToolCallback}) opens this scope around the call, on the calling
 * thread, and the implementation reads {@link #currentAgent()}. The tenant needs no such help: the
 * prompt resolver reads it from the calling thread ({@code TenantContext}).
 *
 * <pre>{@code
 * try (var scope = GuardrailPromptScope.open(agentId)) {
 *     judge.verify(...);
 * }
 * }</pre>
 */
public final class GuardrailPromptScope implements AutoCloseable {

    private static final ThreadLocal<UUID> AGENT = new ThreadLocal<>();

    private final UUID previous;
    private boolean closed;

    private GuardrailPromptScope(UUID previous) {
        this.previous = previous;
    }

    /** Makes {@code agentId} (may be null: no agent-scope override) the current agent of this thread until closed. */
    public static GuardrailPromptScope open(UUID agentId) {
        GuardrailPromptScope scope = new GuardrailPromptScope(AGENT.get());
        if (agentId == null) {
            AGENT.remove();
        } else {
            AGENT.set(agentId);
        }
        return scope;
    }

    /**
     * As {@link #open} but an unknown agent leaves the current scope of this thread alone instead of clearing
     * it: for a callee that is handed an optional agent id and must not drop an agent its caller already set.
     */
    public static GuardrailPromptScope openIfKnown(UUID agentId) {
        return agentId == null ? new GuardrailPromptScope(AGENT.get()) : open(agentId);
    }

    /** The agent a guardrail check on this thread is for, or null. */
    public static UUID currentAgent() {
        return AGENT.get();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previous == null) {
            AGENT.remove();
        } else {
            AGENT.set(previous);
        }
    }
}
