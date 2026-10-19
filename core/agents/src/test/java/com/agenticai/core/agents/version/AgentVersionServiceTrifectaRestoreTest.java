package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentSchemaService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementPolicy;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A version restore rewrites the tool allow-list, so it must go through the same {@code
 * trifecta-enforcement} policy as a direct allow-list save: block rejects a worsening restore
 * before anything is written, require-approval forces SUPERVISED after the snapshot's own tier was
 * applied, warn restores unchanged.
 */
class AgentVersionServiceTrifectaRestoreTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final String READ = "read_crm";
    private static final String WEB = "fetch_web_page";
    private static final String SEND = "send_mail";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AgentService agentService = mock(AgentService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentConfigSnapshotCodec codec = mock(AgentConfigSnapshotCodec.class);
    private final AgentVersionSnapshotService snapshotService =
            mock(AgentVersionSnapshotService.class);
    private final AgentVersionRepository versionRepository = mock(AgentVersionRepository.class);
    private final AgentConfigSnapshot snapshot = mock(AgentConfigSnapshot.class);
    private final Agent agent =
            new Agent(
                    "Test Agent",
                    "You are helpful.",
                    ProviderName.ANTHROPIC,
                    "claude-sonnet-5",
                    1024,
                    0.7,
                    1.0,
                    false,
                    ExperienceMode.ADVANCED);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private AgentVersionService serviceWith(String mode) {
        AgentVersionService service =
                new AgentVersionService(
                        agentService,
                        agentRepository,
                        mock(AgentSchemaService.class),
                        versionRepository,
                        codec,
                        snapshotService,
                        providerOf(null),
                        mock(AuditLogService.class));

        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        Map<String, ToolClassificationDecision> catalog = new LinkedHashMap<>();
        catalog.put(
                READ,
                classified(READ, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false));
        catalog.put(
                WEB,
                classified(
                        WEB, ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC, false));
        catalog.put(
                SEND, classified(SEND, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, true));
        when(registry.resolveAll(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Map<String, ToolClassificationDecision> out = new LinkedHashMap<>();
                            for (Object name :
                                    (java.util.Collection<?>) invocation.getArgument(0)) {
                                ToolClassificationDecision decision =
                                        catalog.get(String.valueOf(name));
                                if (decision != null) {
                                    out.put(String.valueOf(name), decision);
                                }
                            }
                            return out;
                        });
        LethalTrifectaDetector detector =
                new LethalTrifectaDetector(registry, providerOf((MeterRegistry) meters));
        service.setTrifectaEnforcement(
                new TrifectaEnforcementService(
                        providerOf(detector),
                        providerOf(agentService),
                        providerOf(null),
                        providerOf((MeterRegistry) meters),
                        mode,
                        true,
                        false,
                        true));

        TenantContext.set(TenantContext.of("tenant-1", "user@example.com", Set.of()));
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.EDITOR)).thenReturn(agent);
        AgentVersion version = mock(AgentVersion.class);
        when(version.getId()).thenReturn(VERSION_ID);
        when(version.getVersionNumber()).thenReturn(3);
        when(versionRepository.findByIdAndAgentId(VERSION_ID, AGENT_ID))
                .thenReturn(Optional.of(version));
        when(codec.fromJson(any())).thenReturn(snapshot);
        when(snapshot.allowedToolNames()).thenReturn(List.of(READ, WEB, SEND));
        // The codec copies the snapshot onto the agent: its allow-list AND its stored (autonomous)
        // tier.
        when(codec.apply(eq(snapshot), eq(agent)))
                .thenAnswer(
                        invocation -> {
                            agent.setAllowedToolNames(
                                    new LinkedHashSet<>(snapshot.allowedToolNames()));
                            agent.setAutonomyTier(AgentAutonomyTier.FULLY_AUTONOMOUS);
                            return new AgentConfigSnapshotCodec.RestoreOutcome(List.of());
                        });
        when(agentRepository.save(any(Agent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }

    @Test
    void blockRejectsRestoringAVersionThatWouldCompleteTheTrifectaBeforeAnythingIsWritten() {
        AgentVersionService service = serviceWith("block");
        agent.setAllowedToolNames(Set.of(READ, SEND));

        IllegalArgumentException blocked =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.restore(AGENT_ID, VERSION_ID));

        assertTrue(
                blocked.getMessage().startsWith(TrifectaEnforcementPolicy.BLOCK_MESSAGE_PREFIX),
                blocked.getMessage());
        assertEquals(
                Set.of(READ, SEND),
                agent.getAllowedToolNames(),
                "the stored allow-list must be untouched");
        verify(codec, never()).apply(any(), any());
        verify(agentRepository, never()).save(any(Agent.class));
        verify(snapshotService, never()).snapshot(any(), anyString());
    }

    @Test
    void blockStillRestoresAVersionThatLeavesAnAlreadyLethalAgentNoWorse() {
        AgentVersionService service = serviceWith("block");
        agent.setAllowedToolNames(Set.of(READ, WEB, SEND));

        service.restore(AGENT_ID, VERSION_ID);

        verify(codec).apply(snapshot, agent);
        verify(agentRepository).save(agent);
    }

    @Test
    void requireApprovalForcesSupervisedAfterTheSnapshotTierWasApplied() {
        AgentVersionService service = serviceWith("require-approval");
        agent.setAllowedToolNames(Set.of(READ, SEND));

        service.restore(AGENT_ID, VERSION_ID);

        assertEquals(AgentAutonomyTier.SUPERVISED, agent.getAutonomyTier());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(snapshotService).snapshot(any(), summary.capture());
        assertTrue(summary.getValue().startsWith("Restored from v3"), summary.getValue());
        assertTrue(summary.getValue().contains("SUPERVISED"), summary.getValue());
        assertEquals(
                1.0, meters.counter(TrifectaEnforcementService.APPROVAL_FORCED_METRIC).count());
    }

    @Test
    void requireApprovalLeavesARestoreOfANonLethalVersionAlone() {
        AgentVersionService service = serviceWith("require-approval");
        when(snapshot.allowedToolNames()).thenReturn(List.of(READ, SEND));

        service.restore(AGENT_ID, VERSION_ID);

        assertEquals(
                AgentAutonomyTier.FULLY_AUTONOMOUS,
                agent.getAutonomyTier(),
                "the snapshot's own tier stands when the restored tool mix is not a trifecta");
    }

    @Test
    void warnRestoresUnchanged() {
        AgentVersionService service = serviceWith("warn");
        agent.setAllowedToolNames(Set.of(READ, SEND));

        service.restore(AGENT_ID, VERSION_ID);

        assertEquals(Set.of(READ, WEB, SEND), agent.getAllowedToolNames());
        assertEquals(AgentAutonomyTier.FULLY_AUTONOMOUS, agent.getAutonomyTier());
    }

    @Test
    void anOlderDocumentWithoutAnAllowListIsNotJudged() {
        AgentVersionService service = serviceWith("block");
        when(snapshot.allowedToolNames()).thenReturn(null);
        agent.setAllowedToolNames(Set.of(READ, SEND));
        when(codec.apply(eq(snapshot), eq(agent)))
                .thenReturn(
                        new AgentConfigSnapshotCodec.RestoreOutcome(List.of("allowedToolNames")));

        service.restore(AGENT_ID, VERSION_ID);

        assertEquals(Set.of(READ, SEND), agent.getAllowedToolNames());
        verify(agentRepository).save(agent);
    }

    private static ToolClassificationDecision classified(
            String name, ToolTrustTier trust, ToolSensitivity sensitivity, boolean irreversible) {
        return new ToolClassificationDecision.Classified(
                new ToolClassification(
                        name,
                        trust,
                        sensitivity,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        irreversible,
                        true));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
