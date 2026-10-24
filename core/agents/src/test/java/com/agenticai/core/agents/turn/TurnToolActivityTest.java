package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TurnToolActivityTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final TurnToolActivity activity = new TurnToolActivity(clock::get);

    @Test
    void aFreshTurnHasRunNothing() {
        assertThat(activity.anyToolRan()).isFalse();
        assertThat(activity.inFlightCount()).isZero();
        assertThat(activity.snapshot().finished()).isEmpty();
    }

    @Test
    void aRunningToolIsInFlightAndCountsAsHavingRun() {
        long id = activity.begin("send_email");

        assertThat(activity.inFlightCount()).isEqualTo(1);
        assertThat(activity.anyToolRan()).as("it may complete after the turn gave up on it").isTrue();
        assertThat(activity.snapshot().inFlight()).containsExactly("send_email");

        activity.end(id, TurnToolActivity.Outcome.EXECUTED);
        assertThat(activity.inFlightCount()).isZero();
        assertThat(activity.snapshot().finished().get("send_email").executed()).isEqualTo(1);
    }

    @Test
    void startFinishAndProgressAllMoveTheLastActivityMark() {
        long start = activity.lastActivityNanos();

        clock.set(5_000);
        long id = activity.begin("web_search");
        assertThat(activity.lastActivityNanos()).isEqualTo(5_000).isGreaterThan(start);

        clock.set(9_000);
        activity.touch();
        assertThat(activity.lastActivityNanos()).isEqualTo(9_000);

        clock.set(12_000);
        activity.end(id, TurnToolActivity.Outcome.EXECUTED);
        assertThat(activity.lastActivityNanos()).isEqualTo(12_000);
    }

    @Test
    void outcomesAreTalliedPerToolAndOnlyReachedCallsCountAsHavingActed() {
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.JOURNALED);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.FAILED);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.NOT_RUN);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.REPLAYED);
        activity.end(activity.begin("get_order"), TurnToolActivity.Outcome.EXECUTED);

        TurnToolActivity.Tally email = activity.snapshot().finished().get("send_email");
        assertThat(email.journaled()).isEqualTo(1);
        assertThat(email.failed()).isEqualTo(1);
        assertThat(email.notRun()).isEqualTo(1);
        assertThat(email.replayed()).isEqualTo(1);
        assertThat(email.reachedTool()).isEqualTo(2);
        assertThat(email.unjournaled()).as("only the failed call has no journal record").isEqualTo(1);
        assertThat(activity.snapshot().finished().get("get_order").unjournaled()).isEqualTo(1);
    }

    @Test
    void callsAGuardAnsweredDoNotCountAsAToolHavingRun() {
        activity.end(activity.begin("delete_all"), TurnToolActivity.Outcome.NOT_RUN);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.REPLAYED);

        assertThat(activity.anyToolRan()).isFalse();
    }

    @Test
    void aTimerCanListenForActivityAndStopListening() {
        java.util.concurrent.atomic.AtomicInteger woken = new java.util.concurrent.atomic.AtomicInteger();
        Runnable stop = activity.onActivity(woken::incrementAndGet);

        long call = activity.begin("browser_task");
        activity.touch();
        activity.end(call, TurnToolActivity.Outcome.EXECUTED);
        assertThat(woken).hasValue(3);

        stop.run();
        activity.touch();
        assertThat(woken).as("removed listeners are not called").hasValue(3);
    }

    @Test
    void aListenerThatThrowsNeverBreaksAToolCall() {
        activity.onActivity(() -> {
            throw new IllegalStateException("timer gone");
        });

        long call = activity.begin("get_order");
        activity.end(call, TurnToolActivity.Outcome.EXECUTED);

        assertThat(activity.snapshot().finished().get("get_order").executed()).isEqualTo(1);
    }

    @Test
    void finishedCallsCountsEveryAnsweredCallWhateverBecameOfIt() {
        assertThat(activity.finishedCalls()).isZero();

        long running = activity.begin("search");
        assertThat(activity.finishedCalls()).as("a call still running has not finished").isZero();
        activity.end(running, TurnToolActivity.Outcome.EXECUTED);
        activity.end(activity.begin("search"), TurnToolActivity.Outcome.FAILED);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.REPLAYED);
        activity.end(activity.begin("send_email"), TurnToolActivity.Outcome.NOT_RUN);

        assertThat(activity.finishedCalls()).isEqualTo(4);
    }
}
