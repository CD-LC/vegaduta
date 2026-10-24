package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How long one buffered-depth turn may run (the rules are in {@link TurnTimeLimit}, covered by TurnTimeLimitTest). The configured {@code buffered-turn-timeout} guards a
 * provider that went silent behind a browser that gives up at 300 s; an agent job has no browser and
 * its own {@code timeoutSeconds} is the authority, so a job with a longer budget is not cut at 240 s.
 */
class BufferedTurnLimitTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final Duration CONFIGURED = Duration.ofSeconds(240);

    private final BufferedTurnRunner runner =
            new BufferedTurnRunner(null, null, null, null, null, null, null, null, null, null, CONFIGURED, Duration.ofSeconds(20));

    private static TurnContext turn(Instant deadline) {
        return new TurnContext(null, UUID.randomUUID(), "s", "r", T0, deadline, null);
    }

    @Test
    void aTurnWithNoDeadlineGetsTheConfiguredLimit() {
        assertThat(runner.bufferedLimit(turn(null), T0)).isEqualTo(CONFIGURED);
    }

    @Test
    void aJobDeadlineBeyondTheConfiguredLimitRaisesIt() {
        TurnContext job = turn(T0.plus(Duration.ofMinutes(30)));

        assertThat(runner.bufferedLimit(job, T0)).isEqualTo(Duration.ofMinutes(30));
        assertThat(runner.bufferedLimit(job, T0.plus(Duration.ofMinutes(10)))).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void aDeadlineSoonerThanTheConfiguredLimitNowEndsTheTurnAtTheDeadline() {
        // Before the runtime-settings work this test pinned the opposite ("never lowers it"): the job's own timer
        // was the only thing that ended the turn, so the turn kept running in the background past the job's
        // timeout. The deadline is now enforced inside the turn.
        TurnContext soon = turn(T0.plusSeconds(60));

        assertThat(runner.bufferedLimit(soon, T0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(runner.bufferedLimit(soon, T0.plusSeconds(45))).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void anExpiredDeadlineFallsBackToTheConfiguredLimit() {
        assertThat(runner.bufferedLimit(turn(T0.plusSeconds(1)), T0.plusSeconds(500))).isEqualTo(CONFIGURED);
    }
}
