package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link TurnReservationGuards} is the whole "a hosted turn's reservation is touched at most once"
 * invariant: each consume method must return true exactly once per instance, for every caller that
 * shares it.
 */
class TurnReservationGuardsTest {

    @Test
    void eachReservationCanBeConsumedExactlyOnce() {
        TurnReservationGuards guards = new TurnReservationGuards();

        assertThat(guards.consumeTokenReservation()).isTrue();
        assertThat(guards.consumeTokenReservation()).isFalse();
        assertThat(guards.consumeTokenReservation()).isFalse();

        assertThat(guards.consumeSpendReservation()).isTrue();
        assertThat(guards.consumeSpendReservation()).isFalse();
    }

    @Test
    void theTwoGuardsAreIndependent() {
        TurnReservationGuards guards = new TurnReservationGuards();

        guards.consumeTokenReservation();

        // Spending the token reservation must not consume the spend one.
        assertThat(guards.consumeSpendReservation()).isTrue();
    }

    @Test
    void aNewTurnGetsFreshGuards() {
        TurnReservationGuards first = new TurnReservationGuards();
        first.consumeTokenReservation();
        first.consumeSpendReservation();

        TurnReservationGuards second = new TurnReservationGuards();
        assertThat(second.consumeTokenReservation()).isTrue();
        assertThat(second.consumeSpendReservation()).isTrue();
    }
}
