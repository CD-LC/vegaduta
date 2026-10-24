package com.agenticai.core.guardrails;

/**
 * The side-effect rule of an offline eval turn: whether a tool call may really run, or is answered with a
 * simulated result instead because running it could change data, reach outside the platform or spend something
 * that cannot be taken back.
 *
 * <p>Consulted by {@link GuardedToolCallback} for the turn's {@link SandboxApprovalGrants.Grant} only (an ordinary
 * turn has no sandbox, so none of this applies to a person's conversation), AFTER the approval step: a call that
 * is held for a human never reaches this check, and a call the sandbox auto-approved still goes through it, so an
 * approved mutating tool is simulated, not executed.
 *
 * <p>The decision must fail closed: a tool nobody classified, a classification that cannot be read, and an argument
 * shape that cannot be understood are all {@link Verdict#simulate simulated}. The classification registry lives in
 * {@code core/agents}, which this module cannot depend on, which is why the rule is an interface here and the
 * implementation is supplied by the agents module (the same seam as {@link ToolDispatchGate}).
 */
@FunctionalInterface
public interface ToolEffectSandbox {

    /**
     * @param toolName the tool as the model sees it
     * @param toolInput the call's JSON arguments (a few tools are read-only for some arguments only)
     */
    Verdict check(String toolName, String toolInput);

    /**
     * @param execute true: run the tool for real; false: answer with a simulated result
     * @param reason a short, log-safe note (never contains the arguments or output); why the call is simulated
     */
    record Verdict(boolean execute, String reason) {

        private static final Verdict RUN = new Verdict(true, null);

        public static Verdict run() {
            return RUN;
        }

        public static Verdict simulate(String reason) {
            return new Verdict(false, reason == null || reason.isBlank() ? "not known to be read-only" : reason);
        }
    }
}
