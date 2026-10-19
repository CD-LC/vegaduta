package com.agenticai.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dto.QualityStatisticsResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Pricing/monetization audit 2026-07-31, item C1: the whole quality/evals
 * REST surface is PREMIUM+ only. These tests pin the gate on all four
 * endpoints - FREE throws {@link TenantLimitExceededException} (mapped to
 * 403 by {@code GlobalExceptionHandler} in core/app), PREMIUM/UNLIMITED pass
 * through, and a missing request context is treated as UNLIMITED (same
 * posture as {@code LlmCallLogController}).
 */
class QualityControllerTest {

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    private QualityEvaluationService qualityService;
    private TenantLimits tenantLimits;
    private QualityController controller;

    @BeforeEach
    void setUp() {
        qualityService = mock(QualityEvaluationService.class);
        tenantLimits = mock(TenantLimits.class);
        controller = new QualityController(qualityService, tenantLimits, false);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void asPlan(TenantPlan plan) {
        TenantContext.set(TenantContext.of(TENANT_ID, "user@example.com", Set.of()));
        when(tenantLimits.plan(TENANT_ID)).thenReturn(plan);
    }

    // ---- FREE is gated on every endpoint ----

    @Test
    void summaryRejectsFreePlan() {
        asPlan(TenantPlan.FREE);
        TenantLimitExceededException e = assertThrows(
            TenantLimitExceededException.class,
            () -> controller.getSummary(AGENT_ID)
        );
        assertTrue(e.getMessage().toLowerCase().contains("upgrade"));
        verify(qualityService, never()).getLatestSummary(any());
    }

    @Test
    void historyRejectsFreePlan() {
        asPlan(TenantPlan.FREE);
        assertThrows(TenantLimitExceededException.class, () -> controller.getHistory(AGENT_ID, 100));
        verify(qualityService, never()).getHistoricalData(any(), anyInt());
    }

    @Test
    void statisticsRejectsFreePlan() {
        asPlan(TenantPlan.FREE);
        assertThrows(TenantLimitExceededException.class, () -> controller.getStatistics(AGENT_ID));
        verify(qualityService, never()).getStatistics(any());
    }

    @Test
    void configRejectsFreePlan() {
        asPlan(TenantPlan.FREE);
        assertThrows(TenantLimitExceededException.class, () -> controller.getConfig(AGENT_ID));
    }

    // ---- PREMIUM / UNLIMITED pass through ----

    @Test
    void summaryAllowsPremiumPlan() {
        asPlan(TenantPlan.PREMIUM);
        when(qualityService.getLatestSummary(AGENT_ID)).thenReturn(Optional.empty());

        // Gate passes; empty data is the service's own 404, not the plan gate.
        assertEquals(HttpStatus.NOT_FOUND, controller.getSummary(AGENT_ID).getStatusCode());
        verify(qualityService).getLatestSummary(AGENT_ID);
    }

    @Test
    void historyAllowsPremiumPlan() {
        asPlan(TenantPlan.PREMIUM);
        when(qualityService.getHistoricalData(AGENT_ID, 100)).thenReturn(Map.of(
            "timestamps", List.of("2026-07-31T00:00:00Z"),
            "overall_scores", List.of(0.9)
        ));

        assertEquals(HttpStatus.OK, controller.getHistory(AGENT_ID, 100).getStatusCode());
    }

    @Test
    void statisticsAllowsUnlimitedPlan() {
        asPlan(TenantPlan.UNLIMITED);
        when(qualityService.getStatistics(AGENT_ID)).thenReturn(
            new QualityEvaluationService.QualityStatistics(AGENT_ID, 3, 0.8, Map.of(), Instant.now())
        );

        ResponseEntity<QualityStatisticsResponse> response = controller.getStatistics(AGENT_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().totalEvaluations());
    }

    @Test
    void configReturnsRealTierAndFullEvalSetForPremium() {
        asPlan(TenantPlan.PREMIUM);

        ResponseEntity<QualityController.QualityConfigResponse> response = controller.getConfig(AGENT_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PREMIUM", response.getBody().planTier());
        // Premium gets the full evaluation set, not the FREE subset.
        assertEquals(List.of(EvaluationType.values()), response.getBody().enabledEvaluations());
    }

    @Test
    void configReturnsRealTierForUnlimited() {
        asPlan(TenantPlan.UNLIMITED);

        ResponseEntity<QualityController.QualityConfigResponse> response = controller.getConfig(AGENT_ID);
        assertEquals("UNLIMITED", response.getBody().planTier());
    }

    // ---- no request context: treated as UNLIMITED, never NPEs ----

    @Test
    void missingTenantContextIsTreatedAsUnlimited() {
        TenantContext.clear();
        when(qualityService.getLatestSummary(AGENT_ID)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getSummary(AGENT_ID).getStatusCode());
    }

    // ---- FREE ungated by the evals flag (2026-10-02 research ungating) ----

    @Test
    void summaryAllowsFreePlanWhenFlagOn() {
        controller = new QualityController(qualityService, tenantLimits, true);
        asPlan(TenantPlan.FREE);
        when(qualityService.getLatestSummary(AGENT_ID)).thenReturn(Optional.empty());

        // Gate passes; empty data is the service's own 404, not the plan gate.
        assertEquals(HttpStatus.NOT_FOUND, controller.getSummary(AGENT_ID).getStatusCode());
        verify(qualityService).getLatestSummary(AGENT_ID);
    }
}
