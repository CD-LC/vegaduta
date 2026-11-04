package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.security.TenantContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryItemStoreRecallTest {

    private StoreFixture f;

    @BeforeEach
    void setUp() {
        f = new StoreFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private MemoryRecallResult recall(String subject, Set<MemoryKind> kinds) {
        return f.store.recall(new MemoryRecallRequest(f.agent, subject, "q", kinds, 8, 800));
    }

    @Test
    void onlyActiveTrustedRowsAreRecalled() {
        UUID active = f.active(MemoryKind.EPISODIC, "approved");
        f.sink.propose(f.proposal(MemoryKind.EPISODIC, "pending one"));
        UUID rejected = f.sink.propose(f.proposal(MemoryKind.EPISODIC, "rejected one")).orElseThrow().id();
        f.review.reject(f.agent, rejected, MemoryActor.human("alice"));
        UUID reverted = f.active(MemoryKind.EPISODIC, "reverted one");
        f.review.revert(f.agent, reverted, MemoryActor.human("alice"));

        MemoryRecallResult r = recall("user-1", Set.of());
        assertThat(r.items()).extracting(v -> v.id()).containsExactly(active);
        assertThat(r.degradedReason()).isNull();
    }

    @Test
    void expiredRowsAreNotRecalled() {
        UUID id = f.active(MemoryKind.EPISODIC, "old");
        MemoryItem row = f.storage.items.get(id);
        f.storage.put(
                new MemoryItem(row.id(), row.agentId(), row.kind(), row.content(), row.trust(), row.status(), row.writer(),
                        null, null, null, row.sourceTaint(), false, 0, row.subjectKeys(), 1, null, row.createdAt(),
                        f.clock.instant().minusSeconds(5), row.approvedBy(), row.approvedAt()),
                StoreFixture.vector("old"));
        assertThat(recall("user-1", Set.of()).items()).isEmpty();
    }

    @Test
    void episodesAreSubjectScopedButProceduresAreAgentWide() {
        f.active(MemoryKind.EPISODIC, "episode of user-1");
        f.active(MemoryKind.PROCEDURAL, "how to refund");
        f.active(MemoryKind.SELF_MODEL, "good at refunds");

        assertThat(recall("user-2", Set.of()).items())
                .extracting(v -> v.kind())
                .containsExactlyInAnyOrder(MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL);
        assertThat(recall(null, Set.of()).items()).extracting(v -> v.kind()).doesNotContain(MemoryKind.EPISODIC);
        assertThat(recall("user-1", Set.of()).items()).extracting(v -> v.kind()).contains(MemoryKind.EPISODIC);
    }

    @Test
    void anotherAgentsRowsAreNeverReturned() {
        f.active(MemoryKind.PROCEDURAL, "mine");
        MemoryRecallResult r =
                f.store.recall(new MemoryRecallRequest(UUID.randomUUID(), "user-1", "q", Set.of(), 8, 800));
        assertThat(r.items()).isEmpty();
    }

    @Test
    void offKindsAndMissingTenantAreEmpty() {
        f.active(MemoryKind.PROCEDURAL, "p");
        f.props.setKinds(EnumSet.of(MemoryKind.EPISODIC));
        assertThat(recall("user-1", Set.of()).items()).extracting(v -> v.kind()).doesNotContain(MemoryKind.PROCEDURAL);
        f.props.setKinds(EnumSet.of(MemoryKind.PROCEDURAL));
        TenantContext.clear();
        assertThat(recall("user-1", Set.of()).degradedReason()).isEqualTo("no-tenant");
        TenantContext.set(TenantContext.of("tenant_a", "t", Set.of()));
        f.props.setEnabled(false);
        assertThat(recall("user-1", Set.of()).degradedReason()).isEqualTo("disabled");
        f.props.setEnabled(true);
        f.overrideAllows = false;
        assertThat(recall("user-1", Set.of()).degradedReason()).isEqualTo("disabled");
    }

    @Test
    void storeErrorsDegradeInsteadOfThrowing() {
        f.active(MemoryKind.PROCEDURAL, "p");
        f.embedder = t -> {
            throw new IllegalStateException("down");
        };
        MemoryRecallResult r = recall("user-1", Set.of());
        assertThat(r.items()).isEmpty();
        assertThat(r.degradedReason()).isEqualTo("error");
    }

    @Test
    void kindsAreTheThreeNewOnes() {
        assertThat(f.store.kinds()).containsExactlyInAnyOrder(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL);
        assertThat(List.copyOf(f.store.kinds())).doesNotContain(MemoryKind.SEMANTIC);
    }
}
