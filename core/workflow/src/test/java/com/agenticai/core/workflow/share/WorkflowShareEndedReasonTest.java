package com.agenticai.core.workflow.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.share.WorkflowShareOwnerController.ShareStatusResponse;
import com.agenticai.core.sharing.ResourceAccessService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Why a share stopped resolving, as the owner status endpoint reports it. */
class WorkflowShareEndedReasonTest {

    private static WorkflowShare share(UUID workflow) {
        return new WorkflowShare(workflow, "sid");
    }

    @Test
    void liveShareHasNoReason() {
        assertNull(WorkflowShareService.endedReason(share(UUID.randomUUID()), 3));
    }

    @Test
    void revokedAtOrOverTheThresholdIsAbuseReports() {
        WorkflowShare s = share(UUID.randomUUID());
        s.report(3);
        s.report(3);
        s.report(3); // third report auto-revokes
        assertEquals(WorkflowShareService.ENDED_ABUSE_REPORTS, WorkflowShareService.endedReason(s, 3));
    }

    @Test
    void ownerRevokeBelowTheThresholdIsOwnerRevoked() {
        WorkflowShare s = share(UUID.randomUUID());
        s.report(3);
        s.revoke();
        assertEquals(WorkflowShareService.ENDED_OWNER_REVOKED, WorkflowShareService.endedReason(s, 3));
    }

    @Test
    void unrevokedPastExpiryIsExpired() {
        WorkflowShare s = share(UUID.randomUUID());
        s.setExpiresAt(Instant.now().minusSeconds(5));
        assertEquals(WorkflowShareService.ENDED_EXPIRED, WorkflowShareService.endedReason(s, 3));
    }

    @Test
    void statusEndpointReportsReasonCountAndRevokedAtForADeadLink() {
        UUID workflowId = UUID.randomUUID();
        WorkflowShareService svc = mock(WorkflowShareService.class);
        WorkflowShareOwnerController controller =
                new WorkflowShareOwnerController(svc, mock(ResourceAccessService.class));
        WorkflowShare s = share(workflowId);
        s.report(3);
        s.report(3);
        s.report(3);
        when(svc.statusFor(workflowId)).thenReturn(Optional.of(s));
        when(svc.isLive(s)).thenReturn(false);
        when(svc.endedReason(s)).thenReturn(WorkflowShareService.ENDED_ABUSE_REPORTS);

        ShareStatusResponse r = controller.status(workflowId);

        assertEquals(false, r.active());
        assertNull(r.shareUrl());
        assertEquals(3, r.reportCount());
        assertEquals("ABUSE_REPORTS", r.revokedReason());
        assertEquals(s.getUpdatedAt(), r.revokedAt());
    }

    @Test
    void noShareAtAllHasNoReason() {
        UUID workflowId = UUID.randomUUID();
        WorkflowShareService svc = mock(WorkflowShareService.class);
        WorkflowShareOwnerController controller =
                new WorkflowShareOwnerController(svc, mock(ResourceAccessService.class));
        when(svc.statusFor(workflowId)).thenReturn(Optional.empty());

        ShareStatusResponse r = controller.status(workflowId);

        assertNull(r.revokedReason());
        assertNull(r.revokedAt());
        assertEquals(0, r.reportCount());
    }
}
