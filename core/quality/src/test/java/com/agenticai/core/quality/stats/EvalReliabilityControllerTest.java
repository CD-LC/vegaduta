package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.stats.dto.ReliabilityDtos.AgentReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetNoiseFloorResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.PairedComparisonResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Pins the PREMIUM+ gate on the reliability surface, the same way
 * {@code QualityControllerTest} pins it on the four older quality endpoints.
 *
 * <p>This matters beyond consistency: the reliability endpoints read the SAME
 * judge output as the gated {@code /summary}, {@code /history} and
 * {@code /statistics} endpoints, so an ungated statistics view would be a free
 * route to a paid measurement. The tests below also pin that the handlers do not
 * touch the service at all for a FREE tenant - a gate that computes first and
 * rejects afterwards still burns the query budget it was meant to protect.
 */
class EvalReliabilityControllerTest {

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID BASELINE_RUN_ID = UUID.randomUUID();
    private static final UUID VARIANT_RUN_ID = UUID.randomUUID();

    private EvalReliabilityService reliabilityService;
    private TenantLimits tenantLimits;
    private EvalReliabilityController controller;

    @BeforeEach
    void setUp() {
        reliabilityService = mock(EvalReliabilityService.class);
        tenantLimits = mock(TenantLimits.class);
        controller = new EvalReliabilityController(reliabilityService, tenantLimits, false);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void asPlan(TenantPlan plan) {
        TenantContext.set(TenantContext.of(TENANT_ID, "user@example.com", Set.of()));
        when(tenantLimits.plan(TENANT_ID)).thenReturn(plan);
    }

    private static AgentReliabilityResponse emptyReport() {
        return new AgentReliabilityResponse(
            AGENT_ID, Instant.now(), 0.95, 10, List.of(), List.of("no runs yet"));
    }

    private static PairedComparisonResponse cannotTellComparison() {
        return new PairedComparisonResponse(
            UUID.randomUUID(), BASELINE_RUN_ID, VARIANT_RUN_ID, 6,
            0.50, 0.52, 0.02, null, ComparisonVerdict.CANNOT_TELL, null,
            3, 3, 0, 1.0, false, "Cannot tell", List.of());
    }

    // ---- FREE is gated, and never reaches the service ----

    @Test
    void reliabilityRejectsFreePlan() {
        asPlan(TenantPlan.FREE);

        TenantLimitExceededException thrown = assertThrows(
            TenantLimitExceededException.class, () -> controller.getReliability(AGENT_ID, 10));

        assertTrue(thrown.getMessage().contains("Premium"));
        verify(reliabilityService, never()).reportForAgent(any(), anyInt());
    }

    @Test
    void compareRejectsFreePlan() {
        asPlan(TenantPlan.FREE);

        assertThrows(
            TenantLimitExceededException.class,
            () -> controller.compareRuns(AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID, null));

        verify(reliabilityService, never()).compareRuns(any(), any(), any());
    }

    // ---- PREMIUM and UNLIMITED pass through ----

    @Test
    void reliabilityAllowsPremiumPlan() {
        asPlan(TenantPlan.PREMIUM);
        when(reliabilityService.reportForAgent(AGENT_ID, 10)).thenReturn(emptyReport());

        ResponseEntity<AgentReliabilityResponse> response = controller.getReliability(AGENT_ID, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(AGENT_ID, response.getBody().agentId());
        assertEquals(0.95, response.getBody().confidenceLevel());
    }

    @Test
    void reliabilityAllowsUnlimitedPlanAndPassesTheRunWindowThrough() {
        asPlan(TenantPlan.UNLIMITED);
        when(reliabilityService.reportForAgent(eq(AGENT_ID), anyInt())).thenReturn(emptyReport());

        controller.getReliability(AGENT_ID, 25);

        verify(reliabilityService).reportForAgent(AGENT_ID, 25);
    }

    @Test
    void compareAllowsPremiumPlanAndSurfacesACannotTellVerdict() {
        asPlan(TenantPlan.PREMIUM);
        when(reliabilityService.compareRuns(AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID))
            .thenReturn(cannotTellComparison());

        ResponseEntity<PairedComparisonResponse> response =
            controller.compareRuns(AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ComparisonVerdict.CANNOT_TELL, response.getBody().verdict());
    }

    @Test
    void noiseFloorRejectsFreePlan() {
        asPlan(TenantPlan.FREE);

        assertThrows(
            TenantLimitExceededException.class, () -> controller.getNoiseFloor(AGENT_ID, DATASET_ID));

        verify(reliabilityService, never()).noiseFloorForDataset(any(), any());
    }

    @Test
    void noiseFloorAllowsPremiumPlanAndPassesTheAbsenceThrough() {
        asPlan(TenantPlan.PREMIUM);
        when(reliabilityService.noiseFloorForDataset(AGENT_ID, DATASET_ID))
            .thenReturn(new DatasetNoiseFloorResponse(
                DATASET_ID, "Golden set", null, List.of("No noise floor: only one SUCCEEDED run")));

        ResponseEntity<DatasetNoiseFloorResponse> response =
            controller.getNoiseFloor(AGENT_ID, DATASET_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNull(response.getBody().noiseFloor(),
            "an unmeasured floor must arrive as null, never as a zero floor that waves every delta through");
        assertFalse(response.getBody().warnings().isEmpty());
    }

    /**
     * The console workstream independently settled on "candidate" for the
     * challenger arm. Both spellings are accepted so neither side has to be
     * rewritten; giving both with different values is a client bug and is refused.
     */
    @Test
    void compareAcceptsTheCandidateRunIdAlias() {
        asPlan(TenantPlan.PREMIUM);
        when(reliabilityService.compareRuns(AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID))
            .thenReturn(cannotTellComparison());

        ResponseEntity<PairedComparisonResponse> response =
            controller.compareRuns(AGENT_ID, BASELINE_RUN_ID, null, VARIANT_RUN_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reliabilityService).compareRuns(AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID);
    }

    @Test
    void compareRequiresAChallengerRunUnderOneOfItsTwoNames() {
        asPlan(TenantPlan.PREMIUM);

        assertThrows(
            IllegalArgumentException.class,
            () -> controller.compareRuns(AGENT_ID, BASELINE_RUN_ID, null, null));
    }

    @Test
    void compareRefusesConflictingAliasValues() {
        asPlan(TenantPlan.PREMIUM);

        assertThrows(
            IllegalArgumentException.class,
            () -> controller.compareRuns(
                AGENT_ID, BASELINE_RUN_ID, VARIANT_RUN_ID, UUID.randomUUID()));
    }

    /**
     * No HTTP request context means no tenant, which is treated as UNLIMITED -
     * the same posture {@code QualityController} and {@code LlmCallLogController}
     * take, chosen so the gate cannot be skipped silently by an NPE.
     */
    @Test
    void missingTenantContextIsTreatedAsUnlimited() {
        TenantContext.clear();
        when(reliabilityService.reportForAgent(AGENT_ID, 10)).thenReturn(emptyReport());

        ResponseEntity<AgentReliabilityResponse> response = controller.getReliability(AGENT_ID, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ---- FREE ungated by the evals flag (2026-10-02 research ungating) ----

    @Test
    void reliabilityAllowsFreePlanWhenFlagOn() {
        controller = new EvalReliabilityController(reliabilityService, tenantLimits, true);
        asPlan(TenantPlan.FREE);
        when(reliabilityService.reportForAgent(AGENT_ID, 10)).thenReturn(emptyReport());

        ResponseEntity<AgentReliabilityResponse> response = controller.getReliability(AGENT_ID, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reliabilityService).reportForAgent(AGENT_ID, 10);
    }
}
