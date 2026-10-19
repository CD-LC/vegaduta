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
}
