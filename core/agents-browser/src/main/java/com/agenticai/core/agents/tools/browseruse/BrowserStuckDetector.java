package com.agenticai.core.agents.tools.browseruse;

/**
 * Pure loop detector for a browsing task. After every STATE-CHANGING browser action (click, type,
 * select, go back - never a read-only snapshot/extract, and a scroll counts as a change because it
 * moves the page) the caller reports the action's signature and whether the page observably
 * changed. Two runs are tracked:
 *
 * <ul>
 *   <li>{@code repeatRun}: consecutive IDENTICAL actions that each left the page unchanged (the
 *       model clicking the same dead button again and again). An identical action that DID change
 *       the page (paging through results with "Next") is progress and resets the run.
 *   <li>{@code stagnantRun}: consecutive actions of any kind that left the page unchanged (the
 *       model flailing at different elements with no effect).
 * </ul>
 *
 * When either run reaches {@code warnAfter} the verdict is {@link Level#STUCK} (a structured "you
 * appear stuck" observation for the model); at {@code abortAfter} it is {@link Level#ABORT} and the
 * detector is latched - the caller refuses further browser actions until {@link #reset()} (a
 * deliberate {@code browser_navigate} re-plan). Not thread-safe: one instance per browser session,
 * touched only on the session manager's single Playwright thread.
 *
 * <p>Not covered, stated plainly: oscillation between two states (A, B, A, B...) where every action
 * does change the page, and pages that change on their own every poll (a ticking clock makes every
 * action look like progress). The per-turn tool-call ceiling and the session lifetime cap remain
 * the backstop there.
 */
final class BrowserStuckDetector {

    enum Level {
        OK,
        STUCK,
        ABORT
    }

    /** {@code repeats} is the longer of the two runs that triggered the verdict. */
    record Verdict(Level level, int repeats, String reason) {

        static final Verdict PROGRESS = new Verdict(Level.OK, 0, null);
    }

    private final int warnAfter;
    private final int abortAfter;
    private String lastSignature;
    private boolean lastUnchanged;
    private int repeatRun;
    private int stagnantRun;
    private boolean aborted;
    private String abortReason;

    BrowserStuckDetector(int warnAfter, int abortAfter) {
        this.warnAfter = Math.max(2, warnAfter);
        this.abortAfter = Math.max(this.warnAfter + 1, abortAfter);
    }

    /**
     * Records one state-changing action.
     *
     * @param signature stable identity of the action (verb + element role/name + typed-template
     *     hash) - never a resolved secret
     * @param pageChanged whether the page's observable state (URL, text size, element count,
     *     scroll, form values) differed before vs after the action
     */
    Verdict record(String signature, boolean pageChanged) {
        if (aborted) {
            return new Verdict(Level.ABORT, Math.max(repeatRun, stagnantRun), abortReason);
        }
        if (pageChanged) {
            repeatRun = 0;
            stagnantRun = 0;
            lastSignature = signature;
            lastUnchanged = false;
            return Verdict.PROGRESS;
        }
        repeatRun =
                (signature != null && signature.equals(lastSignature) && lastUnchanged)
                        ? repeatRun + 1
                        : 1;
        stagnantRun = stagnantRun + 1;
        lastSignature = signature;
        lastUnchanged = true;
        int worst = Math.max(repeatRun, stagnantRun);
        if (worst >= abortAfter) {
            aborted = true;
            abortReason =
                    "browsing aborted: "
                            + worst
                            + " consecutive browser actions had no effect on the page ("
                            + (repeatRun >= stagnantRun
                                    ? "the same action repeated"
                                    : "different actions, same unchanged page")
                            + "). Stop browsing, do not retry, and tell the user what you could not complete and where you got stuck.";
            return new Verdict(Level.ABORT, worst, abortReason);
        }
        if (worst >= warnAfter) {
            return new Verdict(
                    Level.STUCK,
                    worst,
                    "You appear stuck: the last "
                            + worst
                            + " browser actions"
                            + (repeatRun >= stagnantRun ? " were identical and" : "")
                            + " did not change the page. Do not repeat them. Take a new "
                            + "browser_snapshot and try a different element or approach, scroll to find what you need, or stop and tell the "
                            + "user what blocked you. The browsing task will be aborted after "
                            + abortAfter
                            + " ineffective actions.");
        }
        return Verdict.PROGRESS;
    }

    boolean isAborted() {
        return aborted;
    }

    /** Why the task was aborted; null while it is not. */
    String abortReason() {
        return abortReason;
    }

    /**
     * Clears both runs and the abort latch (a deliberate re-plan such as a fresh {@code
     * browser_navigate}).
     */
    void reset() {
        lastSignature = null;
        lastUnchanged = false;
        repeatRun = 0;
        stagnantRun = 0;
        aborted = false;
        abortReason = null;
    }
}
