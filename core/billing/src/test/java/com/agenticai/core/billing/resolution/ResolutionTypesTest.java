package com.agenticai.core.billing.resolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.billing.resolution.ResolutionDtos.DisputeRequest;
import com.agenticai.core.billing.resolution.ResolutionDtos.RecordResolutionRequest;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionActionResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionEventResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionListResponse;
import com.agenticai.core.billing.resolution.ResolutionDtos.ResolutionSummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The resolution meter's outcome/evidence vocabularies and the controller DTO mapping: {@code
 * RESOLVED_AI} is the only outcome-priced billable value, and {@code ResolutionEventResponse.from}
 * renders the stored enums as their names so the wire shape stays stable if the enum ever renames.
 */
class ResolutionTypesTest {

    @Test
    void theOutcomeVocabularyIsTheFiveRecordedEndStates() {
        assertEquals(5, ResolutionOutcome.values().length);
        assertEquals(ResolutionOutcome.RESOLVED_AI, ResolutionOutcome.valueOf("RESOLVED_AI"));
        assertEquals(ResolutionOutcome.RESOLVED_HUMAN, ResolutionOutcome.valueOf("RESOLVED_HUMAN"));
        assertEquals(ResolutionOutcome.ESCALATED, ResolutionOutcome.valueOf("ESCALATED"));
        assertEquals(ResolutionOutcome.UNRESOLVED, ResolutionOutcome.valueOf("UNRESOLVED"));
        assertEquals(ResolutionOutcome.ABANDONED, ResolutionOutcome.valueOf("ABANDONED"));
    }

    @Test
    void theEvidenceVocabularyIsTheSevenDecisionSources() {
        assertEquals(7, ResolutionEvidence.values().length);
        assertEquals(
                ResolutionEvidence.USER_RESOLVED_SIGNAL,
                ResolutionEvidence.valueOf("USER_RESOLVED_SIGNAL"));
        assertEquals(ResolutionEvidence.EXPLICIT_API, ResolutionEvidence.valueOf("EXPLICIT_API"));
        assertEquals(ResolutionEvidence.JUDGE_VERDICT, ResolutionEvidence.valueOf("JUDGE_VERDICT"));
    }

    @Test
    void eventResponseRendersOutcomeAndEvidenceAsEnumNames() {
        ResolutionEvent event =
                new ResolutionEvent(
                        UUID.randomUUID(),
                        "conv-1",
                        UUID.randomUUID(),
                        "whatsapp",
                        ResolutionOutcome.RESOLVED_AI,
                        ResolutionEvidence.USER_RESOLVED_SIGNAL,
                        new BigDecimal("1.0"),
                        true,
                        "resolved",
                        15_000L,
                        "INR",
                        Instant.now(),
                        "dedupe-1",
                        "sweep",
                        null,
                        null,
                        null,
                        Instant.now());
        ResolutionEventResponse response = ResolutionEventResponse.from(event);
        assertEquals("RESOLVED_AI", response.outcome());
        assertEquals("USER_RESOLVED_SIGNAL", response.evidence());
        assertEquals("conv-1", response.conversationKey());
        assertEquals("whatsapp", response.channel());
        assertTrue(response.billable());
        assertEquals(15_000L, response.unitPriceMinor());
        assertEquals("INR", response.currency());
        assertEquals(event.id(), response.id());
    }

    @Test
    void actionResponseFactoryCarriesIdAndRecordedFlag() {
        UUID id = UUID.randomUUID();
        ResolutionActionResponse ok = ResolutionActionResponse.of(id, true);
        assertEquals(id, ok.id());
        assertTrue(ok.recorded());
    }

    @Test
    void theRequestAndEnvelopeRecordsArePlainCarriers() {
        RecordResolutionRequest record =
                new RecordResolutionRequest(
                        "conv-2",
                        UUID.randomUUID(),
                        "web",
                        "RESOLVED_AI",
                        "EXPLICIT_API",
                        new BigDecimal("1.0"),
                        Instant.now(),
                        "dedupe-2");
        assertEquals("conv-2", record.conversationKey());
        assertEquals("EXPLICIT_API", record.evidence());
        assertEquals("dedupe-2", record.dedupeKey());

        DisputeRequest dispute = new DisputeRequest("customer says unresolved");
        assertEquals("customer says unresolved", dispute.reason());

        ResolutionEventResponse row =
                ResolutionEventResponse.from(
                        new ResolutionEvent(
                                UUID.randomUUID(),
                                "c",
                                null,
                                "web",
                                ResolutionOutcome.ABANDONED,
                                ResolutionEvidence.NO_ESCALATION_IDLE,
                                new BigDecimal("0.6"),
                                false,
                                "not engaged",
                                null,
                                "INR",
                                Instant.now(),
                                "d",
                                "sweep",
                                null,
                                null,
                                null,
                                Instant.now()));
        ResolutionListResponse list = new ResolutionListResponse(List.of(row), 50, 100);
        assertEquals(1, list.events().size());
        assertEquals(50, list.limit());
        assertEquals(100, list.offset());

        ResolutionSummaryResponse summary =
                new ResolutionSummaryResponse(
                        Instant.now(),
                        Instant.now(),
                        7,
                        Map.of("RESOLVED_AI", 5L),
                        5,
                        75_000,
                        "INR",
                        15_000,
                        false);
        assertEquals(7, summary.totalEvents());
        assertEquals(5L, summary.byOutcome().get("RESOLVED_AI"));
        assertEquals(75_000, summary.billableTotalMinor());
        assertEquals(15_000, summary.configuredUnitPriceMinor());
    }
}
