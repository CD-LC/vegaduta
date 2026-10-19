package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The takeover state machine: every legal edge, every illegal one, and the per-session control
 * object that enforces them.
 */
class BrowserTakeoverStateTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");

    private static BrowserTakeoverControl control() {
        return new BrowserTakeoverControl(
                "t|a|s", "tenant-1", "user-1", UUID.randomUUID(), T0, 20, 40);
    }

    @Test
    void theOnlyLegalEdgesAreTheDocumentedOnes() {
        Set<String> legal =
                Set.of(
                        "AGENT_CONTROL>PAUSED_FOR_USER",
                        "PAUSED_FOR_USER>USER_CONTROL",
                        "PAUSED_FOR_USER>RESUMING",
                        "USER_CONTROL>RESUMING",
                        "RESUMING>AGENT_CONTROL",
                        "AGENT_CONTROL>EXPIRED",
                        "PAUSED_FOR_USER>EXPIRED",
                        "USER_CONTROL>EXPIRED",
                        "RESUMING>EXPIRED",
                        "AGENT_CONTROL>CANCELLED",
                        "PAUSED_FOR_USER>CANCELLED",
                        "USER_CONTROL>CANCELLED",
                        "RESUMING>CANCELLED");
        for (BrowserTakeoverState from : BrowserTakeoverState.values()) {
            for (BrowserTakeoverState to : BrowserTakeoverState.values()) {
                assertThat(from.canMoveTo(to))
                        .as(from + " -> " + to)
                        .isEqualTo(legal.contains(from + ">" + to));
            }
        }
    }

    @Test
    void terminalStatesGoNowhereAndOnlyUserHeldStatesOfferTheLiveView() {
        for (BrowserTakeoverState terminal :
                EnumSet.of(BrowserTakeoverState.EXPIRED, BrowserTakeoverState.CANCELLED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (BrowserTakeoverState to : BrowserTakeoverState.values()) {
                assertThat(terminal.canMoveTo(to)).isFalse();
            }
        }
        assertThat(
                        java.util.Arrays.stream(BrowserTakeoverState.values())
                                .filter(BrowserTakeoverState::userHasThePage))
                .containsExactlyInAnyOrder(
                        BrowserTakeoverState.PAUSED_FOR_USER, BrowserTakeoverState.USER_CONTROL);
    }

    @Test
    void theHappyPathWalksTheWholeCycleAndBumpsTheTokenEpochOnce() {
        BrowserTakeoverControl c = control();
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
        c.pause(BrowserTakeoverControl.Origin.AGENT, "captcha", "Solve it", "shop.example", T0);
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.PAUSED_FOR_USER);
        assertThat(c.epoch()).isZero();
        c.beginUserControl(T0.plusSeconds(5));
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.USER_CONTROL);
        assertThat(c.epoch()).isEqualTo(1);
        c.beginResume("user");
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.RESUMING);
        c.finishResume("note");
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.AGENT_CONTROL);
        assertThat(c.consumeResumeNote()).isEqualTo("note");
        assertThat(c.consumeResumeNote()).as("handed out once").isNull();
        // a second episode gets a new epoch, so a token from the first is dead
        c.pause(
                BrowserTakeoverControl.Origin.USER,
                "login",
                "Log in",
                "shop.example",
                T0.plusSeconds(60));
        c.beginUserControl(T0.plusSeconds(61));
        assertThat(c.epoch()).isEqualTo(2);
    }

    @Test
    void declining_aRequestWithoutActingGoesStraightToResuming() {
        BrowserTakeoverControl c = control();
        c.pause(BrowserTakeoverControl.Origin.AGENT, "other", "x", "h", T0);
        c.beginResume("user");
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.RESUMING);
    }

    @Test
    void illegalMovesThrowAndLeaveTheStateUntouched() {
        BrowserTakeoverControl c = control();
        assertThatThrownBy(() -> c.beginUserControl(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.beginResume("user")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.finishResume("n")).isInstanceOf(IllegalStateException.class);
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.AGENT_CONTROL);

        c.pause(BrowserTakeoverControl.Origin.AGENT, "other", "x", "h", T0);
        assertThatThrownBy(() -> c.pause(BrowserTakeoverControl.Origin.USER, "other", "y", "h", T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.finishResume("n")).isInstanceOf(IllegalStateException.class);
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.PAUSED_FOR_USER);

        c.beginUserControl(T0);
        assertThatThrownBy(() -> c.beginUserControl(T0)).isInstanceOf(IllegalStateException.class);
        assertThat(c.state()).isEqualTo(BrowserTakeoverState.USER_CONTROL);
    }

    @Test
    void expiredAndCancelledAreFinal() {
        BrowserTakeoverControl expired = control();
        expired.pause(BrowserTakeoverControl.Origin.AGENT, "other", "x", "h", T0);
        expired.expire("wait_timeout");
        assertThat(expired.state()).isEqualTo(BrowserTakeoverState.EXPIRED);
        assertThatThrownBy(() -> expired.cancel("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expired.beginUserControl(T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> expired.pause(BrowserTakeoverControl.Origin.USER, "o", "r", "h", T0))
                .isInstanceOf(IllegalStateException.class);

        BrowserTakeoverControl cancelled = control();
        cancelled.cancel("session_closed");
        assertThat(cancelled.state()).isEqualTo(BrowserTakeoverState.CANCELLED);
        assertThatThrownBy(() -> cancelled.expire("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ownershipNeedsBothTenantAndUserAndAnUnattendedSessionHasNoOwner() {
        BrowserTakeoverControl c = control();
        assertThat(c.isOwnedBy("tenant-1", "user-1")).isTrue();
        assertThat(c.isOwnedBy("tenant-2", "user-1")).isFalse();
        assertThat(c.isOwnedBy("tenant-1", "user-2")).isFalse();
        assertThat(c.isOwnedBy(null, "user-1")).isFalse();
        BrowserTakeoverControl unattended =
                new BrowserTakeoverControl("k", "tenant-1", null, UUID.randomUUID(), T0, 20, 40);
        assertThat(unattended.isOwnedBy("tenant-1", null)).isFalse();
        assertThat(unattended.isOwnedBy("tenant-1", "user-1")).isFalse();
    }

    @Test
    void theRateLimiterAllowsABurstThenRefillsAtTheSustainedRate() {
        BrowserTakeoverControl c =
                new BrowserTakeoverControl("k", "t", "u", UUID.randomUUID(), T0, 2, 3);
        c.pause(BrowserTakeoverControl.Origin.USER, "other", "", "h", T0);
        c.beginUserControl(T0);
        assertThat(c.admitInput(T0)).isTrue();
        assertThat(c.admitInput(T0)).isTrue();
        assertThat(c.admitInput(T0)).isTrue();
        assertThat(c.admitInput(T0)).as("burst of 3 spent").isFalse();
        assertThat(c.admitInput(T0.plusMillis(500))).as("2/s refills one token in 500ms").isTrue();
        assertThat(c.admitInput(T0.plusMillis(500))).isFalse();
        assertThat(c.inputEvents()).isEqualTo(4);
    }

    @Test
    void typedRunsBecomeScrubCandidatesOnlyWhenLongEnoughAndAreDrainedOnce() {
        BrowserTakeoverControl c = control();
        c.noteTyped("ab");
        c.endTypedRun();
        c.noteTyped("hunter2!");
        c.noteTyped("x");
        c.noteBackspace();
        c.endTypedRun();
        c.noteTyped("tail1");
        assertThat(c.drainTypedCandidates()).containsExactly("hunter2!", "tail1");
        assertThat(c.drainTypedCandidates()).isEmpty();
    }
}
