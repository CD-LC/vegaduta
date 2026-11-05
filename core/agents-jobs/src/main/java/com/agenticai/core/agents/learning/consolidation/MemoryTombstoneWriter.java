package com.agenticai.core.agents.learning.consolidation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole "forget" surface of the consolidation apply path: append one tombstone row marking a
 * fact forgotten. There is intentionally no update or delete method - a tombstone is the only mark
 * the job may leave, the fact row itself is untouched (deletion and undo are a later work package),
 * and nothing here can raise a row's trust or status.
 */
@Component
public class MemoryTombstoneWriter {

    /** Actor recorded on every machine-written tombstone. */
    public static final String ACTOR = "system:memory-consolidation";

    public static final String REASON_DECAY = "DECAY";
    public static final String REASON_QUARANTINE_TTL = "QUARANTINE_TTL";
    public static final String REASON_MERGED_INTO_PREFIX = "MERGED_INTO:";

    private final MemoryTombstoneRepository tombstones;

    public MemoryTombstoneWriter(MemoryTombstoneRepository tombstones) {
        this.tombstones = tombstones;
    }

    /** Marks {@code fact} forgotten; returns the tombstone id. The fact row is never modified. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID markForgotten(
            String subjectKey,
            ConsolidationFactSource.Fact fact,
            String reason,
            Double score,
            Instant at) {
        String r = reason == null || reason.isBlank() ? REASON_DECAY : reason;
        if (r.length() > 64) {
            r = r.substring(0, 64);
        }
        MemoryTombstone t =
                new MemoryTombstone(
                        UUID.randomUUID(),
                        subjectKey,
                        fact.scope(),
                        fact.agentId(),
                        fact.id(),
                        sha256(fact.content()),
                        r,
                        ACTOR,
                        score == null ? null : BigDecimal.valueOf(score),
                        fact.trust() == null ? null : fact.trust().name(),
                        at);
        return tombstones.save(t).getId();
    }

    private static String sha256(String content) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (content == null ? "" : content)
                                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
