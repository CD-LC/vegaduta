package com.agenticai.core.agents.memorystore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The V681 CHECK constraints as service-level rules (the database side is {@code
 * MemoryItemSchemaIT}).
 */
class MemoryItemInvariantsTest {

    private static MemoryItem item(
            MemoryKind kind,
            MemoryTrustLevel trust,
            MemoryItemStatus status,
            List<String> taint,
            String approver,
            int generation) {
        return new MemoryItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                kind,
                "c",
                trust,
                status,
                MemoryWriter.AGENT,
                null,
                null,
                null,
                taint,
                true,
                generation,
                List.of(),
                1,
                null,
                Instant.now(),
                null,
                approver,
                approver == null ? null : Instant.now());
    }

    @Test
    void activeRequiresTrusted() {
        assertThatThrownBy(
                        () ->
                                item(
                                        MemoryKind.EPISODIC,
                                        MemoryTrustLevel.QUARANTINED,
                                        MemoryItemStatus.ACTIVE,
                                        List.of(),
                                        "alice",
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                item(
                                        MemoryKind.EPISODIC,
                                        MemoryTrustLevel.TRUSTED,
                                        MemoryItemStatus.ACTIVE,
                                        List.of(),
                                        null,
                                        1))
                .doesNotThrowAnyException();
    }

    @Test
    void trustedWithTaintRequiresAnApprover() {
        assertThatThrownBy(
                        () ->
                                item(
                                        MemoryKind.EPISODIC,
                                        MemoryTrustLevel.TRUSTED,
                                        MemoryItemStatus.PENDING,
                                        List.of("web"),
                                        null,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                item(
                                        MemoryKind.EPISODIC,
                                        MemoryTrustLevel.TRUSTED,
                                        MemoryItemStatus.ACTIVE,
                                        List.of("web"),
                                        "alice",
                                        1))
                .doesNotThrowAnyException();
    }

    @Test
    void activeProceduralRequiresAnApprover() {
        assertThatThrownBy(
                        () ->
                                item(
                                        MemoryKind.PROCEDURAL,
                                        MemoryTrustLevel.TRUSTED,
                                        MemoryItemStatus.ACTIVE,
                                        List.of(),
                                        null,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                item(
                                        MemoryKind.PROCEDURAL,
                                        MemoryTrustLevel.TRUSTED,
                                        MemoryItemStatus.ACTIVE,
                                        List.of(),
                                        "alice",
                                        1))
                .doesNotThrowAnyException();
    }

    @Test
    void generationIsBounded() {
        for (int bad : new int[] {-1, 9}) {
            assertThatThrownBy(
                            () ->
                                    item(
                                            MemoryKind.EPISODIC,
                                            MemoryTrustLevel.QUARANTINED,
                                            MemoryItemStatus.PENDING,
                                            List.of(),
                                            null,
                                            bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void semanticIsNotStoredHere() {
        assertThatThrownBy(
                        () ->
                                item(
                                        MemoryKind.SEMANTIC,
                                        MemoryTrustLevel.QUARANTINED,
                                        MemoryItemStatus.PENDING,
                                        List.of(),
                                        null,
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNewRowIsAlwaysQuarantinedAndPending() {
        MemoryItem i =
                MemoryItem.pending(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        MemoryKind.PROCEDURAL,
                        "c",
                        MemoryWriter.AGENT,
                        null,
                        null,
                        null,
                        List.of(),
                        true,
                        1,
                        List.of(),
                        1,
                        null,
                        Instant.now(),
                        null);
        assertThat(i.trust()).isEqualTo(MemoryTrustLevel.QUARANTINED);
        assertThat(i.status()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(i.approvedBy()).isNull();
    }

    @Test
    void eventReasonMustBeACodeNotText() {
        assertThatThrownBy(
                        () ->
                                MemoryEvent.of(
                                        null,
                                        UUID.randomUUID(),
                                        MemoryEvent.Type.REFUSED,
                                        MemoryActor.system("x"),
                                        null,
                                        null,
                                        "the customer prefers INR",
                                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
