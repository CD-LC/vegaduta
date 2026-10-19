package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.omnichannel.session.ChannelSenderPolicy.Decision;
import org.junit.jupiter.api.Test;

class MatrixSenderPolicyTest {

    private static final String BOT = "@bot:example.org";

    @Test
    void pairingModeAdmitsStrangersToThePairingFlowOnly() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(BOT, MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, "");
        assertThat(policy.evaluate("@stranger:other.org")).isEqualTo(Decision.ADMIT);
    }

    @Test
    void theBotNeverTalksToItself() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(BOT, MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, "*");
        assertThat(policy.evaluate("@Bot:example.org")).isEqualTo(Decision.DROP);
        assertThat(policy.evaluate(null)).isEqualTo(Decision.DROP);
    }

    @Test
    void allowlistModeDropsEveryoneNotListed() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(
                        BOT, MatrixSenderMode.ALLOWLIST, MatrixJoinPolicy.OFF, "@alice:example.org");
        assertThat(policy.evaluate("@alice:example.org")).isEqualTo(Decision.ADMIT);
        assertThat(policy.evaluate("@mallory:example.org")).isEqualTo(Decision.DROP);
    }

    @Test
    void allowlistModeWithAnEmptyListAdmitsNobody() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(BOT, MatrixSenderMode.ALLOWLIST, MatrixJoinPolicy.OFF, "");
        assertThat(policy.evaluate("@alice:example.org")).isEqualTo(Decision.DROP);
    }

    @Test
    void joinOnInviteIsOffByDefaultEvenForAllowListedInviters() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(
                        BOT, MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, "@alice:example.org");
        assertThat(policy.mayJoinInviteFrom("@alice:example.org")).isFalse();
    }

    @Test
    void joinOnInviteNeedsTheListWhenEnabled() {
        MatrixSenderPolicy policy =
                new MatrixSenderPolicy(
                        BOT, MatrixSenderMode.PAIRING, MatrixJoinPolicy.ALLOWLIST, "@alice:example.org");
        assertThat(policy.mayJoinInviteFrom("@alice:example.org")).isTrue();
        assertThat(policy.mayJoinInviteFrom("@mallory:example.org")).isFalse();
        assertThat(policy.mayJoinInviteFrom(BOT)).isFalse();
        assertThat(policy.mayJoinInviteFrom(null)).isFalse();
    }
}
