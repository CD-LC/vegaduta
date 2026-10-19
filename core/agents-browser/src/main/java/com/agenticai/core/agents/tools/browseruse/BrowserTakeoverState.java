package com.agenticai.core.agents.tools.browseruse;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who is driving one browser session. The only legal moves are the edges below; everything else is
 * refused by {@link BrowserTakeoverControl} (and tested), so "the agent can act while the person is
 * typing" cannot be produced by a caller that forgets a check.
 *
 * <pre>
 *   AGENT_CONTROL --request/pause--> PAUSED_FOR_USER --begin--> USER_CONTROL --hand back/idle--> RESUMING --done--> AGENT_CONTROL
 *                                          |                                                          ^
 *                                          +------------ hand back without acting ---------------------+
 *   any non-terminal state --> EXPIRED (waited too long / hard max duration)
 *   any non-terminal state --> CANCELLED (session closed, user cancelled)
 * </pre>
 *
 * Only {@link #AGENT_CONTROL} lets an agent browser tool run. {@link #RESUMING} is the short window
 * in which the server reads the page back (redacted) before the agent is allowed in again.
 */
enum BrowserTakeoverState {
    AGENT_CONTROL,
    PAUSED_FOR_USER,
    USER_CONTROL,
    RESUMING,
    EXPIRED,
    CANCELLED;

    private static final Set<BrowserTakeoverState> TERMINAL = EnumSet.of(EXPIRED, CANCELLED);

    boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * True for the states in which the person (not the agent) owns the page: the live view is on
     * offer.
     */
    boolean userHasThePage() {
        return this == PAUSED_FOR_USER || this == USER_CONTROL;
    }

    /** True when {@code next} is a legal move from this state. */
    boolean canMoveTo(BrowserTakeoverState next) {
        if (isTerminal() || next == null || next == this) {
            return false;
        }
        return switch (next) {
            case PAUSED_FOR_USER -> this == AGENT_CONTROL;
            case USER_CONTROL -> this == PAUSED_FOR_USER;
            case RESUMING -> this == USER_CONTROL || this == PAUSED_FOR_USER;
            case AGENT_CONTROL -> this == RESUMING;
            case EXPIRED, CANCELLED -> true;
        };
    }
}
