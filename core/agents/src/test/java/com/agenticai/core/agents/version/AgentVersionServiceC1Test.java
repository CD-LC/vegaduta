package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentSchemaService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The C1 verbs on {@link AgentVersionService}: {@code publish} (hash-deduped
 * snapshot of the draft), {@code rollback} (PRODUCTION pointer moves back -
 * never a new snapshot), and {@code diff} (field-level bundle compare). All
 * collaborators mocked, same posture as {@link AgentVersionPromotionGateTest}
 * - these tests pin the SEMANTICS, not the persistence.
 */
class AgentVersionServiceC1Test {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentService agentService;
    private AgentVersionRepository versionRepository;
    private AgentVersionSnapshotService snapshotService;
    private AgentConfigSnapshotCodec codec;
    private AgentVersionService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        versionRepository = mock(AgentVersionRepository.class);
        snapshotService = mock(AgentVersionSnapshotService.class);
        codec = mock(AgentConfigSnapshotCodec.class);
        ObjectProvider<AgentPromotionGate> gateProvider = mock(ObjectProvider.class);
        when(gateProvider.getIfAvailable()).thenReturn(null);
        service = new AgentVersionService(
            agentService, mock(AgentRepository.class), mock(AgentSchemaService.class),
            versionRepository, codec, snapshotService, gateProvider, mock(AuditLogService.class));

        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.VIEWER)).thenReturn(agent);
        when(versionRepository.save(any(AgentVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.set(TenantContext.of("tenant-1", "user@example.com", Set.of()));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private AgentVersion version(int number) {
        AgentVersion v = mock(AgentVersion.class);
        when(v.getId()).thenReturn(UUID.randomUUID());
        when(v.getVersionNumber()).thenReturn(number);
        return v;
    }

    // ----------------------------------------------------------- publish

    @Test
    void publishAppendsAndReportsCreated() {
        AgentVersion v = version(8);
        when(snapshotService.snapshot(any(), eq("my note"))).thenReturn(Optional.of(v));

        AgentPublishResponse response = service.publish(AGENT_ID, "my note");

        assertTrue(response.createdNew());
        verify(snapshotService).snapshot(any(), eq("my note"));
    }

    @Test
    void publishDefaultsTheChangeNoteWhenBlank() {
        AgentVersion v = version(8);
        when(snapshotService.snapshot(any(), eq("Published"))).thenReturn(Optional.of(v));

        service.publish(AGENT_ID, "   ");

        verify(snapshotService).snapshot(any(), eq("Published"));
    }

    @Test
    void publishDedupedBundleAnswersTheExistingNewest() {
        AgentVersion latest = version(7);
        when(snapshotService.snapshot(any(), anyString())).thenReturn(Optional.empty());
        when(versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(AGENT_ID))
            .thenReturn(Optional.of(latest));

        AgentPublishResponse response = service.publish(AGENT_ID, "unchanged");

        assertFalse(response.createdNew());
        assertEquals(latest.getId(), response.version().id());
    }

    // ---------------------------------------------------------- rollback

    @Test
    void rollbackMovesProductionToThePreviousVersionAndNeverSnapshots() {
        AgentVersion current = version(7);
        AgentVersion previous = version(5);
        AgentVersion oldest = version(3);
        when(versionRepository.findByAgentIdAndLabel(AGENT_ID, AgentVersionLabel.PRODUCTION))
            .thenReturn(Optional.of(current));
        when(versionRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(current, previous, oldest));

        AgentVersionResponse result = service.rollback(AGENT_ID);

        // The CURRENT holder is cleared+flushed before the pointer is
        // assigned - the V212 partial unique index must never see two.
        InOrder order = inOrder(versionRepository, current, previous);
        order.verify(current).clearLabel();
        order.verify(versionRepository).saveAndFlush(current);
        order.verify(previous).assignLabel(AgentVersionLabel.PRODUCTION);
        order.verify(versionRepository).save(previous);
        // "Rollback never creates a new snapshot" - the spec's hard rule.
        verify(snapshotService, never()).snapshot(any(), anyString());
        assertEquals(previous.getId(), result.id());
    }

    @Test
    void rollbackSkipsNewerUnlabeledRowsAboveProduction() {
        // v9 exists but was never promoted; PRODUCTION sits on v7 - rolling
        // back must land on v6, not v9. ("Previous" is ordinal below the
        // CURRENT holder, not below the newest row.)
        AgentVersion newest = version(9);
        AgentVersion current = version(7);
        AgentVersion previous = version(6);
        when(versionRepository.findByAgentIdAndLabel(AGENT_ID, AgentVersionLabel.PRODUCTION))
            .thenReturn(Optional.of(current));
        when(versionRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(newest, current, previous));

        service.rollback(AGENT_ID);

        verify(previous).assignLabel(AgentVersionLabel.PRODUCTION);
        verify(newest, never()).assignLabel(any());
    }

    @Test
    void rollbackWithNothingEarlierIsAConflict() {
        AgentVersion current = version(1);
        when(versionRepository.findByAgentIdAndLabel(AGENT_ID, AgentVersionLabel.PRODUCTION))
            .thenReturn(Optional.of(current));
        when(versionRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(current));

        assertThrows(VersionRollbackException.class, () -> service.rollback(AGENT_ID));
        verify(current, never()).clearLabel();
    }

    @Test
    void rollbackWithNoProductionLabelIsAConflict() {
        when(versionRepository.findByAgentIdAndLabel(AGENT_ID, AgentVersionLabel.PRODUCTION))
            .thenReturn(Optional.empty());

        assertThrows(VersionRollbackException.class, () -> service.rollback(AGENT_ID));
    }

    // --------------------------------------------------------------- diff

    @Test
    @SuppressWarnings("unchecked")
    void diffReportsOnlyChangedFields() throws Exception {
        AgentVersion from = version(3);
        AgentVersion to = version(7);
        when(versionRepository.findByAgentIdAndVersionNumber(AGENT_ID, 3)).thenReturn(Optional.of(from));
        when(versionRepository.findByAgentIdAndVersionNumber(AGENT_ID, 7)).thenReturn(Optional.of(to));
        when(from.getConfigJson()).thenReturn("{}");
        when(to.getConfigJson()).thenReturn("{}");

        AgentConfigSnapshot a = mock(AgentConfigSnapshot.class);
        AgentConfigSnapshot b = mock(AgentConfigSnapshot.class);
        when(codec.fromJson("{}")).thenReturn(a, b);
        // Differ on exactly one component; the rest are null==null.
        when(a.model()).thenReturn("gpt-4o");
        when(b.model()).thenReturn("claude-sonnet-4");

        Map<String, Object> diff = service.diff(AGENT_ID, 3, 7);

        List<Map<String, Object>> fields = (List<Map<String, Object>>) diff.get("fields");
        assertEquals(1, fields.size());
        assertEquals("model", fields.get(0).get("field"));
        assertEquals("gpt-4o", fields.get(0).get("from"));
        assertEquals("claude-sonnet-4", fields.get(0).get("to"));
        assertEquals(false, diff.get("unchanged"));
    }

    @Test
    void diffOnMissingOrdinalIsNotFound() {
        AgentVersion to = version(7);
        when(versionRepository.findByAgentIdAndVersionNumber(AGENT_ID, 3)).thenReturn(Optional.empty());
        when(versionRepository.findByAgentIdAndVersionNumber(AGENT_ID, 7)).thenReturn(Optional.of(to));

        assertThrows(AgentVersionNotFoundException.class, () -> service.diff(AGENT_ID, 3, 7));
    }
}
