package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

/**
 * The step-event heartbeat (2026-09-25 latency audit): a {@code heartbeat} event on the side
 * channel every {@code app.chat.sse.step-heartbeat-interval} for the life of a step-event turn, so
 * the silent pre-token / tool-execution / provider-failover gaps stop tripping the browser's
 * stream-idle timer and Cloudflare's ~100s origin limit on turns the server was still executing
 * healthily. See {@link AgentStepEventEmitter#heartbeat}'s javadoc.
 *
 * <p>No reactor-test on this module's classpath (same constraint ChatTurnCancelServiceTest notes),
 * so these are real-clock tests on short intervals with deliberately wide count bounds - the
 * contract under test is "beats flow while the turn is open and stop when it ends", not an exact
 * tick count, which wall-clock scheduling can never promise anyway.
 */
class AgentStepEventEmitterHeartbeatTest {

    private static final String MARKER = AgentStepEventEmitter.STEP_EVENT_MARKER;

    private static List<String> collect(AgentStepEventEmitter emitter, long turnMillis)
            throws InterruptedException {
        List<String> chunks = new CopyOnWriteArrayList<>();
        emitter.asFlux().subscribe(chunks::add);
        // runStarted is what actually arms the heartbeat (see startHeartbeat's
        // javadoc: the task is bound to a real subscription, so an emitter that
        // is never subscribed never ticks).
        emitter.runStarted("session", UUID.randomUUID());
        Thread.sleep(turnMillis);
        emitter.complete();
        return chunks;
    }

    private static long heartbeats(List<String> chunks) {
        return chunks.stream().filter(c -> c.contains("\"heartbeat\"")).count();
    }

    @Test
    void heartbeatsTickForTheLifeOfTheTurnAndStopAtComplete() throws InterruptedException {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active(Duration.ofMillis(50));
        List<String> chunks = collect(emitter, 160); // ~3 beats at 50ms
        assertEquals("run_started", type(chunks.get(0)));
        Thread.sleep(120); // ~2 more ticks would land if complete() had not disposed
        long beats = heartbeats(chunks);
        assertTrue(
                beats >= 2 && beats <= 5,
                "expected ~3 beats for a 160ms turn at 50ms, got " + beats);
        assertTrue(chunks.stream().skip(1).allMatch(c -> "heartbeat".equals(type(c))));
    }

    @Test
    void heartbeatCarriesElapsedMsSinceRunStarted() throws InterruptedException {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active(Duration.ofMillis(50));
        List<String> chunks = collect(emitter, 130);
        String firstBeat =
                chunks.stream().filter(c -> c.contains("\"heartbeat\"")).findFirst().orElseThrow();
        assertTrue(firstBeat.contains("\"ms\":"), firstBeat);
    }

    @Test
    void zeroIntervalMeansNoHeartbeat() throws InterruptedException {
        List<String> chunks = collect(AgentStepEventEmitter.active(Duration.ZERO), 150);
        assertEquals(1, chunks.size()); // run_started only

        // The production overload tolerates a null interval too (treated as off).
        AgentStepEventEmitter none = AgentStepEventEmitter.active(null);
        none.runStarted("s", UUID.randomUUID());
        none.complete();
        assertTrue(none.hasEmitted());
    }

    @Test
    void heartbeatCountsAsWireActivity() throws InterruptedException {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active(Duration.ofMillis(50));
        collect(emitter, 90);
        // A beat reached the wire, so hasEmitted() must be true - the
        // controller's mid-stream-error gate depends on this being honest
        // (see hasEmitted()'s javadoc).
        assertTrue(emitter.hasEmitted());
    }

    @Test
    void disabledEmitterNeverHeartbeats() {
        AgentStepEventEmitter disabled = AgentStepEventEmitter.disabled();
        disabled.runStarted("s", UUID.randomUUID());
        disabled.complete();
        assertEquals(0, disabled.asFlux().count().block());
    }

    @Test
    void unstartedEmitterNeverTicks() throws InterruptedException {
        // A synchronous failure before the token stream is subscribed must not
        // leave a live interval behind - runStarted is the only arming point.
        AgentStepEventEmitter emitter =
                AgentStepEventEmitter.active(Duration.ofMillis(50), Schedulers.parallel());
        Thread.sleep(160);
        emitter.complete();
        assertEquals(0, emitter.asFlux().count().block());
    }

    private static String type(String chunk) {
        int i = chunk.indexOf("\"type\":\"");
        return i < 0 ? null : chunk.substring(i + 8, chunk.indexOf('"', i + 8));
    }
}
