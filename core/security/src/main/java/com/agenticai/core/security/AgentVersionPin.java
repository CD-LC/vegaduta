package com.agenticai.core.security;

import java.util.UUID;

/**
 * The agent-config version a run/turn resolved at start (C1 versioning) - a plain {@link
 * ThreadLocal} set by the entry point that loaded the agent ({@code AgentChatService}, {@code
 * QuickTaskService}) and read by the persistence writers downstream ({@code LlmMetricsAdvisor} ->
 * {@code llm_call_log.agent_version_id}, {@code ToolCallLogService} -> {@code
 * tool_call_log.agent_version_id}) so "which configuration produced this call" is answerable
 * without threading an extra parameter through every advisor and tool-call signature in between.
 *
 * <p>Same trade-offs {@link TenantContext} already documents: same-thread reads only, never
 * propagated automatically to worker/reactor threads. For the streaming chat path that means the
 * pin is read where it matters - advisor params are built on the request thread while the pin is
 * set ({@code LlmMetricsAdvisor#attributeTo} captures it into {@code AGENT_VERSION_ID_PARAM}), and
 * the few completion callbacks that land on other threads fall back to a null stamp, which the
 * column tolerates (nullable by design - "unknown" is an honest answer, a wrong version id is not).
 *
 * <p>{@link TenantScope} (the common wrapper for tenant-scoped async work) carries the capturing
 * thread's pin to the worker and restores the worker's previous pin afterwards, so a pin set during
 * or left over from other work on a pooled thread cannot stamp a wrong version id.
 *
 * <p>Callers must {@link #clear()} in a finally once the synchronous request-thread work is done -
 * the same leak rule as TenantContext on a pooled thread.
 */
public final class AgentVersionPin {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private AgentVersionPin() {}

    /**
     * The pinned {@code agent_version.id} for this thread's turn, or null when the run
     * predates/didn't resolve one.
     */
    public static UUID current() {
        return CURRENT.get();
    }

    public static void set(UUID agentVersionId) {
        if (agentVersionId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(agentVersionId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
