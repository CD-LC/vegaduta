package com.agenticai.core.agents.turnjournal;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only journal entry of an agent turn. {@code payloadHash} is the SHA-256 of the
 * underlying payload (prompt, tool arguments, tool result, answer) which is NEVER stored;
 * {@code summary} is a short redacted, structural description. {@code hash} chains the event to
 * {@code prevHash} (see {@link TurnHashChain}).
 */
public record TurnEvent(
        UUID turnId,
        UUID agentId,
        int seq,
        TurnEventType type,
        String payloadHash,
        String summary,
        String prevHash,
        String hash,
        Instant createdAt) {}
