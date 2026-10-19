package com.agenticai.core.agents.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.share.AgentShareOwnerController.ShareStatusResponse;
import com.agenticai.core.sharing.ResourceAccessService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Why a share stopped resolving, as the owner status endpoint reports it. */
class AgentShareEndedReasonTest {

    private static AgentShare share(UUID agent) {
        return new AgentShare(agent, "sid");
    }

    @Test
    void liveShareHasNoReason() {
        assertNull(AgentShareService.endedReason(share(UUID.randomUUID()), 3));
    }

    @Test
    void revokedAtOrOverTheThresholdIsAbuseReports() {
        AgentShare s = share(UUID.randomUUID());
        s.report(3);
        s.report(3);
        s.report(3); // third report auto-revokes
        assertEquals(AgentShareService.ENDED_ABUSE_REPORTS, AgentShareService.endedReason(s, 3));
    }

    @Test
    void ownerRevokeBelowTheThresholdIsOwnerRevoked() {
        AgentShare s = share(UUID.randomUUID());
        s.report(3);
        s.revoke();
        assertEquals(AgentShareService.ENDED_OWNER_REVOKED, AgentShareService.endedReason(s, 3));
    }

    @Test
    void unrevokedPastExpiryIsExpired() {
        AgentShare s = share(UUID.randomUUID());
        s.setExpiresAt(Instant.now().minusSeconds(5));
        assertEquals(AgentShareService.ENDED_EXPIRED, AgentShareService.endedReason(s, 3));
    }

    @Test
    void statusEndpointReportsReasonCountAndRevokedAtForADeadLink() {
        UUID agentId = UUID.randomUUID();
        AgentShareService svc = mock(AgentShareService.class);
        AgentShareOwnerController controller =
                new AgentShareOwnerController(svc, mock(ResourceAccessService.class));
        AgentShare s = share(agentId);
        s.report(3);
        s.report(3);
        s.report(3);
        when(svc.statusFor(agentId)).thenReturn(Optional.of(s));
        when(svc.isLive(s)).thenReturn(false);
        when(svc.endedReason(s)).thenReturn(AgentShareService.ENDED_ABUSE_REPORTS);

        ShareStatusResponse r = controller.status(agentId);

        assertEquals(false, r.active());
        assertNull(r.shareUrl());
        assertEquals(3, r.reportCount());
        assertEquals("ABUSE_REPORTS", r.revokedReason());
        assertEquals(s.getUpdatedAt(), r.revokedAt());
    }

    @Test
    void noShareAtAllHasNoReason() {
        UUID agentId = UUID.randomUUID();
        AgentShareService svc = mock(AgentShareService.class);
        AgentShareOwnerController controller =
                new AgentShareOwnerController(svc, mock(ResourceAccessService.class));
        when(svc.statusFor(agentId)).thenReturn(Optional.empty());

        ShareStatusResponse r = controller.status(agentId);

        assertNull(r.revokedReason());
        assertNull(r.revokedAt());
        assertEquals(0, r.reportCount());
    }
}
