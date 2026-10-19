package com.agenticai.core.agents.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * SE-03: the share a controller holds is detached (resolved in a read-only transaction, open-in-view
 * is off), so counters must be applied to the row re-loaded inside the writing transaction.
 */
class AgentShareCountersTest {

    private static final String SHARE_ID = "abcdefghijklmnop";

    private AgentShareRepository repository;
    private TenantResolutionService tenantResolution;
    private AgentShareService service;
    private AgentShare detached;
    private AgentShare managed;

    @BeforeEach
    void setUp() {
        repository = mock(AgentShareRepository.class);
        tenantResolution = mock(TenantResolutionService.class);
        service =
                new AgentShareService(
                        repository,
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class),
                        tenantResolution,
                        mock(StringRedisTemplate.class),
                        "",
                        3,
                        12,
                        4,
                        20,
                        6);
        UUID agentId = UUID.randomUUID();
        detached = new AgentShare(agentId, SHARE_ID);
        managed = new AgentShare(agentId, SHARE_ID);
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.of(managed));
    }

    @Test
    void viewerCountIsAppliedToTheManagedRow() {
        service.recordViewer(detached);
        service.recordViewer(detached);

        assertEquals(2, managed.getViewerCount());
        assertEquals(0, detached.getViewerCount());
    }

    @Test
    void threeReportsRevokeTheLinkAndCountThree() {
        assertFalse(service.report(detached, "spam"));
        assertFalse(service.report(detached, null));
        assertTrue(service.report(detached, null));

        assertEquals(3, managed.getReportCount());
        assertTrue(managed.isRevoked());
        verify(tenantResolution).unlink(ExternalAccountProvider.AGENT_SHARE, SHARE_ID);
    }

    @Test
    void reportOnAVanishedRowIsANoOp() {
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertFalse(service.report(detached, "x"));
    }
}
