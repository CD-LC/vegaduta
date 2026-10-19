package com.agenticai.core.agents.turnjournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Hash arithmetic of the turn journal: SHA-256 hex, genesis hash, chain computation and check. */
public final class TurnHashChain {

    /** {@code prevHash} of seq 0. */
    public static final String GENESIS = "0".repeat(64);

    private TurnHashChain() {}

    /** Result of {@link #verify}; {@code brokenAtSeq} is -1 when the chain is intact. */
    public record Verification(boolean intact, int brokenAtSeq, String reason) {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String eventHash(
            String prevHash,
            UUID turnId,
            UUID agentId,
            int seq,
            TurnEventType type,
            String payloadHash,
            String summary,
            Instant createdAt) {
        return sha256(
                String.join(
                        "|",
                        prevHash,
                        String.valueOf(turnId),
                        String.valueOf(agentId),
                        Integer.toString(seq),
                        type.name(),
                        payloadHash,
                        summary,
                        Long.toString(createdAt.toEpochMilli())));
    }

    /** Builds the next event after {@code previous} (null for the first event of a turn). */
    public static TurnEvent next(
            TurnEvent previous,
            UUID agentId,
            UUID turnId,
            TurnEventType type,
            String payloadHash,
            String summary) {
        int seq = previous == null ? 0 : previous.seq() + 1;
        String prev = previous == null ? GENESIS : previous.hash();
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        String hash = eventHash(prev, turnId, agentId, seq, type, payloadHash, summary, now);
        return new TurnEvent(turnId, agentId, seq, type, payloadHash, summary, prev, hash, now);
    }

    /** Recomputes every hash; detects an edited, removed, inserted or reordered event. */
    public static Verification verify(List<TurnEvent> events) {
        String expectedPrev = GENESIS;
        int expectedSeq = 0;
        for (TurnEvent e : events) {
            if (e.seq() != expectedSeq) {
                return new Verification(false, e.seq(), "sequence gap or reorder");
            }
            if (!expectedPrev.equals(e.prevHash())) {
                return new Verification(false, e.seq(), "prevHash does not match previous event");
            }
            String recomputed =
                    eventHash(
                            e.prevHash(),
                            e.turnId(),
                            e.agentId(),
                            e.seq(),
                            e.type(),
                            e.payloadHash(),
                            e.summary(),
                            e.createdAt());
            if (!recomputed.equals(e.hash())) {
                return new Verification(false, e.seq(), "event content does not match its hash");
            }
            expectedPrev = e.hash();
            expectedSeq++;
        }
        return new Verification(true, -1, "ok");
    }
}
