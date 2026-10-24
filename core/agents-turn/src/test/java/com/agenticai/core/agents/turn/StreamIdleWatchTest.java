package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.TurnToolActivity.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The stream idle timer counts model silence, not tool time. A 90 s idle timer used to fire during a
 * tool that simply took longer (a browser task, a coding task, an agent-to-agent call) and the turn was
 * then replayed on another provider, tools included. Time here is virtual ({@link ManualScheduler}).
 */
class StreamIdleWatchTest {

    private static final Duration IDLE = Duration.ofSeconds(90);
    private static final Duration CEILING = Duration.ofMinutes(30);

    private final ManualScheduler time = new ManualScheduler();
    private final TurnToolActivity activity = new TurnToolActivity(time::nanos);
    private final Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
    private final List<String> received = new ArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private boolean completed;

    private void subscribe(Duration idle, Duration ceiling) {
        StreamIdleWatch.apply(tokens.asFlux(), idle, ceiling, activity, time)
                .subscribe(received::add, error::set, () -> completed = true);
    }

    @Test
    void silenceLongerThanTheIdleTimeoutFiresWhenNoToolIsRunning() {
        subscribe(IDLE, CEILING);

        tokens.tryEmitNext("a");
        time.advance(Duration.ofSeconds(89));
        assertThat(error.get()).isNull();
        time.advance(Duration.ofSeconds(2));

        assertThat(error.get()).isInstanceOf(TimeoutException.class);
        assertThat(error.get().getMessage()).contains("Did not observe any item or terminal signal");
        assertThat(received).containsExactly("a");
    }

    @Test
    void aToolRunningFarLongerThanTheIdleTimeoutDoesNotFireIt() {
        subscribe(IDLE, CEILING);
        tokens.tryEmitNext("a");

        time.advance(Duration.ofSeconds(10));
        long call = activity.begin("browser_task");
        time.advance(Duration.ofMinutes(10)); // 600 s of tool time, 6.6x the idle timeout
        assertThat(error.get()).as("the tool is working; the model is not silent").isNull();

        activity.end(call, Outcome.EXECUTED);
        tokens.tryEmitNext("b");
        tokens.tryEmitComplete();

        assertThat(error.get()).isNull();
        assertThat(received).containsExactly("a", "b");
        assertThat(completed).isTrue();
    }

    @Test
    void theIdleClockStartsAgainWhenTheToolFinishes() {
        subscribe(IDLE, CEILING);
        tokens.tryEmitNext("a");
        long call = activity.begin("browser_task");
        time.advance(Duration.ofMinutes(5));
        activity.end(call, Outcome.EXECUTED);

        time.advance(Duration.ofSeconds(80));
        assertThat(error.get()).as("80 s since the tool finished is inside the 90 s allowance").isNull();
        time.advance(Duration.ofSeconds(11));

        assertThat(error.get()).as("the model went silent after the tool finished").isInstanceOf(TimeoutException.class);
    }

    @Test
    void aToolThatOutlivesTheCeilingIsTreatedAsHung() {
        subscribe(IDLE, Duration.ofMinutes(10));
        tokens.tryEmitNext("a");
        activity.begin("browser_task");

        time.advance(Duration.ofMinutes(9));
        assertThat(error.get()).isNull();
        time.advance(Duration.ofMinutes(2));

        assertThat(error.get()).isInstanceOf(TimeoutException.class);
    }

    @Test
    void aToolReportingProgressKeepsTheCeilingFromRunningOut() {
        subscribe(IDLE, Duration.ofMinutes(10));
        tokens.tryEmitNext("a");
        activity.begin("coding_task");

        for (int i = 0; i < 4; i++) {
            time.advance(Duration.ofMinutes(8));
            activity.touch();
        }

        assertThat(error.get()).as("32 minutes of work, each 8 minute stretch ended in progress").isNull();
    }

    @Test
    void theWaitForTheFirstTokenIsNotCutShortByAToolEither() {
        subscribe(IDLE, CEILING);

        long call = activity.begin("search_knowledge_base");
        time.advance(Duration.ofMinutes(3));
        assertThat(error.get()).isNull();
        activity.end(call, Outcome.EXECUTED);
        tokens.tryEmitNext("a");

        assertThat(received).containsExactly("a");
        assertThat(error.get()).isNull();
    }

    @Test
    void aStreamThatFinishesCancelsItsTimerAndNeverFiresLater() {
        subscribe(IDLE, CEILING);
        tokens.tryEmitNext("a");
        tokens.tryEmitComplete();

        time.advance(Duration.ofHours(1));

        assertThat(completed).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    void anUpstreamErrorPassesThroughUntouched() {
        subscribe(IDLE, CEILING);
        IllegalStateException boom = new IllegalStateException("provider said no");

        tokens.tryEmitError(boom);

        assertThat(error.get()).isSameAs(boom);
    }

    @Test
    void theTimeoutIsReadAsATimeoutByTheDepthFallbackSignal() {
        subscribe(IDLE, CEILING);
        time.advance(IDLE.plusSeconds(1));

        assertThat(DegradedRetrySignal.isTimeout(error.get())).isTrue();
    }

    @Test
    void flux() {
        // sanity: the helper accepts any Flux, not only a sink
        List<String> out = StreamIdleWatch.apply(Flux.just("x", "y"), IDLE, CEILING, activity, time).collectList().block();
        assertThat(out).containsExactly("x", "y");
    }
}
