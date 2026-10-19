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

class LessonLearningGatewayTest {

    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void uninstall() {
        LessonLearningGateway.install(null);
    }

    private static Flux<String> observe(Flux<String> turn) {
        return LessonLearningGateway.observe(
                turn, AGENT, "s1", "do the thing", Instant.now(),
                Optional.of(TenantContext.of("acme", "alice", Set.of())));
    }

    private static final class Recorder implements LessonLearningGateway.Observer {
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
    void withNoObserverOrADisabledOneTheTurnIsReturnedUntouched() {
        Flux<String> turn = Flux.just("a", "b");
        assertSame(turn, observe(turn));
        Recorder off = new Recorder(false);
        LessonLearningGateway.install(off);
        assertSame(turn, observe(turn));
        assertFalse(LessonLearningGateway.active());
        assertTrue(off.seen.isEmpty());
    }

    @Test
    void anEnabledObserverSeesTheFinishedTurnAndTheReplyIsUnchanged() {
        Recorder on = new Recorder(true);
        LessonLearningGateway.install(on);
        Flux<String> turn = Flux.just("Hello", ", ", "world");
        Flux<String> observed = observe(turn);
        assertNotSame(turn, observed);
        assertEquals(List.of("Hello", ", ", "world"), observed.collectList().block());
        assertEquals(1, on.seen.size());
        SkillLearningGateway.TurnOutcome o = on.seen.get(0);
        assertEquals("Hello, world", o.answer());
        assertEquals("do the thing", o.userMessage());
        assertTrue(o.completed());
        assertEquals("acme", o.scope().context().orElseThrow().getTenantId());
    }

    @Test
    void anErroredTurnIsReportedAsNotCompletedAndAFailingObserverNeverBreaksIt() {
        Recorder on = new Recorder(true);
        LessonLearningGateway.install(on);
        try {
            observe(Flux.concat(Flux.just("part"), Flux.error(new IllegalStateException("boom")))).collectList().block();
        } catch (IllegalStateException expected) {
            // the error still reaches the caller
        }
        assertFalse(on.seen.get(0).completed());

        LessonLearningGateway.install(
                new LessonLearningGateway.Observer() {
                    @Override
                    public boolean enabled() {
                        return true;
                    }

                    @Override
                    public void onTurn(SkillLearningGateway.TurnOutcome outcome) {
                        throw new IllegalStateException("observer bug");
                    }
                });
        assertEquals(List.of("x"), observe(Flux.just("x")).collectList().block());
    }
}
