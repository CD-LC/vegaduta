package com.agenticai.core.agentspi;

import java.util.Map;

/**
 * Tells the Assistant's setup guide whether a thing the user is asking about is already set up on this
 * install, so it can say "Slack is not connected yet - open this page" instead of guessing. Each module
 * that owns a connection contributes one provider for the topics it knows; a topic nobody answers for
 * is reported as unknown and the guide says so rather than inventing a state.
 *
 * <p>Called on the request thread of an agent turn, so the current tenant is bound. Implementations
 * must be cheap, read-only and must not throw for a missing connection.
 */
public interface SetupStatusProvider {

    /** What an install knows about one topic. */
    enum State {
        /** Set up and working. */
        READY,
        /** Nothing set up yet. */
        NOT_SET_UP,
        /** Set up but off or failing; {@link Status#detail()} says what. */
        NEEDS_ATTENTION
    }

    /** One answer: the state and a short plain-language fact (no secrets, no identifiers a user cannot see). */
    record Status(State state, String detail) {}

    /** Answers by topic id (ids from the platform guide's topic catalogue); topics this provider does not own are absent. */
    Map<String, Status> statuses();
}
