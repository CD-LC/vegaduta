package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryItemReviewServiceTest {

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

    private UUID pending(MemoryKind kind, String text) {
        return f.sink.propose(f.proposal(kind, text)).orElseThrow().id();
    }

    @Test
    void approveIsTheOnlyPathToTrustedActiveAndRecordsTheApprover() {
        UUID id = pending(MemoryKind.PROCEDURAL, "check PSP first");
        MemoryItemReviewService.Outcome o = f.review.approve(f.agent, id, alice);
        assertThat(o.ok()).isTrue();
        MemoryItem row = f.storage.items.get(id);
        assertThat(row.status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(row.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(row.approvedBy()).isEqualTo("alice");
        assertThat(row.approvedAt()).isEqualTo(f.clock.instant());
        assertThat(f.storage.events(id)).extracting(MemoryEvent::event)
                .containsExactly(MemoryEvent.Type.CREATED, MemoryEvent.Type.APPROVED);
    }

    @Test
    void onlyAnIdentifiedHumanMayDecide() {
        UUID id = pending(MemoryKind.EPISODIC, "e");
        for (MemoryActor bad :
                new MemoryActor[] {
                    MemoryActor.agent("agent-1"), MemoryActor.system("distiller"), MemoryActor.human(null), MemoryActor.human(" "), null
                }) {
            assertThat(f.review.approve(f.agent, id, bad).refusal()).isEqualTo("NOT_HUMAN");
            assertThat(f.review.reject(f.agent, id, bad).refusal()).isEqualTo("NOT_HUMAN");
            assertThat(f.review.edit(f.agent, id, "z", bad).refusal()).isEqualTo("NOT_HUMAN");
            assertThat(f.review.delete(f.agent, id, bad).refusal()).isEqualTo("NOT_HUMAN");
        }
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.PENDING);
    }

    @Test
    void aTaintedItemCanBeApprovedOnlyWithAnApproverOnTheRow() {
        UUID id =
                f.sink
                        .propose(
                                new MemoryWriteProposal(
                                        f.agent,
                                        "u",
                                        MemoryKind.EPISODIC,
                                        "from a web page",
                                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("web"), null),
                                        null,
                                        false))
                        .orElseThrow()
                        .id();
        assertThat(f.review.approve(f.agent, id, alice).ok()).isTrue();
        MemoryItem row = f.storage.items.get(id);
        assertThat(row.sourceTaint()).containsExactly("web");
        assertThat(row.approvedBy()).isEqualTo("alice");
    }

    @Test
    void approvalOfADerivedItemDoesNotApproveItsSources() {
        UUID src = f.active(MemoryKind.EPISODIC, "episode");
        UUID pendingSrc = pending(MemoryKind.EPISODIC, "other episode");
        UUID derived =
                f.sink
                        .propose(
                                new MemoryWriteProposal(
                                        f.agent,
                                        "user-1",
                                        MemoryKind.PROCEDURAL,
                                        "d",
                                        null,
                                        null,
                                        true,
                                        List.of(
                                                com.agenticai.core.agents.memory.spi.MemorySourceRef.item(src),
                                                com.agenticai.core.agents.memory.spi.MemorySourceRef.item(pendingSrc))))
                        .orElseThrow()
                        .id();
        assertThat(f.review.approve(f.agent, derived, alice).ok()).isTrue();
        assertThat(f.storage.items.get(pendingSrc).status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(f.storage.items.get(pendingSrc).trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void onlyPendingItemsCanBeApprovedAndNotTwice() {
        UUID id = pending(MemoryKind.EPISODIC, "e");
        assertThat(f.review.approve(f.agent, id, alice).ok()).isTrue();
        assertThat(f.review.approve(f.agent, id, alice).refusal()).isEqualTo("NOT_PENDING");
        UUID rejected = pending(MemoryKind.EPISODIC, "r");
        f.review.reject(f.agent, rejected, alice);
        assertThat(f.review.approve(f.agent, rejected, alice).refusal()).isEqualTo("NOT_PENDING");
    }

    @Test
    void approvalRefusesPastTheGenerationCapAndExpiredItems() {
        UUID id = pending(MemoryKind.EPISODIC, "model written"); // modelGenerated=false -> generation 0
        f.props.setMaxGeneration(0);
        assertThat(f.review.approve(f.agent, id, alice).ok()).isTrue();
        MemoryWriteProposal model =
                new MemoryWriteProposal(f.agent, "u", MemoryKind.EPISODIC, "m", null, null, true); // generation 1
        // cap 0 refuses a model-written proposal outright
        assertThat(f.sink.proposeDetailed(model).refusal()).isEqualTo(MemoryTrustPolicy.GENERATION_CAP);
        f.props.setMaxGeneration(1);
        UUID gen1 = f.sink.propose(model).orElseThrow().id();
        f.props.setMaxGeneration(0);
        assertThat(f.review.approve(f.agent, gen1, alice).refusal()).isEqualTo(MemoryTrustPolicy.GENERATION_CAP);

        f.props.setMaxGeneration(2);
        UUID soon = pending(MemoryKind.EPISODIC, "will expire");
        f.props.setEpisodicTtlDays(1);
        MemoryItem row = f.storage.items.get(soon);
        MemoryItem expired =
                MemoryItem.pending(row.id(), row.agentId(), row.kind(), row.content(), row.writer(), null, null, null,
                        List.of(), false, 0, row.subjectKeys(), 1, null, row.createdAt(), f.clock.instant().minusSeconds(1));
        f.storage.put(expired, StoreFixture.vector("x"));
        assertThat(f.review.approve(f.agent, soon, alice).refusal()).isEqualTo("EXPIRED");
    }

    @Test
    void rejectAndRevertMoveStatusWithEvents() {
        UUID a = pending(MemoryKind.EPISODIC, "a");
        assertThat(f.review.reject(f.agent, a, alice).ok()).isTrue();
        assertThat(f.storage.items.get(a).status()).isEqualTo(MemoryItemStatus.REJECTED);
        assertThat(f.review.reject(f.agent, a, alice).refusal()).isEqualTo("NOT_PENDING");

        UUID b = f.active(MemoryKind.EPISODIC, "b");
        assertThat(f.review.revert(f.agent, b, alice).ok()).isTrue();
        assertThat(f.storage.items.get(b).status()).isEqualTo(MemoryItemStatus.REVERTED);
        assertThat(f.storage.events(b)).extracting(MemoryEvent::event).contains(MemoryEvent.Type.REVERTED);
        assertThat(f.review.revert(f.agent, a, alice).refusal()).isEqualTo("NOT_ACTIVE");
    }

    @Test
    void editMakesANewPendingVersionKeepingTaintAndLineage() {
        UUID id = f.active(MemoryKind.PROCEDURAL, "old steps");
        MemoryItemReviewService.Outcome o = f.review.edit(f.agent, id, "new steps", alice);
        assertThat(o.ok()).isTrue();
        MemoryItem next = f.storage.items.get(o.itemId());
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.supersedesId()).isEqualTo(id);
        assertThat(next.writer()).isEqualTo(MemoryWriter.HUMAN);
        assertThat(next.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(next.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(f.storage.lineage(next.id())).extracting(MemoryItemSource::sourceKey).containsExactly(id.toString());
        // the old ACTIVE row is untouched until the new version is approved
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.ACTIVE);
        assertThat(f.storage.items.get(id).content()).isEqualTo("old steps");
        assertThat(f.review.approve(f.agent, next.id(), alice).ok()).isTrue();
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.SUPERSEDED);
    }

    @Test
    void editingAPendingItemSupersedesItAtOnceAndIsRescreened() {
        UUID id = pending(MemoryKind.EPISODIC, "draft");
        assertThat(f.review.edit(f.agent, id, "x".repeat(5000), alice).refusal()).isEqualTo("SCREEN_TOO_LONG");
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(f.review.edit(f.agent, id, "final", alice).ok()).isTrue();
        assertThat(f.storage.items.get(id).status()).isEqualTo(MemoryItemStatus.SUPERSEDED);
    }

    @Test
    void deleteIsHardAndLeavesATextFreeEvent() {
        UUID id = f.active(MemoryKind.EPISODIC, "secret fact about Priya");
        assertThat(f.review.delete(f.agent, id, alice).ok()).isTrue();
        assertThat(f.storage.items).doesNotContainKey(id);
        assertThat(f.storage.events(id)).extracting(MemoryEvent::event).contains(MemoryEvent.Type.ERASED);
        assertThat(f.storage.events.toString()).doesNotContain("Priya").doesNotContain("secret");
        assertThat(f.review.delete(f.agent, id, alice).refusal()).isEqualTo("NOT_FOUND");
    }

    @Test
    void otherAgentsAndMissingTenantAreRefused() {
        UUID id = pending(MemoryKind.EPISODIC, "e");
        assertThat(f.review.approve(UUID.randomUUID(), id, alice).refusal()).isEqualTo("NOT_FOUND");
        TenantContext.clear();
        assertThat(f.review.approve(f.agent, id, alice).refusal()).isEqualTo("NO_TENANT");
        assertThat(f.review.pending(f.agent, 10)).isEmpty();
    }

    @Test
    void reviewWorksWithTheFeatureFlagOff() {
        UUID id = pending(MemoryKind.EPISODIC, "e");
        f.props.setEnabled(false);
        assertThat(f.review.reject(f.agent, id, alice).ok()).isTrue();
    }
}
