package com.agenticai.core.quality.memeval;

import java.util.List;

/**
 * Everything the scorers need from one (scenario, arm, repeat) execution. A pure value model: the
 * real runner against the platform and the in-memory reference subject both produce it.
 */
public record MemoryRun(
        String scenarioId,
        MemoryArm arm,
        int repeat,
        long seed,
        List<ProbeOutcome> outcomes,
        List<StoreItem> finalStore,
        List<TurnCost> turns) {

    public MemoryRun {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        finalStore = finalStore == null ? List.of() : List.copyOf(finalStore);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** A tool call the agent made (or tried) while answering a probe. */
    public record ToolCall(String name, String args) {}

    /**
     * A memory item injected into the probe prompt; {@code factId} is null when it matches no
     * ledger fact.
     */
    public record InjectedItem(
            String factId, String key, String text, String userId, String tenantId) {}

    /**
     * A row in the memory store. {@code flaggedConflict} marks a key the store knows holds two
     * values.
     */
    public record StoreItem(
            String factId,
            String key,
            String text,
            String userId,
            String tenantId,
            boolean flaggedConflict) {}

    /** Cost of one turn: tokens, total latency and time to first token (null when not measured). */
    public record TurnCost(
            int promptTokens, int completionTokens, double latencyMs, Double ttftMs) {}

    /**
     * One probe's observable result; {@code store} is the store as seen when the probe was asked.
     */
    public record ProbeOutcome(
            String probeId,
            String answer,
            List<ToolCall> toolCalls,
            List<InjectedItem> injected,
            List<StoreItem> store,
            TurnCost cost) {
        public ProbeOutcome {
            answer = answer == null ? "" : answer;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            injected = injected == null ? List.of() : List.copyOf(injected);
            store = store == null ? List.of() : List.copyOf(store);
        }
    }
}
