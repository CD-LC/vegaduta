package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.runtime.RuntimeLayer;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The wall-clock limit of one turn, from the turn's runtime settings and its enclosing deadline (an agent job's
 * own timeout): a buffered depth always has one (the platform's {@code buffered-turn-timeout} unless a tenant or
 * agent set another; a job deadline sooner than that ends the turn at the deadline, a later one extends the
 * platform default but never an explicit limit); a FAST stream has none unless a tenant, agent or deadline gives
 * it one.
 */
class TurnTimeLimitTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final Duration PLATFORM = Duration.ofSeconds(240);

    private static AgentRuntimeSettings settings(Duration turnTimeout, RuntimeLayer layer) {
        Map<RuntimeSetting, RuntimeLayer> layers = new EnumMap<>(RuntimeSetting.class);
        layers.put(RuntimeSetting.TURN_TIMEOUT_SECONDS, layer);
        return new AgentRuntimeSettings(15, turnTimeout, Duration.ZERO, 20_000, 3, 0.0, 8, layers);
    }

    private static TurnContext turn(Instant deadline, AgentRuntimeSettings settings) {
        TurnContext context = new TurnContext(null, UUID.randomUUID(), "s", "r", T0, deadline, null);
        if (settings != null) {
            context.runtime().set(settings);
        }
        return context;
    }

    // --- buffered depths ---------------------------------------------------------------------

    @Test
    void aBufferedTurnWithNoSettingsAndNoDeadlineGetsTheConfiguredLimit() {
        assertThat(TurnTimeLimit.buffered(turn(null, null), T0, PLATFORM)).isEqualTo(PLATFORM);
    }

    @Test
    void anAgentsOwnTurnTimeoutIsTheBufferedLimit() {
        TurnContext context = turn(null, settings(Duration.ofSeconds(60), RuntimeLayer.AGENT));

        assertThat(TurnTimeLimit.buffered(context, T0, PLATFORM)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aJobDeadlineBeyondThePlatformDefaultExtendsIt() {
        TurnContext job = turn(T0.plus(Duration.ofMinutes(30)), settings(PLATFORM, RuntimeLayer.PLATFORM));

        assertThat(TurnTimeLimit.buffered(job, T0, PLATFORM)).isEqualTo(Duration.ofMinutes(30));
        assertThat(TurnTimeLimit.buffered(job, T0.plus(Duration.ofMinutes(10)), PLATFORM)).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void aJobDeadlineBeyondAnExplicitAgentLimitDoesNotExtendIt() {
        TurnContext job = turn(T0.plus(Duration.ofMinutes(30)), settings(Duration.ofSeconds(60), RuntimeLayer.AGENT));

        assertThat(TurnTimeLimit.buffered(job, T0, PLATFORM)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aJobDeadlineSoonerThanTheLimitEndsTheTurnAtTheDeadline() {
        TurnContext soon = turn(T0.plusSeconds(60), settings(PLATFORM, RuntimeLayer.PLATFORM));

        assertThat(TurnTimeLimit.buffered(soon, T0, PLATFORM)).isEqualTo(Duration.ofSeconds(60));
        assertThat(TurnTimeLimit.buffered(soon, T0.plusSeconds(45), PLATFORM)).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void anExpiredDeadlineFallsBackToTheLimitRatherThanFiringAtOnce() {
        assertThat(TurnTimeLimit.buffered(turn(T0.plusSeconds(1), null), T0.plusSeconds(500), PLATFORM)).isEqualTo(PLATFORM);
    }

    // --- FAST streams --------------------------------------------------------------------------

    @Test
    void aFastTurnWithOnlyPlatformSettingsHasNoOverallLimit() {
        assertThat(TurnTimeLimit.fast(turn(null, settings(PLATFORM, RuntimeLayer.PLATFORM)), T0)).isEmpty();
        assertThat(TurnTimeLimit.fast(turn(null, null), T0)).isEmpty();
    }

    @Test
    void aFastTurnIsLimitedByTheAgentsTimeoutByTheDeadlineOrByTheSooner() {
        AgentRuntimeSettings agent = settings(Duration.ofSeconds(90), RuntimeLayer.AGENT);

        assertThat(TurnTimeLimit.fast(turn(null, agent), T0)).contains(Duration.ofSeconds(90));
        assertThat(TurnTimeLimit.fast(turn(T0.plusSeconds(30), settings(PLATFORM, RuntimeLayer.PLATFORM)), T0))
            .contains(Duration.ofSeconds(30));
        assertThat(TurnTimeLimit.fast(turn(T0.plusSeconds(30), agent), T0)).contains(Duration.ofSeconds(30));
        assertThat(TurnTimeLimit.fast(turn(T0.plusSeconds(300), agent), T0)).contains(Duration.ofSeconds(90));
    }

    // --- the cap -----------------------------------------------------------------------------------

    @Test
    void aStreamThatFinishesInTimeIsUntouched() {
        Flux<String> capped = TurnTimeLimit.cap(Flux.just("a", "b"), Duration.ofSeconds(5));

        assertThat(capped.collectList().block(Duration.ofSeconds(2))).containsExactly("a", "b");
    }

    @Test
    void aStreamThatRunsPastTheLimitFailsWithTheTurnLimitErrorNotAProviderTimeout() {
        Flux<String> capped = TurnTimeLimit.cap(Flux.concat(Flux.just("a"), Flux.never()), Duration.ofMillis(100));

        assertThatThrownBy(() -> capped.collectList().block(Duration.ofSeconds(5)))
            .isInstanceOf(TurnTimeLimit.Exceeded.class)
            .satisfies(e -> {
                assertThat(((TurnTimeLimit.Exceeded) e).limit()).isEqualTo(Duration.ofMillis(100));
                assertThat(DegradedRetrySignal.isTimeout(e)).as("must not look like a hung provider").isFalse();
            });
    }
}
