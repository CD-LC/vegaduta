package com.agenticai.core.quality.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.calibration.JudgeCalibrationSetController.AddItemsRequest;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetController.CreateSetRequest;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetController.RenameSetRequest;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetController.ResultResponse;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetController.SetDetailResponse;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService.ItemInput;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Plan gate and delegation of the stored-set endpoints. */
class JudgeCalibrationSetControllerTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID SET = UUID.randomUUID();

    private JudgeCalibrationSetService service;
    private TenantLimits limits;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        service = mock(JudgeCalibrationSetService.class);
        limits = mock(TenantLimits.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private JudgeCalibrationSetController controller(TenantPlan plan) {
        when(limits.plan("tenant-1")).thenReturn(plan);
        return new JudgeCalibrationSetController(service, limits);
    }

    private JudgeCalibrationSet storedSet() {
        JudgeCalibrationSet set = new JudgeCalibrationSet(AGENT, "my set", "tester");
        org.springframework.test.util.ReflectionTestUtils.setField(set, "id", SET);
        return set;
    }

    @Test
    void freePlanIsRefusedOnEveryEndpointBeforeTouchingTheService() {
        JudgeCalibrationSetController c = controller(TenantPlan.FREE);
        assertThrows(TenantLimitExceededException.class, () -> c.list(AGENT));
        assertThrows(TenantLimitExceededException.class, () -> c.create(AGENT, new CreateSetRequest("n", List.of())));
        assertThrows(TenantLimitExceededException.class, () -> c.get(AGENT, SET));
        assertThrows(TenantLimitExceededException.class, () -> c.rename(AGENT, SET, new RenameSetRequest("n")));
        assertThrows(TenantLimitExceededException.class, () -> c.delete(AGENT, SET));
        assertThrows(TenantLimitExceededException.class, () -> c.addItems(AGENT, SET, new AddItemsRequest(List.of())));
        assertThrows(TenantLimitExceededException.class, () -> c.deleteItem(AGENT, SET, UUID.randomUUID()));
        assertThrows(TenantLimitExceededException.class, () -> c.history(AGENT, null, null));
        verifyNoInteractions(service);
    }

    @Test
    void createDelegatesAndAnswers201WithTheDetail() {
        JudgeCalibrationSet set = storedSet();
        ItemInput row = new ItemInput("q", "a", "b", "A", null);
        when(service.createSet(AGENT, "my set", List.of(row))).thenReturn(set);
        when(service.requireSet(AGENT, SET)).thenReturn(set);
        when(service.listItems(AGENT, SET)).thenReturn(List.of());
        when(service.countItems(SET)).thenReturn(0L);

        ResponseEntity<SetDetailResponse> response = controller(TenantPlan.PREMIUM)
            .create(AGENT, new CreateSetRequest("my set", List.of(row)));

        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("my set", response.getBody().set().name());
        assertEquals(SET, response.getBody().set().id());
    }

    @Test
    void nullBodiesReachTheServiceAsNullsSoTheValidationLivesInOnePlace() {
        JudgeCalibrationSetController c = controller(TenantPlan.PREMIUM);
        JudgeCalibrationSet set = storedSet();
        when(service.createSet(AGENT, null, null)).thenReturn(set);
        when(service.requireSet(AGENT, SET)).thenReturn(set);
        c.create(AGENT, null);
        verify(service).createSet(AGENT, null, null);

        when(service.renameSet(AGENT, SET, null)).thenReturn(set);
        c.rename(AGENT, SET, null);
        verify(service).renameSet(AGENT, SET, null);

        c.addItems(AGENT, SET, null);
        verify(service).addItems(AGENT, SET, null);
    }

    @Test
    void deleteEndpointsAnswer204() {
        JudgeCalibrationSetController c = controller(TenantPlan.PREMIUM);
        UUID item = UUID.randomUUID();
        assertEquals(204, c.delete(AGENT, SET).getStatusCode().value());
        assertEquals(204, c.deleteItem(AGENT, SET, item).getStatusCode().value());
        verify(service).deleteSet(AGENT, SET);
        verify(service).deleteItem(AGENT, SET, item);
    }

    @Test
    void historyMapsResultsAndPassesTheFilters() {
        JudgeCalibrationResult r = new JudgeCalibrationResult(SET, AGENT, "OPENAI/judge", "OPENAI/agent", 20, 20, 18,
            0.71, 0.9, 0.88, "substantial agreement", null, "tester");
        when(service.history(AGENT, SET, 5)).thenReturn(List.of(r));

        List<ResultResponse> body = controller(TenantPlan.PREMIUM).history(AGENT, SET, 5).getBody();

        assertNotNull(body);
        assertEquals(1, body.size());
        assertEquals(0.71, body.get(0).kappa());
        assertEquals(0.9, body.get(0).orderSwapConsistency());
        assertEquals("OPENAI/judge", body.get(0).judgeModel());
        assertEquals(20, body.get(0).n());
    }

    @Test
    void unlimitedPlanWithNoTenantContextIsAllowed() {
        TenantContext.clear();
        JudgeCalibrationSetController c = new JudgeCalibrationSetController(service, limits);
        when(service.listSets(AGENT)).thenReturn(List.of());
        assertEquals(200, c.list(AGENT).getStatusCode().value());
        verify(limits, never()).plan(any());
    }
}
