package com.agenticai.core.quality.dataset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Computes {@code eval_dataset.content_hash} (V343): a SHA-256 over the
 * dataset's evaluation-relevant content - the pass bar, the tool-execution
 * flag, and every case's position/message/expectations in canonical replay
 * order ({@code position, createdAt} - the same ordering the runner replays
 * and the results list returns, so reordering cases changes the hash the
 * same way changing one does).
 *
 * <h2>What is deliberately NOT covered</h2>
 * {@code name} and {@code description} are human-facing labels: renaming a
 * dataset must not invalidate the runs that tested its cases. The hash
 * exists to answer "did the content a run replayed change since" - nothing
 * else.
 *
 * <h2>Canonical form</h2>
 * {@code v1} {@link #FS} threshold({@code %.3f}) {@link #FS} tools(0/1),
 * then per case {@link #RS} position {@link #FS} sha256(userMessage)
 * {@link #FS} sha256(expectedOutput-or-NUL) {@link #FS}
 * sha256(expectedTools-or-NUL). Each free-text field is pre-hashed so a
 * user message containing a separator byte can never collide with a
 * boundary. Deterministic, single-implementation (Java only - the column
 * backfills lazily, never in SQL, which is why no dual implementation can
 * drift).
 */
public final class DatasetContentHash {

    private static final char FS = '\u001F';
    private static final char RS = '\u001E';
    private static final char NUL = '\u0000';

    private DatasetContentHash() {
    }

    /** The dataset's current content hash - a pure function of its rows. */
    public static String of(EvalDataset dataset, List<EvalDatasetCase> cases) {
        StringBuilder sb = new StringBuilder("v1");
        sb.append(FS).append(String.format(Locale.ROOT, "%.3f", dataset.getPassThreshold()));
        sb.append(FS).append(dataset.isToolExecutionEnabled() ? '1' : '0');
        for (EvalDatasetCase c : cases) {
            sb.append(RS)
                .append(c.getPosition())
                .append(FS).append(fieldHash(c.getUserMessage()))
                .append(FS).append(fieldHash(c.getExpectedOutput()))
                .append(FS).append(fieldHash(c.getExpectedTools()));
            // V375: only when a code grader is set, so every pre-V375 dataset
            // keeps the exact hash its existing runs and gate bindings carry.
            if (c.getGraderSpec() != null) {
                sb.append(FS).append('g').append(fieldHash(c.getGraderSpec()));
            }
        }
        return sha256Hex(sb.toString());
    }

    private static String fieldHash(String value) {
        // NUL marker for absent fields so null never collides with "" -
        // an expectedOutput of "" and a missing expectedOutput are different
        // assertions and must hash differently.
        return sha256Hex(value == null ? String.valueOf(NUL) : value);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM - unreachable.
            throw new IllegalStateException(e);
        }
    }
}
