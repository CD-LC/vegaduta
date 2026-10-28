package com.agenticai.core.agents.turn;

import com.agenticai.core.guardrails.taint.TurnTaint;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Policy seam for taint on INBOUND CHANNEL turns ({@code app.agents.taint.inbound-channel-sources}),
 * deliberately without deciding the policy: the product owner has not chosen whether a message
 * from a channel sender should taint the turn, so the default is {@code off} and this class only
 * answers the question when an operator opts in.
 *
 * <ul>
 *   <li>{@code off} (default) - {@link #apply} marks nothing.
 *   <li>{@code all} - every inbound channel turn is marked {@value #SOURCE}, owner or not.
 *   <li>{@code non-owner} - marked {@value #SOURCE} unless the sender is the agent owner.
 * </ul>
 *
 * <p>An adapter wires it with one line where it builds the turn's {@code TurnContext}, next to
 * wherever it already knows who the sender is:
 *
 * <pre>{@code inboundChannelTaintPolicy.apply(turnContext.taint(), senderIsOwner);}</pre>
 *
 * The mark lands on the turn's own {@link TurnTaint} (the instance tool resolution adopts), so the
 * gates, the observe-mode audit and the conversation-level marker ({@code SessionTaintStore}) all
 * see it. No-op when the taint layer itself is {@code off}.
 */
@Component
public class InboundChannelTaintPolicy {

    /** The source label recorded on the turn. */
    public static final String SOURCE = "inbound_non_owner";

    /** The configured scope. */
    public enum Scope {
        OFF,
        ALL,
        NON_OWNER;

        /** Lenient parse; blank or unrecognised values fall back to {@link #OFF}. */
        public static Scope parse(String raw) {
            if (raw == null) {
                return OFF;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "all" -> ALL;
                case "non-owner" -> NON_OWNER;
                default -> OFF;
            };
        }
    }

    private final Scope scope;

    public InboundChannelTaintPolicy(
            @Value("${app.agents.taint.inbound-channel-sources:off}") String scope) {
        this.scope = Scope.parse(scope);
    }

    public Scope scope() {
        return scope;
    }

    /** Whether a turn from this sender should be marked under the configured scope. */
    public boolean shouldMark(boolean senderIsOwner) {
        return switch (scope) {
            case OFF -> false;
            case ALL -> true;
            case NON_OWNER -> !senderIsOwner;
        };
    }

    /**
     * Marks {@code taint} when the policy says so.
     *
     * @return true if the turn was marked
     */
    public boolean apply(TurnTaint taint, boolean senderIsOwner) {
        if (taint == null || !shouldMark(senderIsOwner)) {
            return false;
        }
        taint.mark(SOURCE);
        return true;
    }
}
