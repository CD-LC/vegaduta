package com.agenticai.core.agents.tools.classification;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import java.util.HashSet;
import java.util.List;

/**
 * Pure decision rules for lethal-trifecta enforcement. No Spring, no I/O: the service that wires
 * these to config, the registry and the agent entity lives in {@code
 * com.agenticai.core.agents.tools.TrifectaEnforcementService}.
 */
public final class TrifectaEnforcementPolicy {

    /**
     * Marker the web console keys on to show a block message as a policy block, not a generic
     * error.
     */
    public static final String BLOCK_MESSAGE_PREFIX = "Blocked by the lethal-trifecta policy";

    private static final int MAX_NAMES_IN_MESSAGE = 5;

    private TrifectaEnforcementPolicy() {}

    /** What a config save should do about its resulting tool mix. */
    public enum Action {
        /** Not a trifecta, or enforcement is off. */
        NONE,
        /** Trifecta present: log, count, surface the advisory; the save proceeds unchanged. */
        WARN,
        /** Trifecta present: force the agent's autonomy tier to SUPERVISED (ALWAYS_GATE). */
        FORCE_APPROVAL,
        /** Trifecta made worse: reject the save. */
        BLOCK
    }

    /**
     * @param before the agent's report before the save (may be null when it was not computed or is
     *     unknown)
     * @param after the report for the tool mix the save would leave (null = unknown, treated as no
     *     trifecta)
     */
    public static Action decide(
            TrifectaEnforcementMode mode,
            LethalTrifectaDetector.Report before,
            LethalTrifectaDetector.Report after) {
        TrifectaEnforcementMode effective = mode == null ? TrifectaEnforcementMode.WARN : mode;
        if (effective == TrifectaEnforcementMode.OFF || !isLethal(after)) {
            return Action.NONE;
        }
        return switch (effective) {
            case WARN -> Action.WARN;
            case REQUIRE_APPROVAL -> Action.FORCE_APPROVAL;
            // Never block a save that leaves the agent no worse than it was: removing a leg ends up
            // here as NONE (after is not lethal), re-saving or removing unrelated tools as WARN.
            case BLOCK -> isNoWorse(before, after) ? Action.WARN : Action.BLOCK;
            case OFF -> Action.NONE;
        };
    }

    public static boolean isLethal(LethalTrifectaDetector.Report report) {
        return report != null && report.isLethalTrifecta();
    }

    /**
     * True when {@code before} already held the trifecta and {@code after} adds no tool to any of
     * its three legs. An unknown {@code before} is never "no worse": the block decision stays
     * conservative.
     */
    public static boolean isNoWorse(
            LethalTrifectaDetector.Report before, LethalTrifectaDetector.Report after) {
        if (!isLethal(before) || after == null) {
            return false;
        }
        return subset(after.privateDataTools(), before.privateDataTools())
                && subset(after.untrustedContentTools(), before.untrustedContentTools())
                && subset(after.egressTools(), before.egressTools());
    }

    private static boolean subset(List<String> candidate, List<String> of) {
        return new HashSet<>(of).containsAll(candidate);
    }

    /**
     * Whether a confirmation-gate-exempt tool (channel_send_message, google_gmail, ...) keeps its
     * exemption for this agent.
     *
     * <p>Only {@code require-approval} and {@code block} take the exemption away: {@code warn} is
     * the behaviour from before enforcement existed, so it never changes dispatch.
     *
     * @param overridesExempt {@code app.tools.classification.trifecta-overrides-exempt}
     * @param holdsTrifecta true / false, or {@code null} when it could not be computed (fail SAFE:
     *     an agent we cannot judge does not get the exemption)
     */
    public static boolean exemptionHolds(
            TrifectaEnforcementMode mode, boolean overridesExempt, Boolean holdsTrifecta) {
        if (mode == TrifectaEnforcementMode.OFF
                || mode == TrifectaEnforcementMode.WARN
                || !overridesExempt) {
            return true;
        }
        return Boolean.FALSE.equals(holdsTrifecta);
    }

    /**
     * Whether actions that send data out always need a human for this agent right now, because of
     * the trifecta enforcement (so never under {@code off} or {@code warn}). A SUPERVISED agent is
     * gated on every call; otherwise the gate applies when the confirmation gate is on and the
     * trifecta overrides the exempt list. A FULLY_AUTONOMOUS agent is the tenant's explicit choice
     * and is never gated by the classification gate.
     */
    public static boolean isGated(
            boolean lethal,
            TrifectaEnforcementMode mode,
            AgentAutonomyTier tier,
            boolean overridesExempt,
            boolean confirmationGate) {
        if (!lethal
                || mode == TrifectaEnforcementMode.OFF
                || mode == TrifectaEnforcementMode.WARN
                || tier == AgentAutonomyTier.FULLY_AUTONOMOUS) {
            return false;
        }
        return tier == AgentAutonomyTier.SUPERVISED || (overridesExempt && confirmationGate);
    }

    /** The platform is acting on the trifecta (not just warning about it). */
    public static boolean isEnforced(boolean lethal, TrifectaEnforcementMode mode) {
        return lethal
                && (mode == TrifectaEnforcementMode.REQUIRE_APPROVAL
                        || mode == TrifectaEnforcementMode.BLOCK);
    }

    /**
     * The 400 message: names the three legs and how to fix it. Starts with {@link
     * #BLOCK_MESSAGE_PREFIX}.
     */
    public static String blockMessage(LethalTrifectaDetector.Report after) {
        return BLOCK_MESSAGE_PREFIX
                + ": this tool mix lets the agent read private data ("
                + summarise(after.privateDataTools())
                + "), take in untrusted content ("
                + summarise(after.untrustedContentTools())
                + ") and send data out ("
                + summarise(after.egressTools())
                + "), so a prompt injection could leak the private data. "
                + "Remove at least one of these three kinds of tool from the agent, or ask your administrator "
                + "to switch the enforcement mode to require-approval, which keeps the tools but makes a person "
                + "approve what the agent sends.";
    }

    private static String summarise(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "none";
        }
        if (names.size() <= MAX_NAMES_IN_MESSAGE) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMES_IN_MESSAGE))
                + " and "
                + (names.size() - MAX_NAMES_IN_MESSAGE)
                + " more";
    }
}
