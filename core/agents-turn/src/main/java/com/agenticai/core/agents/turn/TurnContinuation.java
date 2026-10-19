package com.agenticai.core.agents.turn;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a finished model reply is really a stopped task: the model wrote what it is
 * about to do ("Let me create the pom.xml:") and then returned text with no tool call, which ends
 * the tool loop. Small local models do this routinely; the turn then looks finished to the
 * framework while the work is not. {@link FastStreamTurn} uses {@link #looksUnfinished} to re-prompt
 * the model instead of handing the half-done reply back to the user.
 *
 * <p>Deliberately conservative: a reply that asks the user something, waits for an approval, or
 * reads like a summary is never treated as unfinished.
 */
final class TurnContinuation {

    static final String NUDGE =
            "You stopped before the task was finished: your last message described a next step but"
                    + " did not do it. Carry on now. Do not describe or announce what you will do -"
                    + " call the tools and do it. Use only the tools you were given. When every part"
                    + " of the original request is actually done, reply with a short summary of what"
                    + " was done.";

    private static final int TAIL_CHARS = 400;

    private static final Pattern INTENT =
            Pattern.compile(
                    "\\b(let me|let's|i'll|i will|i am going to|i'm going to|i need to|i should|"
                            + "next,? i|now i|now let|going to (create|write|run|check|add|build|"
                            + "install|update|fix))\\b");

    private static final Pattern DONE =
            Pattern.compile(
                    "\\b(all done|is done|are done|completed|is complete|finished|all set|here is a"
                            + " summary|summary:|in summary|that's everything|ready to use|you can"
                            + " now)\\b");

    private TurnContinuation() {}

    static boolean looksUnfinished(String reply) {
        if (reply == null) {
            return false;
        }
        String text = reply.strip();
        if (text.isEmpty() || text.endsWith("?")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("approval") || lower.contains("approve")) {
            return false;
        }
        String tail = lower.length() > TAIL_CHARS ? lower.substring(lower.length() - TAIL_CHARS) : lower;
        if (text.endsWith(":")) {
            return true;
        }
        if (DONE.matcher(tail).find()) {
            return false;
        }
        return INTENT.matcher(tail).find();
    }
}
