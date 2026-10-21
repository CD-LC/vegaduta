package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for the conversation tree. The service logic talks only to this, so it is tested
 * against an in-memory fake; {@link JpaChatBranchStore} is the production implementation.
 */
public interface ChatBranchStore {

    /**
     * Per-session state. {@code activeLeafId} null = empty path. {@code reuseUserMessageId} is the
     * one-shot "map the next re-appended user message onto this node" hint set by regenerate.
     */
    record State(UUID agentId, String sessionId, String ownerSubject, UUID activeLeafId, UUID reuseUserMessageId) {
    }

    Optional<State> findState(UUID agentId, String sessionId);

    /** Every node of the session, oldest first. */
    List<ChatBranchTree.Node> findNodes(UUID agentId, String sessionId);

    /**
     * Upserts the state and inserts {@code newNodes} atomically. A concurrent writer that took the
     * same sibling slot makes this throw (a unique-constraint violation); callers re-read and retry.
     */
    void save(State state, List<ChatBranchTree.Node> newNodes);

    /**
     * Retention: deletes every session (state row and all its nodes) last touched before {@code cutoff}.
     * Whole sessions only, never part of a tree. Returns the number of sessions removed.
     */
    int deleteSessionsIdleBefore(Instant cutoff);

    /** Erasure: deletes every session (state row and all its nodes) bound to {@code subject}. Returns the number of sessions removed. */
    int deleteSessionsOwnedBy(String subject);

    /**
     * One session of a subject's thread list ({@code GET /api/chat/threads}). {@code firstUserMessage}
     * is the stored content of the session's earliest USER node (null when it has none), untrimmed:
     * the service cuts it to a title.
     */
    record Thread(UUID agentId, String sessionId, String firstUserMessage, Instant createdAt, Instant updatedAt) {
    }

    /**
     * The sessions bound to {@code subject}, newest {@code updatedAt} first, at most {@code limit} of them,
     * only those last touched strictly before {@code before} when it is given. The first user message of
     * every returned session is fetched in one query for the page, never one per row.
     */
    List<Thread> findThreadsOwnedBy(String subject, Instant before, int limit);

    /**
     * Deletes one session (state row and all its nodes) if, and only if, it is bound to {@code subject}.
     * Returns false when there is no such session or it belongs to someone else - the caller answers
     * both the same way.
     */
    boolean deleteSessionOwnedBy(UUID agentId, String sessionId, String subject);
}
