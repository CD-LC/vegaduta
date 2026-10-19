package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class SkillLearningGatewayTest {

    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void uninstall() {
        SkillLearningGateway.install(null);
    }

    private static Flux<String> observe(Flux<String> turn) {
        return SkillLearningGateway.observe(
                turn,
                AGENT,
                "s1",
                "do the thing",
                Instant.now(),
                Optional.of(TenantContext.of("acme", "alice", Set.of())));
    }

    private static final class Recorder implements SkillLearningGateway.Observer {
        final boolean enabled;
        final List<SkillLearningGateway.TurnOutcome> seen = new ArrayList<>();

        Recorder(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void onTurn(SkillLearningGateway.TurnOutcome outcome) {
            seen.add(outcome);
        }
    }

    @Test
    void withNoObserverTheTurnIsReturnedUntouched() {
        Flux<String> turn = Flux.just("a", "b");
        assertSame(turn, observe(turn));
        assertFalse(SkillLearningGateway.active());
    }

    @Test
    void aDisabledObserverAddsNoOperatorToTheTurn() {
        Recorder off = new Recorder(false);
        SkillLearningGateway.install(off);
        Flux<String> turn = Flux.just("a");
        assertSame(turn, observe(turn));
        assertFalse(SkillLearningGateway.active());
        assertTrue(off.seen.isEmpty());
    }

    @Test
    void anEnabledObserverSeesTheFinishedTurnAndTheReplyIsUnchanged() {
        Recorder on = new Recorder(true);
        SkillLearningGateway.install(on);
        Flux<String> turn = Flux.just("Hello", ", ", "world");
        Flux<String> observed = observe(turn);
        assertNotSame(turn, observed);
        assertEquals(List.of("Hello", ", ", "world"), observed.collectList().block());

        assertEquals(1, on.seen.size());
        SkillLearningGateway.TurnOutcome o = on.seen.get(0);
        assertEquals("Hello, world", o.answer());
        assertEquals(AGENT, o.agentId());
        assertEquals("s1", o.sessionId());
        assertTrue(o.completed());
        assertFalse(o.cancelled());
        // The tenant travels as data, not as whatever the finishing thread happens to hold.
        assertTrue(o.scope().context().isPresent());
        assertEquals("acme", o.scope().context().get().getTenantId());
    }

    @Test
    void anErroredTurnIsReportedAsNotCompleted() {
        Recorder on = new Recorder(true);
        SkillLearningGateway.install(on);
        Flux<String> failing = Flux.concat(Flux.just("part"), Flux.error(new IllegalStateException("boom")));
        try {
            observe(failing).collectList().block();
        } catch (IllegalStateException expected) {
            // the error still reaches the caller
        }
        assertEquals(1, on.seen.size());
        assertFalse(on.seen.get(0).completed());
        assertFalse(on.seen.get(0).cancelled());
        assertEquals("part", on.seen.get(0).answer());
    }

    @Test
    void aCancelledTurnIsReportedAsCancelled() {
        Recorder on = new Recorder(true);
        SkillLearningGateway.install(on);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        var sub = observe(sink.asFlux()).subscribe();
        sink.tryEmitNext("partial");
        sub.dispose();
        assertEquals(1, on.seen.size());
        assertTrue(on.seen.get(0).cancelled());
        assertFalse(on.seen.get(0).completed());
    }

    @Test
    void theKeptAnswerIsBounded() {
        Recorder on = new Recorder(true);
        SkillLearningGateway.install(on);
        String chunk = "x".repeat(10_000);
        observe(Flux.just(chunk, chunk, chunk, chunk, chunk)).blockLast();
        int kept = on.seen.get(0).answer().length();
        assertTrue(kept < 5 * 10_000, "kept " + kept);
        assertTrue(kept <= SkillLearningGateway.MAX_ANSWER_CHARS + 10_000);
    }

    @Test
    void aFailingObserverNeverBreaksTheTurn() {
        SkillLearningGateway.install(
                new SkillLearningGateway.Observer() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public void onTurn(SkillLearningGateway.TurnOutcome outcome) {
                        throw new IllegalStateException("observer bug");
                    }
                });
        assertEquals(List.of("ok"), observe(Flux.just("ok")).collectList().block());
    }
}
