package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

/** Optional limit on the run-history list: default cap, explicit limit, hard max. */
class WorkflowServiceListRunsPagingTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private WorkflowRunRepository runRepository;
    private WorkflowService service;
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        when(runRepository.findByWorkflowIdOrderByStartedAtDescIdDesc(
                        any(UUID.class), any(Pageable.class)))
                .thenReturn(List.of());
        service =
                WorkflowServiceFixture.of(
                        mock(WorkflowDefinitionRepository.class),
                        runRepository,
                        mock(OrchestratorService.class),
                        mock(WorkflowExecutionEngine.class),
                        new ObjectMapper(),
                        mock(TenantResolutionService.class),
                        mock(TenantSchemaResolver.class),
                        mock(TenantLimits.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(ObjectProvider.class),
                        mock(EncryptionService.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        mock(ResourceAccessService.class),
                        mock(WorkflowExecutionParamsInferenceService.class),
                        mock(com.agenticai.core.workflow.version.WorkflowVersionService.class));
    }

    private int pageSizeFor(Integer limit) {
        service.listRuns(workflowId, limit);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository, atLeastOnce())
                .findByWorkflowIdOrderByStartedAtDescIdDesc(any(UUID.class), c.capture());
        assertEquals(0, c.getValue().getOffset());
        return c.getValue().getPageSize();
    }

    @Test
    void defaultsToTwoHundredAndLegacyOverloadStaysUnbounded() {
        assertEquals(200, pageSizeFor(null));
        service.listRuns(workflowId);
        verify(runRepository).findByWorkflowIdOrderByStartedAtDesc(workflowId);
        verify(runRepository, times(1))
                .findByWorkflowIdOrderByStartedAtDescIdDesc(any(UUID.class), any(Pageable.class));
    }

    @Test
    void explicitLimitHonouredAndClamped() {
        assertEquals(30, pageSizeFor(30));
        assertEquals(1000, pageSizeFor(100_000));
        assertEquals(1, pageSizeFor(0));
    }
}
