package com.agenticai.core.agents.resumable;

import java.util.List;
import java.util.Optional;

/**
 * Bounded, TTL'd, per-turn event buffer behind resumable chat streams
 * ({@code app.chat.resumable-streams.enabled}). One buffer entry is exactly one
 * chunk the chat stream emitted (a token, a step-event line, a keepalive), so a
 * client that lost its connection can ask for "everything after event N" and get
 * back byte-for-byte what it missed.
 *
 * <p>Ids are monotonic per turn, start at 1 and have no holes. The buffer is
 * bounded: once more than {@code buffer-max-events} entries exist the OLDEST are
 * dropped, and a reader whose cursor points before the oldest retained entry is
 * told so ({@link ReadResult#gap()}) rather than being handed a silently
 * incomplete replay.
 *
 * <p>Implementations are used by exactly one producer per turn (the detached
 * subscription in {@link ResumableChatStreamService}) and any number of readers.
 */
public interface ResumableStreamBuffer {

    /** Who a turn belongs to. Every read is authorised against this, never against the request body. */
    record TurnMeta(
        String turnId,
        String tenantId,
        String ownerSubject,
        String agentId,
        String sessionId,
        long startedAtMs
    ) {
    }

    /**
     * One buffered event. {@code terminal} marks the last entry of a turn: its
     * {@code payload} is then the end status ({@code complete}, {@code error} or
     * {@code detach-timeout}), not stream text.
     */
    record Entry(long id, String payload, boolean terminal) {
    }

    /**
     * What a read found.
     *
     * @param found   false when the turn is unknown or its buffer expired
     * @param gap     true when events after the caller's cursor were already dropped (or lost):
     *                the replay cannot be complete, so the caller must not pretend it is
     * @param entries entries with {@code id > after}, ascending; empty when nothing new
     */
    record ReadResult(boolean found, boolean gap, List<Entry> entries) {

        public static ReadResult notFound() {
            return new ReadResult(false, false, List.of());
        }

        public static ReadResult gapped() {
            return new ReadResult(true, true, List.of());
        }

        public static ReadResult of(List<Entry> entries) {
            return new ReadResult(true, false, entries);
        }
    }

    /** Registers a turn. The first {@link #append} gets id 1. Re-opening an existing id is a no-op. */
    void open(TurnMeta meta);

    /**
     * Appends one data entry.
     *
     * @return the assigned id, or -1 when the turn is unknown or already finished
     */
    long append(String turnId, String payload);

    /**
     * Appends the terminal entry (at most once per turn) and starts the post-completion TTL.
     *
     * @return the terminal entry's id, or -1 when the turn is unknown
     */
    long finish(String turnId, String status);

    /**
     * Empty when the turn never existed, its buffer expired, or it belongs to another tenant: the tenant
     * is part of the lookup (and of every Redis key), so a turn id from tenant A is worthless to tenant B.
     */
    Optional<TurnMeta> meta(String tenantId, String turnId);

    /** Entries with {@code id > afterId}. A negative cursor reads from the start. Another tenant's turn reads as not found. */
    ReadResult read(String tenantId, String turnId, long afterId);
}
