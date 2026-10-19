package com.agenticai.core.agents.tools.classification;

/**
 * Whether <b>repeating</b> a tool call is safe - the replay half of a {@link ToolClassification}.
 *
 * <p><b>Why this exists.</b> Retry, resume-after-crash, speculative execution and response caching
 * are all the same operation ("call this again") and all four are correct exactly when this field
 * says they are. Today that judgement is made implicitly, per call site, by whoever wrote the retry
 * loop. A durable agent run that re-executes a step after a lease lapse has no way to know whether
 * it is re-reading a row or sending a second invoice, which means the only safe retry policy is
 * "don't", and the only shipped alternative is "retry and hope".
 *
 * <p><b>The two mistakes this taxonomy is shaped to prevent</b>, both of which are reliably made
 * the first time anyone classifies their own tools:
 *
 * <ol>
 *   <li><i>Calling a non-deterministic read {@link #PURE}.</i> "Get the current timestamp" has no
 *       side effect at all and is still not pure, because a replay returns a different answer and
 *       the run silently diverges from the journal. Every model invocation is in this category too
 *       - kernels are not batch-invariant, so the same prompt is not guaranteed to produce the same
 *       tokens, and an LLM call's bytes must therefore be recorded rather than re-derived.
 *   <li><i>Calling an externally-visible effect {@link #SIDE_EFFECT}.</i> "Send email" is not a
 *       side effect you can absorb by retrying - the second attempt sends a second email to a real
 *       person. That is {@link #DURABLE}, and a replay of it must return the <b>journaled
 *       response</b> and must not reach the provider at all.
 * </ol>
 *
 * <p>Both of those are why this is a three-valued enum and not an {@code idempotent} boolean.
 *
 * @see ToolClassification#requiresIdempotencyKey()
 */
public enum ToolDeterminism {

    /**
     * Same arguments produce the same result, and calling it changes nothing observable. Free to
     * retry, cache, replay, run speculatively, or skip entirely if the result is already known.
     *
     * <p>The bar is genuinely high, and it is worth being blunt about how high: as of this class
     * being written <b>no first-party tool in this codebase qualifies</b>, and {@link
     * BuiltInToolClassifications} declares none. Every candidate fails for a concrete reason. A
     * knowledge search reads a corpus that changes. A translation or transliteration tool is a
     * hosted model call whose output is not byte-stable. A code-graph statistics tool reads an
     * index that is rebuilt. Listing a tenant's agents reads mutable rows.
     *
     * <p>That emptiness is information, not an omission - it is the honest answer, and it is
     * exactly why a durable-execution layer for this platform has to journal tool responses rather
     * than assume it can re-derive them. Reserve {@code PURE} for a pure function of its arguments:
     * a unit conversion, a hash, a date-format transformation. If you are about to declare
     * something {@code PURE}, check that it touches neither the clock, the network, a database, nor
     * a model.
     */
    PURE,

    /**
     * Repeating the call is <b>recoverable</b>: either it has no external effect but is not
     * deterministic (a read of mutable state, a clock read, a model call), or its effect is
     * internally absorbable (an upsert, a cache warm, a counter that is reconciled later).
     *
     * <p>This is the honest default for most reads, which is the point most often missed. A read
     * classified here is not being accused of mutating anything; it is being marked as
     * <i>non-replayable from scratch</i>, so a resumed run must return the recorded bytes instead
     * of calling again, or accept that the run's reasoning now rests on different facts than the
     * reasoning it is resuming.
     *
     * <p>Requires an idempotency key before any retry or replay, same as {@link #DURABLE} - the key
     * is what lets a replay be recognised as a replay. The difference from {@code DURABLE} is what
     * happens if the key is missing or the dedupe misses: here you get duplicated internal work,
     * there you get a duplicate in the real world.
     */
    SIDE_EFFECT,

    /**
     * The effect is durably recorded and externally visible, and <b>cannot be absorbed by this
     * platform</b>: a message sent, a file uploaded, a payment link created, a video published, a
     * workspace deleted, a shell command run.
     *
     * <p>Enforced (opt-in, {@code app.tools.effect-journal.enabled}) by {@code
     * com.agenticai.core.agents.tools.EffectJournal} via {@code LoggingToolCallback}: an identical
     * call within the TTL returns the recorded result without reaching the provider.
     *
     * <p>Operational contract, which is stricter than a retry policy: a replay must be served
     * entirely from the effect journal ({@code agent_effect(run_id, idempotency_key UNIQUE, tool,
     * request_hash, response)} in the durable-execution design) and must not reach the provider.
     * "Retry with backoff" is not an acceptable implementation for a {@code DURABLE} tool, because
     * the failure mode of a timed-out send is indistinguishable from the failure mode of a
     * succeeded send whose acknowledgement was lost - and one of those two readings sends the
     * customer a second invoice.
     *
     * <p>Note this is about the <i>effect</i>, not about reversibility by a human. Deleting a
     * workspace is {@code DURABLE}; so is creating one, even though creating one is easy to undo.
     * Reversibility is the separate {@link ToolClassification#irreversible()} flag, and it is the
     * one that drives human confirmation.
     */
    DURABLE;

    /**
     * True when a retry, a resume or a replay of this tool must carry an idempotency key - i.e. for
     * everything except {@link #PURE}.
     *
     * <p>Phrased as "requires a key" rather than "is idempotent" because the key is the artefact a
     * caller has to produce. The durable-execution design computes it as {@code hash(runId, nodeId,
     * attemptIndex, canonicalRequest)}; the contract this predicate states is simply that a {@code
     * SIDE_EFFECT} or {@code DURABLE} tool may not be re-invoked without one.
     */
    public boolean requiresIdempotencyKey() {
        return switch (this) {
            case PURE -> false;
            case SIDE_EFFECT, DURABLE -> true;
        };
    }

    /**
     * True when a replay must be served from the recorded response and must not reach the
     * underlying provider at all.
     *
     * <p>Only {@link #DURABLE}. For {@link #SIDE_EFFECT} a re-call is permitted (it is merely
     * wasteful and possibly divergent); for {@code DURABLE} a re-call is a real-world duplicate and
     * is therefore a correctness bug, not an inefficiency.
     */
    public boolean replayMustUseJournaledResponse() {
        return switch (this) {
            case PURE, SIDE_EFFECT -> false;
            case DURABLE -> true;
        };
    }
}
