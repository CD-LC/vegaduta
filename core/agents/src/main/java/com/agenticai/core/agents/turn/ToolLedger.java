package com.agenticai.core.agents.turn;

import java.util.List;

/**
 * The compact record of what ONE turn's tools did: tool, arguments summary, result summary, status and, for
 * a call held for a human, the approval state. It is stored with the assistant turn in the conversation
 * history ({@code ChatTurn#tools}) and replayed to the model on later turns, so a model that runs a tool in
 * one turn still knows on the next which file ids it got, which write calls it already made and which
 * action is waiting on a person - the loop runs inside Spring AI, so without this the history carried the
 * model's prose only.
 *
 * <p>Entries are written already summarised: cut to {@code app.chat.tool-ledger.*} limits and masked like
 * the tool-call log and the model-facing tool result are (credentials, and personal data when the tenant's
 * gateway policy asks for it). {@code omitted} counts calls past the per-turn limit that are not listed.
 *
 * <p>Serialised as part of the stored {@code ChatTurn}; a turn without tool calls stores {@code null}, so
 * history written before this existed (and tool-free turns) read and write exactly as they did.
 */
public record ToolLedger(List<Entry> entries, int omitted) {

    /** How one call ended, in the terms the model needs to decide whether to do it again. */
    public enum Status {
        /** The tool ran and returned. */
        OK,
        /** The tool ran and failed, or its result was withheld by a guard. */
        FAILED,
        /** A guard answered instead (policy, step budget, circuit breaker): the tool did not run. */
        NOT_RUN,
        /** Held for a human decision: the tool has not run, a later note says how it was decided. */
        AWAITING_APPROVAL,
        /** An identical durable call already ran this turn; its recorded result was returned. */
        REPLAYED,
        /**
         * An offline evaluation turn answered with a simulated result instead of running the tool (it could change
         * data or reach outside the platform): the tool did not run, and nothing was changed or sent.
         */
        SIMULATED
    }

    /**
     * One call. {@code approval} is {@code null} unless the call was held for a human.
     *
     * @param arguments the call's arguments, compacted and cut
     * @param result the tool's result (or the guard's answer, or the failure), compacted and cut
     */
    public record Entry(String tool, String arguments, String result, Status status, String approval) {}

    public static final ToolLedger EMPTY = new ToolLedger(List.of(), 0);

    public ToolLedger {
        entries = entries == null ? List.of() : List.copyOf(entries);
        omitted = Math.max(0, omitted);
    }

    /** True when nothing was recorded (no entry and no omitted call). Named so a JSON mapper sees no property. */
    public boolean none() {
        return entries.isEmpty() && omitted == 0;
    }

    /** {@code ledger} itself, or {@code null} when it records nothing: what a stored turn carries. */
    public static ToolLedger nullIfNone(ToolLedger ledger) {
        return ledger == null || ledger.none() ? null : ledger;
    }
}
