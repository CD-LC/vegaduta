package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memorystore.MemoryTrustPolicy.Derivation;
import com.agenticai.core.agents.memorystore.MemoryTrustPolicy.SourceFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryTrustPolicyTest {

    private static SourceFacts src(MemoryTrustLevel trust, List<String> taint, MemoryItemStatus st, boolean model,
            boolean confirmed, int gen) {
        return new SourceFacts(trust, taint, st, model, confirmed, gen);
    }

    private static SourceFacts cleanEvent() {
        return src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.ACTIVE, false, true, 0);
    }

    @Test
    void meetIsTheLowestTrust() {
        assertThat(MemoryTrustLevel.meet(List.of(MemoryTrustLevel.TRUSTED, MemoryTrustLevel.TRUSTED)))
                .isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(MemoryTrustLevel.meet(List.of(MemoryTrustLevel.TRUSTED, MemoryTrustLevel.QUARANTINED)))
                .isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(MemoryTrustLevel.meet(List.of())).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void provenanceDeriveTakesMeetAndTaintUnion() {
        MemoryProvenanceRecord d =
                MemoryProvenanceRecord.derive(
                        List.of(
                                new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("a"), null),
                                new MemoryProvenanceRecord(MemoryTrustLevel.QUARANTINED, List.of("b", "a"), null)),
                        "distiller");
        assertThat(d.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(d.sourceTaint()).containsExactly("a", "b");
        assertThat(MemoryProvenanceRecord.derive(List.of(), "x").trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void oneQuarantinedSourceQuarantinesTheDerivedItem() {
        Derivation d =
                MemoryTrustPolicy.derive(
                        List.of(
                                cleanEvent(),
                                src(MemoryTrustLevel.QUARANTINED, List.of(), MemoryItemStatus.ACTIVE, false, true, 0)),
                        true,
                        2);
        assertThat(d.refused()).isFalse();
        assertThat(d.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(d.proposalStatus()).isEqualTo(MemoryProposalStatus.QUARANTINED);
    }

    @Test
    void aPendingSourceCountsAsQuarantinedEvenIfItsTrustColumnSaysTrusted() {
        Derivation d =
                MemoryTrustPolicy.derive(
                        List.of(src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.PENDING, false, true, 0)),
                        true,
                        2);
        assertThat(d.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }

    @Test
    void taintIsTheUnionAndTaintAloneQuarantinesTheVerdict() {
        Derivation d =
                MemoryTrustPolicy.derive(
                        List.of(
                                src(MemoryTrustLevel.TRUSTED, List.of("web"), MemoryItemStatus.ACTIVE, false, true, 0),
                                src(MemoryTrustLevel.TRUSTED, List.of("mail", "web"), MemoryItemStatus.ACTIVE, false, true, 0)),
                        true,
                        2);
        assertThat(d.sourceTaint()).containsExactly("web", "mail");
        assertThat(d.trust()).isEqualTo(MemoryTrustLevel.TRUSTED);
        assertThat(d.proposalStatus()).isEqualTo(MemoryProposalStatus.QUARANTINED);
    }

    @Test
    void generationIsMaxOfSourcesPlusOne() {
        Derivation d =
                MemoryTrustPolicy.derive(
                        List.of(
                                cleanEvent(),
                                src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.ACTIVE, false, true, 1)),
                        true,
                        2);
        assertThat(d.generation()).isEqualTo(2);
        assertThat(d.modelGenerated()).isTrue();
        assertThat(MemoryTrustPolicy.derive(List.of(), false, 2).generation()).isZero();
        assertThat(MemoryTrustPolicy.derive(List.of(), true, 2).generation()).isEqualTo(1);
    }

    @Test
    void pastTheCapIsRefused() {
        Derivation d =
                MemoryTrustPolicy.derive(
                        List.of(src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.ACTIVE, false, true, 2)),
                        true,
                        2);
        assertThat(d.refusal()).isEqualTo(MemoryTrustPolicy.GENERATION_CAP);
        assertThat(MemoryTrustPolicy.derive(List.of(), true, 0).refusal()).isEqualTo(MemoryTrustPolicy.GENERATION_CAP);
    }

    @Test
    void aModelGeneratedSourceIsRefusedUnlessAHumanConfirmedIt() {
        SourceFacts unconfirmed =
                src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.ACTIVE, true, false, 1);
        SourceFacts confirmed = src(MemoryTrustLevel.TRUSTED, List.of(), MemoryItemStatus.ACTIVE, true, true, 1);
        assertThat(MemoryTrustPolicy.derive(List.of(unconfirmed), true, 3).refusal())
                .isEqualTo(MemoryTrustPolicy.MODEL_GENERATED_SOURCE);
        assertThat(MemoryTrustPolicy.derive(List.of(confirmed), true, 3).refused()).isFalse();
    }

    @Test
    void deadSourcesAreNotUsable() {
        for (MemoryItemStatus st :
                List.of(MemoryItemStatus.REJECTED, MemoryItemStatus.REVERTED, MemoryItemStatus.SUPERSEDED)) {
            assertThat(
                            MemoryTrustPolicy.derive(
                                            List.of(src(MemoryTrustLevel.TRUSTED, List.of(), st, false, true, 0)),
                                            true,
                                            2)
                                    .refusal())
                    .isEqualTo(MemoryTrustPolicy.SOURCE_NOT_USABLE);
        }
    }

    @Test
    void externalSourceWithoutTrustIsQuarantined() {
        Derivation d = MemoryTrustPolicy.derive(List.of(SourceFacts.external(null, null)), true, 2);
        assertThat(d.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
    }
}
