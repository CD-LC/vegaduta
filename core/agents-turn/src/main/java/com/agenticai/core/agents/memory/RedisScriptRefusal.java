package com.agenticai.core.agents.memory;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.RedisSystemException;

/**
 * Tells "Redis refused to run the script" apart from every other failure, and remembers a refusal
 * for {@link #RETRY_AFTER} so callers do not pay a failed call on every use.
 *
 * <p>A script is atomic and may already have run when a call fails in a way that leaves the outcome
 * unknown (a command timeout, a dropped connection). Replaying the write in that case duplicates
 * it. The only failures it is safe to fall back on are the ones Redis raises BEFORE executing
 * anything: scripting disabled, the ACL denying EVAL/EVALSHA, or the keys not hashing to one
 * cluster slot.
 */
public final class RedisScriptRefusal {

    /** How long a caller skips a script after Redis refused it. */
    public static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    private final AtomicLong skipUntilNanos = new AtomicLong(System.nanoTime() - 1);

    public RedisScriptRefusal() {}

    /** True while a recent refusal says to go straight to the fallback. */
    public boolean skipScript() {
        return System.nanoTime() - skipUntilNanos.get() < 0;
    }

    /**
     * Rethrows {@code e} unless it is a refusal; a refusal is remembered ({@link #skipScript()} is
     * true for {@link #RETRY_AFTER}) and the caller falls back. A timeout or dropped connection may
     * have happened AFTER the script ran, so replaying the write could duplicate it.
     */
    public void rethrowUnlessRefused(RuntimeException e) {
        if (!isRefusedBeforeRunning(e)) {
            throw e;
        }
        skipUntilNanos.set(System.nanoTime() + RETRY_AFTER.toNanos());
    }

    private static final String[] MARKERS = {
        "noperm", "crossslot", "unknown command", "scripting disabled", "scripting is disabled"
    };

    /**
     * True only for a {@link RedisSystemException} (a server error reply) whose cause chain carries
     * one of the pre-execution refusal markers. Timeouts ({@code QueryTimeoutException}),
     * connection failures and Lua runtime errors are not refusals.
     */
    public static boolean isRefusedBeforeRunning(RuntimeException e) {
        if (!(e instanceof RedisSystemException)) {
            return false;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null) {
                String lower = m.toLowerCase(Locale.ROOT);
                for (String marker : MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
