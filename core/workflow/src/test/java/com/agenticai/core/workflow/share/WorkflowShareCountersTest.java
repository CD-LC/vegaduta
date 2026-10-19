package com.agenticai.core.workflow.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** SE-03 for workflow shares: counters land on the row re-loaded in the writing transaction. */
class WorkflowShareCountersTest {

    private static final String SHARE_ID = "abcdefghijklmnop";

    private WorkflowShareRepository repository;
    private TenantResolutionService tenantResolution;
    private WorkflowShareService service;
    private WorkflowShare detached;
    private WorkflowShare managed;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowShareRepository.class);
        tenantResolution = mock(TenantResolutionService.class);
        service =
                new WorkflowShareService(
                        repository,
                        mock(WorkflowDefinitionRepository.class),
                        mock(ResourceAccessService.class),
                        tenantResolution,
                        mock(StringRedisTemplate.class),
                        "",
                        3,
                        12,
                        20);
        UUID workflowId = UUID.randomUUID();
        detached = new WorkflowShare(workflowId, SHARE_ID);
        managed = new WorkflowShare(workflowId, SHARE_ID);
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.of(managed));
    }

    @Test
    void viewerCountIsAppliedToTheManagedRow() {
        service.recordViewer(detached);

        assertEquals(1, managed.getViewerCount());
    }

    @Test
    void threeReportsRevokeTheLinkAndCountThree() {
        assertFalse(service.report(detached, "spam"));
        assertFalse(service.report(detached, null));
        assertTrue(service.report(detached, null));

        assertEquals(3, managed.getReportCount());
        assertTrue(managed.isRevoked());
        verify(tenantResolution).unlink(ExternalAccountProvider.WORKFLOW_SHARE, SHARE_ID);
    }
}
