package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which console turns can be explored by the bandits is a setting: as shipped only a turn the client tagged (the
 * Playground); on request every console turn, with a reference the server makes.
 */
class RlTurnRefPolicyTest {

    @Test
    void asShippedATurnWithoutAClientReferenceHasNone() {
        RlTurnRefPolicy shipped = new RlTurnRefPolicy(false);

        assertThat(shipped.turnRefFor(null)).isNull();
        assertThat(shipped.turnRefFor("")).isEmpty();
    }

    @Test
    void aClientReferenceIsAlwaysKeptAsIs() {
        assertThat(new RlTurnRefPolicy(false).turnRefFor("playground-7")).isEqualTo("playground-7");
        assertThat(new RlTurnRefPolicy(true).turnRefFor("playground-7")).isEqualTo("playground-7");
    }

    @Test
    void whenEnabledATurnWithoutOneGetsAServerMadeUniqueReference() {
        RlTurnRefPolicy all = new RlTurnRefPolicy(true);

        String first = all.turnRefFor(null);
        String second = all.turnRefFor("   ");

        assertThat(first).startsWith(RlTurnRefPolicy.SERVER_PREFIX).hasSizeGreaterThan(RlTurnRefPolicy.SERVER_PREFIX.length());
        assertThat(second).startsWith(RlTurnRefPolicy.SERVER_PREFIX).isNotEqualTo(first);
    }
}
