package com.agenticai.core.agents.turn;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the memory recall put into ONE chat turn's prompt, as ids and counts only (never the text), shared by
 * reference between the stage that recalls ({@code ChatMemoryRecall} / the prompt assembler) and the stages that
 * report it (the turn journal, an eval harness reading the turn's context). Held by {@link TurnContext}; one
 * instance per turn, like {@link TurnToolActivity}.
 *
 * <p>A turn that never reached the recall stage has {@link #trace()} {@code null}; one that did has a {@link Trace}
 * even when nothing was injected or recall was switched off for the agent ({@code memory.recall.enabled}), so
 * "recalled nothing" and "did not look" can be told apart.
 */
public final class TurnMemoryRecall {

    /**
     * The recall outcome of one turn.
     *
     * @param enabled false when recall was switched off for the turn's agent, so nothing was looked up
     * @param privateFactIds ids of the agent's own long-term memories injected
     * @param sharedFactIds ids of the tenant-shared memories injected
     * @param blockLabels labels (persona, user_profile, project_notes, ...) of the always-in-context memory blocks
     *     injected; labels are names, never block text
     * @param approxTokens estimated tokens of the whole injected memory text (blocks and facts), the same estimate
     *     the history window uses; not a tokenizer count
     */
    public record Trace(
            boolean enabled,
            List<UUID> privateFactIds,
            List<UUID> sharedFactIds,
            List<String> blockLabels,
            long approxTokens) {

        public Trace {
            privateFactIds = privateFactIds == null ? List.of() : List.copyOf(privateFactIds);
            sharedFactIds = sharedFactIds == null ? List.of() : List.copyOf(sharedFactIds);
            blockLabels = blockLabels == null ? List.of() : List.copyOf(blockLabels);
        }

        /** The trace of a turn whose agent has memory recall switched off. */
        public static Trace disabled() {
            return new Trace(false, List.of(), List.of(), List.of(), 0);
        }

        public int privateFacts() {
            return privateFactIds.size();
        }

        public int sharedFacts() {
            return sharedFactIds.size();
        }

        public int blocks() {
            return blockLabels.size();
        }

        /** Every injected fact id, private first. */
        public List<UUID> factIds() {
            List<UUID> all = new ArrayList<>(privateFactIds);
            all.addAll(sharedFactIds);
            return List.copyOf(all);
        }
    }

    private volatile Trace trace;

    /** The latest recall of this turn, or null before the recall stage ran. */
    public Trace trace() {
        return trace;
    }

    /** Records the recall outcome; a re-run of the stage (a retried attempt) replaces the earlier one. */
    public void record(Trace recalled) {
        this.trace = recalled;
    }
}
