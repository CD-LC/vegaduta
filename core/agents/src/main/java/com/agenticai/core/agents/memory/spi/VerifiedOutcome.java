package com.agenticai.core.agents.memory.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One piece of evidence about how an agent did on a class of task, in the closed vocabulary of
 * ADR-0028 section 3.
 *
 * <p>This type is the only way into the competence counters, and it has no field, and no {@link
 * Signal} value, for the model's own claim of success or for a sampled LLM judge: those cannot be
 * expressed, so they cannot be counted. Every signal is something the platform observed or a person
 * decided (tool status, an approval decision, an explicit rating), or {@link Signal#UNKNOWN}, which
 * is counted apart and never as success.
 *
 * <p>{@code taskClass} is a closed platform vocabulary (tool family, skill id, workflow id), never
 * model or user free text: it must match {@code [a-z0-9][a-z0-9_.:+-]*}; text that does not is
 * rejected, and an over-long (but otherwise valid) class is shortened deterministically to {@value
 * #MAX_TASK_CLASS_CHARS} characters with a hash suffix.
 */
public record VerifiedOutcome(UUID agentId, String taskClass, Signal signal) {

    /** Longest stored task class (the column is VARCHAR(96)). */
    public static final int MAX_TASK_CLASS_CHARS = 96;

    private static final Pattern CLASS = Pattern.compile("[a-z0-9][a-z0-9_.:+-]*");

    /** What was observed or decided. Nothing here is a model's opinion. */
    public enum Signal {
        /** A tool call finished and the platform recorded no error for it. */
        TOOL_SUCCESS,
        /**
         * A tool call, or the turn itself, failed as the platform observed it (error status, failed
         * validation).
         */
        TOOL_ERROR,
        /** A person approved the action. */
        APPROVAL_GRANTED,
        /** A person denied the action. */
        APPROVAL_DENIED,
        /** A person explicitly rated the outcome as good. */
        HUMAN_ACCEPTED,
        /** A person explicitly rated the outcome as bad. */
        HUMAN_REJECTED,
        /** No verified signal either way. Counted apart; never success. */
        UNKNOWN
    }

    public VerifiedOutcome {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (signal == null) {
            throw new IllegalArgumentException("a verified signal is required");
        }
        taskClass = normalise(taskClass);
    }

    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("taskClass is required");
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (!CLASS.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "taskClass must be a platform identifier, not free text");
        }
        if (s.length() <= MAX_TASK_CLASS_CHARS) {
            return s;
        }
        return s.substring(0, MAX_TASK_CLASS_CHARS - 9) + "~" + shortHash(s);
    }

    private static String shortHash(String s) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
