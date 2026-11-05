package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A merge or summary the nightly consolidation filed (writer label + lineage) is a PENDING proposal
 * that never changes its sources by itself; approving it, by a person, supersedes them. Other
 * derived items never do.
 */
class ConsolidationProposalApprovalTest {

    private StoreFixture f;
    private final MemoryActor alice = MemoryActor.human("alice");

    @BeforeEach
    void setUp() {
        f = new StoreFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private UUID propose(String writer, MemoryKind kind, String text, UUID... sources) {
        return f.sink.proposeDetailed(
                        new MemoryWriteProposal(
                                f.agent,
                                null,
                                kind,
                                text,
                                new MemoryProvenanceRecord(
                                        MemoryTrustLevel.TRUSTED, List.of(), writer),
                                null,
                                true,
                                java.util.Arrays.stream(sources)
                                        .map(MemorySourceRef::item)
                                        .toList()))
                .receipt()
                .id();
    }

    @Test
    void aSummaryProposalIsPendingWithLineageAndTouchesNoSourceUntilAPersonApproves() {
        UUID a = f.active(MemoryKind.EPISODIC, "episode one");
        UUID b = f.active(MemoryKind.EPISODIC, "episode two");

        UUID summary =
                propose(
                        MemoryProvenanceRecord.WRITER_CONSOLIDATION_SUMMARY,
                        MemoryKind.EPISODIC,
                        "Summary of 2 notes: one; two",
                        a,
                        b);

        MemoryItem row = f.storage.items.get(summary);
        assertThat(row.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(row.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(row.writer()).isEqualTo(MemoryWriter.DISTILLER);
        assertThat(row.sourceType()).isEqualTo(MemoryItem.SOURCE_CONSOLIDATION_SUMMARY);
        assertThat(row.generation()).isEqualTo(1);
        assertThat(f.storage.lineage(summary))
                .extracting(MemoryItemSource::sourceKey)
                .containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(f.storage.items.get(a).status())
                .as("filing changes nothing")
                .isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.items.get(b).status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.listByStatus(f.agent, MemoryItemStatus.ACTIVE, 10))
                .extracting(MemoryItem::id)
                .doesNotContain(summary);

        assertThat(f.review.approve(f.agent, summary, alice).ok()).isTrue();

        assertThat(f.storage.items.get(summary).status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.items.get(a).status()).isEqualTo(MemoryItemStatus.SUPERSEDED);
        assertThat(f.storage.items.get(b).status()).isEqualTo(MemoryItemStatus.SUPERSEDED);
        assertThat(f.storage.events(a))
                .extracting(MemoryEvent::event)
                .contains(MemoryEvent.Type.EDITED);
        assertThat(f.storage.events(a).get(f.storage.events(a).size() - 1).actor())
                .isEqualTo(alice);
        assertThat(f.storage.lineage(summary))
                .as("lineage survives, so erasing a source still reaches the summary")
                .hasSize(2);
    }

    @Test
    void rejectingAMergeProposalLeavesTheSourcesActive() {
        UUID keeper = f.active(MemoryKind.PROCEDURAL, "check the PSP first");
        UUID dup = f.active(MemoryKind.PROCEDURAL, "check the PSP first, always");
        UUID merge =
                propose(
                        MemoryProvenanceRecord.WRITER_CONSOLIDATION_MERGE,
                        MemoryKind.PROCEDURAL,
                        "check the PSP first",
                        keeper,
                        dup);
        assertThat(f.storage.items.get(merge).sourceType())
                .isEqualTo(MemoryItem.SOURCE_CONSOLIDATION_MERGE);

        assertThat(f.review.reject(f.agent, merge, alice).ok()).isTrue();

        assertThat(f.storage.items.get(keeper).status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.items.get(dup).status()).isEqualTo(MemoryItemStatus.ACTIVE);
    }

    @Test
    void anOrdinaryDerivedItemNeverSupersedesItsSources() {
        UUID src = f.active(MemoryKind.EPISODIC, "an episode");
        UUID other = f.active(MemoryKind.EPISODIC, "another episode");
        UUID distilled = propose(null, MemoryKind.PROCEDURAL, "a distilled procedure", src, other);
        assertThat(f.storage.items.get(distilled).sourceType()).doesNotStartWith("CONSOL");

        assertThat(f.review.approve(f.agent, distilled, alice).ok()).isTrue();

        assertThat(f.storage.items.get(src).status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.items.get(other).status()).isEqualTo(MemoryItemStatus.ACTIVE);
    }

    @Test
    void theConsolidationLabelWithoutLineageIsNotAConsolidationProposal() {
        UUID id =
                propose(
                        MemoryProvenanceRecord.WRITER_CONSOLIDATION_SUMMARY,
                        MemoryKind.EPISODIC,
                        "no sources at all");
        assertThat(f.storage.items.get(id).sourceType()).doesNotStartWith("CONSOL");
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.PENDING);
    }

    @Test
    void aSourceThatIsNoLongerActiveIsNotTouchedAndTheApprovalStands() {
        UUID a = f.active(MemoryKind.EPISODIC, "kept episode");
        UUID b = f.active(MemoryKind.EPISODIC, "reverted episode");
        UUID summary =
                propose(
                        MemoryProvenanceRecord.WRITER_CONSOLIDATION_SUMMARY,
                        MemoryKind.EPISODIC,
                        "Summary of 2 notes",
                        a,
                        b);
        assertThat(f.review.revert(f.agent, b, alice).ok()).isTrue();

        assertThat(f.review.approve(f.agent, summary, alice).ok()).isTrue();

        assertThat(f.storage.items.get(a).status()).isEqualTo(MemoryItemStatus.SUPERSEDED);
        assertThat(f.storage.items.get(b).status())
                .as("reverted stays reverted")
                .isEqualTo(MemoryItemStatus.REVERTED);
    }
}
