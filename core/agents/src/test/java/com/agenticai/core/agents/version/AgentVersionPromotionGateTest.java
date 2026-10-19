package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentSchemaService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.version.AgentPromotionGate.GateDecision;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The {@link AgentVersionService#setLabel} half of the eval gate (V260): the
 * PRODUCTION label is the promote/publish action and goes through the {@link
 * AgentPromotionGate} SPI; STAGING never does. All collaborators mocked -
 * this test pins the ENFORCEMENT contract (what happens at the hook), while
 * {@code EvalGateServiceTest} in core/quality pins the decision rules.
 */
class AgentVersionPromotionGateTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();

    private AgentService agentService;
    private AgentVersionRepository versionRepository;
    private ObjectProvider<AgentPromotionGate> gateProvider;
    private AgentPromotionGate gate;
    private AuditLogService auditLogService;
    private AgentVersionService service;
    private AgentVersion version;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentSchemaService schemaService = mock(AgentSchemaService.class);
        versionRepository = mock(AgentVersionRepository.class);
        AgentConfigSnapshotCodec codec = mock(AgentConfigSnapshotCodec.class);
        AgentVersionSnapshotService snapshotService = mock(AgentVersionSnapshotService.class);
        gateProvider = mock(ObjectProvider.class);
        gate = mock(AgentPromotionGate.class);
        auditLogService = mock(AuditLogService.class);
        service = new AgentVersionService(
            agentService, agentRepository, schemaService, versionRepository,
            codec, snapshotService, gateProvider, auditLogService);

        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);

        version = mock(AgentVersion.class);
        when(version.getId()).thenReturn(VERSION_ID);
        when(version.getVersionNumber()).thenReturn(7);
        when(versionRepository.findByIdAndAgentId(VERSION_ID, AGENT_ID))
            .thenReturn(Optional.of(version));
        when(versionRepository.findByAgentIdAndLabel(eq(AGENT_ID), any()))
            .thenReturn(Optional.empty());
        when(versionRepository.save(any(AgentVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        when(gateProvider.getIfAvailable()).thenReturn(gate);
        TenantContext.set(TenantContext.of("tenant-1", "user@example.com", Set.of()));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static GateDecision allowed() {
        return new GateDecision(true, true, true, "PASSING_RUN", "passed", UUID.randomUUID(),
            "PASSED", 0.9, null);
    }

    private static GateDecision blocked(String reason) {
        return new GateDecision(false, true, true, reason, "blocked: " + reason, null,
            null, null, null);
    }

    @Test
    void stagingNeverConsultsTheGate() {
        service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.STAGING, false, null);

        verify(gate, never()).evaluatePromotion(any(), any());
        verify(version).assignLabel(AgentVersionLabel.STAGING);
    }

    @Test
    void clearingALabelNeverConsultsTheGate() {
        service.setLabel(AGENT_ID, VERSION_ID, null, false, null);

        verify(gate, never()).evaluatePromotion(any(), any());
        verify(version).clearLabel();
    }

    @Test
    void productionAllowedWhenGatePasses() {
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(allowed());

        service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, false, null);

        verify(version).assignLabel(AgentVersionLabel.PRODUCTION);
        verify(versionRepository).save(version);
        verify(auditLogService).record(eq("user@example.com"), eq("agent.version.promote"),
            eq("agent"), eq(AGENT_ID.toString()), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void productionBlockedThrowsAndAuditsTheRefusal() {
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(blocked("NO_QUALIFYING_RUN"));

        PromotionGateBlockedException e = assertThrows(PromotionGateBlockedException.class,
            () -> service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, false, null));

        // The label never moved.
        verify(version, never()).assignLabel(any());
        verify(versionRepository, never()).save(any());
        // ...and the refusal is on the audit trail with the gate's reason.
        verify(auditLogService).record(any(), eq("agent.version.promote"), eq("agent"),
            eq(AGENT_ID.toString()), eq(AuditOutcome.FAILURE),
            argThat(d -> d != null && d.contains("NO_QUALIFYING_RUN")));
        // The report the 412 body renders is the decision the gate returned.
        org.junit.jupiter.api.Assertions.assertEquals("NO_QUALIFYING_RUN", e.getDecision().reason());
    }

    @Test
    void forcePromoteNeedsTheManageScope() {
        // Plain editor: no PERM_MANAGE_AGENTS, not platform-admin.
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(blocked("RUN_FAILED"));

        assertThrows(PromotionGateForceDeniedException.class,
            () -> service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, true, "hotfix"));

        verify(version, never()).assignLabel(any());
    }

    @Test
    void forcePromoteNeedsAReason() {
        TenantContext.set(TenantContext.of("tenant-1", "admin@example.com", Set.of("PERM_MANAGE_AGENTS")));
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(blocked("RUN_FAILED"));

        assertThrows(IllegalArgumentException.class,
            () -> service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, true, "  "));

        verify(version, never()).assignLabel(any());
    }

    @Test
    void forcePromoteWithScopeAndReasonGoesThroughAudited() {
        TenantContext.set(TenantContext.of("tenant-1", "admin@example.com", Set.of("PERM_MANAGE_AGENTS")));
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(blocked("RUN_FAILED"));

        service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, true, "incident hotfix");

        verify(version).assignLabel(AgentVersionLabel.PRODUCTION);
        verify(auditLogService).record(eq("admin@example.com"), eq("agent.version.promote"),
            eq("agent"), eq(AGENT_ID.toString()), eq(AuditOutcome.SUCCESS),
            argThat(d -> d != null && d.contains("FORCED") && d.contains("incident hotfix")));
    }

    @Test
    void platformAdminCanForceWithoutTenantGrants() {
        TenantContext.set(TenantContext.of("tenant-1", "root", Set.of("ROLE_platform-admin")));
        when(gate.evaluatePromotion(AGENT_ID, VERSION_ID)).thenReturn(blocked("RUN_STALE"));

        service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, true, "deploy window");

        verify(version).assignLabel(AgentVersionLabel.PRODUCTION);
    }

    @Test
    void noGateProviderMeansNoGate() {
        when(gateProvider.getIfAvailable()).thenReturn(null);

        service.setLabel(AGENT_ID, VERSION_ID, AgentVersionLabel.PRODUCTION, false, null);

        verify(version).assignLabel(AgentVersionLabel.PRODUCTION);
    }
}
