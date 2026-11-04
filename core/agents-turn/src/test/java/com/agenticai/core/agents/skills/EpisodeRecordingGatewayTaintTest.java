package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.memory.TurnTaintSnapshot;
import com.agenticai.core.guardrails.taint.TurnTaint;
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

/** The finished turn's taint reaches the episode observer as a read-only snapshot taken when the turn ends. */
class EpisodeRecordingGatewayTaintTest {

    private static final UUID AGENT = UUID.randomUUID();

    private final List<SkillLearningGateway.TurnOutcome> seen = new ArrayList<>();

    @AfterEach
    void uninstall() {
        EpisodeRecordingGateway.install(null);
    }

    private void install() {
        EpisodeRecordingGateway.install(new EpisodeRecordingGateway.Observer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void onTurn(SkillLearningGateway.TurnOutcome outcome) {
                seen.add(outcome);
            }
        });
    }

    private Flux<String> run(TurnTaint taint) {
        return TurnCompletionObservers.observe(
                Flux.just("a", "b"),
                AGENT,
                "s1",
                "hello",
                Instant.now(),
                Optional.of(TenantContext.of("acme", "alice", Set.of())),
                taint);
    }

    @Test
    void aCleanTurnCarriesAReadableCleanSnapshot() {
        install();
        run(new TurnTaint()).blockLast();
        TurnTaintSnapshot s = seen.get(0).taint();
        assertNotNull(s);
        assertTrue(s.readable());
        assertFalse(s.isTainted());
    }

    @Test
    void marksMadeDuringTheTurnCountBecauseTheSnapshotIsTakenAtTheEnd() {
        install();
        TurnTaint taint = new TurnTaint();
        Flux<String> turn = run(taint).doOnNext(x -> taint.mark("fetch_web_page"));
        turn.blockLast();
        TurnTaintSnapshot s = seen.get(0).taint();
        assertTrue(s.isTainted());
        assertEquals(List.of("fetch_web_page"), s.sources());
    }

    @Test
    void noRecordMeansAnUnreadableSnapshot() {
        install();
        run(null).blockLast();
        assertSame(TurnTaintSnapshot.unreadable(), seen.get(0).taint());
    }

    @Test
    void theOldEntryPointAndOutcomeConstructorCarryNoTaintState() {
        install();
        TurnCompletionObservers.observe(
                        Flux.just("a"), AGENT, "s1", "hello", Instant.now(), Optional.empty())
                .blockLast();
        assertSame(TurnTaintSnapshot.unreadable(), seen.get(0).taint());
        SkillLearningGateway.TurnOutcome plain =
                new SkillLearningGateway.TurnOutcome(AGENT, "s", null, null, Instant.now(), true, false, null);
        assertEquals(null, plain.taint());
    }
}
