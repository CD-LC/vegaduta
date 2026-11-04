package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryProposalSinkTest {

    private static MemoryWriteProposal proposal(MemoryKind kind, MemoryProvenanceRecord prov) {
        return new MemoryWriteProposal(UUID.randomUUID(), "u1", kind, "text", prov, null, false);
    }

    @Test
    void statusTypeHasNoActiveValue() {
        assertThat(Arrays.stream(MemoryProposalStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("PENDING", "QUARANTINED");
    }

    @Test
    void noKindAndNoProvenanceCombinationYieldsAnythingButPendingOrQuarantined() {
        List<MemoryProvenanceRecord> provenances =
                List.of(
                        MemoryProvenanceRecord.TRUSTED,
                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("web"), "x"),
                        new MemoryProvenanceRecord(MemoryTrustLevel.QUARANTINED, List.of(), null),
                        new MemoryProvenanceRecord(null, null, null));
        for (MemoryKind kind : MemoryKind.values()) {
            for (MemoryProvenanceRecord p : provenances) {
                assertThat(MemoryProposalPolicy.statusFor(proposal(kind, p)))
                        .isIn(MemoryProposalStatus.PENDING, MemoryProposalStatus.QUARANTINED);
            }
        }
    }

    @Test
    void cleanIsPendingTaintedOrUnknownIsQuarantined() {
        assertThat(MemoryProposalPolicy.statusFor(proposal(MemoryKind.PROCEDURAL, MemoryProvenanceRecord.TRUSTED)))
                .isEqualTo(MemoryProposalStatus.PENDING);
        assertThat(
                        MemoryProposalPolicy.statusFor(
                                proposal(
                                        MemoryKind.SEMANTIC,
                                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of("web"), null))))
                .isEqualTo(MemoryProposalStatus.QUARANTINED);
        assertThat(
                        MemoryProposalPolicy.statusFor(
                                proposal(MemoryKind.SEMANTIC, new MemoryProvenanceRecord(null, null, null))))
                .isEqualTo(MemoryProposalStatus.QUARANTINED);
    }

    @Test
    void sinkPortExposesOnlyProposeReturningAReceipt() {
        Method[] methods = MemoryProposalSink.class.getDeclaredMethods();
        assertThat(methods).hasSize(1);
        assertThat(methods[0].getName()).isEqualTo("propose");
        assertThat(MemoryProposalReceipt.class.getRecordComponents())
                .extracting(c -> c.getType().getSimpleName())
                .containsExactly("UUID", "MemoryProposalStatus");
    }

    @Test
    void proposalCarriesNoStatusOrTrustOfItsOwn() {
        assertThat(Arrays.stream(MemoryWriteProposal.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("status", "trust");
    }

    @Test
    void disabledSinkCreatesNothing() {
        Optional<MemoryProposalReceipt> r =
                new DisabledMemoryProposalSink().propose(proposal(MemoryKind.SEMANTIC, MemoryProvenanceRecord.TRUSTED));
        assertThat(r).isEmpty();
    }

    @Test
    void aQuarantinedItemCannotBecomeARecallView() {
        assertThatThrownBy(
                        () ->
                                new MemoryItemView(
                                        UUID.randomUUID(),
                                        MemoryKind.SEMANTIC,
                                        "x",
                                        1.0,
                                        new MemoryProvenanceRecord(MemoryTrustLevel.QUARANTINED, List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownStoredTrustParsesAsQuarantined() {
        assertThat(MemoryTrustLevel.parse("garbage")).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(MemoryTrustLevel.parse(null)).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(MemoryTrustLevel.parse("TRUSTED")).isEqualTo(MemoryTrustLevel.TRUSTED);
    }
}
