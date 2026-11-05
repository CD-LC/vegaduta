package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryItemStorage#eraseSubject}: a data subject's erasure removes every item about them across agents and
 * statuses, everything derived from those items, and leaves only content-free ERASED events (ADR-0028 section 12).
 */
class MemorySubjectErasureTest {

    private final StoreFixture f = new StoreFixture();

    @AfterEach
    void close() {
        f.close();
    }

    private UUID propose(UUID agent, String subject, String text, List<MemorySourceRef> sources) {
        return f.sink
                .proposeDetailed(new MemoryWriteProposal(
                        agent, subject, MemoryKind.EPISODIC, text, MemoryProvenanceRecord.TRUSTED, null, false, sources))
                .receipt()
                .id();
    }

    @Test
    void erasesEverythingAboutTheSubjectAndTheDerivedSummariesButNobodyElse() {
        UUID otherAgent = UUID.randomUUID();
        UUID pending = propose(f.agent, "alice", "alice episode one", List.of());
        UUID active = f.active(MemoryKind.EPISODIC, "bob episode");
        UUID second = propose(otherAgent, "alice", "alice episode on another agent", List.of());
        UUID derived = propose(f.agent, "alice", "summary of alice", List.of(MemorySourceRef.item(pending)));

        List<UUID> erased = f.storage.eraseSubject("alice", MemoryActor.system("subject-erasure"), Instant.now());

        assertThat(erased).containsExactlyInAnyOrder(pending, second, derived);
        assertThat(f.storage.find(f.agent, pending)).isEmpty();
        assertThat(f.storage.find(otherAgent, second)).isEmpty();
        assertThat(f.storage.find(f.agent, derived)).isEmpty();
        assertThat(f.storage.lineage(derived)).isEmpty();
        assertThat(f.storage.listForSubject("alice", 10)).isEmpty();
        assertThat(f.storage.listForSubject("user-1", 10)).as("an unrelated subject keeps its rows").isNotEmpty();
        assertThat(f.storage.find(f.agent, active)).as("bob's item is untouched").isPresent();
        for (UUID id : erased) {
            assertThat(f.storage.events(id))
                    .as("only a content-free ERASED tombstone outlives the row")
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.event()).isEqualTo(MemoryEvent.Type.ERASED);
                        assertThat(e.actor().ref()).isEqualTo("subject-erasure");
                    });
        }
    }

    @Test
    void blankOrUnknownSubjectErasesNothing() {
        propose(f.agent, "alice", "alice episode", List.of());
        assertThat(f.storage.eraseSubject(" ", MemoryActor.system("x"), Instant.now())).isEmpty();
        assertThat(f.storage.eraseSubject(null, MemoryActor.system("x"), Instant.now())).isEmpty();
        assertThat(f.storage.eraseSubject("nobody", MemoryActor.system("x"), Instant.now())).isEmpty();
        assertThat(f.storage.listForSubject("alice", 10)).hasSize(1);
    }
}
