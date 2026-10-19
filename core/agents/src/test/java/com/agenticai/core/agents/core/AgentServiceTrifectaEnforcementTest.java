package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementPolicy;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The allow-list save path under each {@code app.tools.classification.trifecta-enforcement} mode:
 * warn keeps today's behaviour, require-approval forces the SUPERVISED tier with a version-history
 * note, block rejects a worsening save before anything is mutated or saved.
 */
class AgentServiceTrifectaEnforcementTest {

    private static final String READ = "read_crm";
    private static final String WEB = "fetch_web_page";
    private static final String SEND = "send_mail";
    private static final String CALC = "calculator";

    private final AgentRepository repository = mock(AgentRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private Agent agent;
    private UUID agentId;

    private AgentService serviceWith(
            String mode, boolean analyseAutoSelect, AgentToolPort toolProvider) {
        AgentService service =
                AgentServiceTestFactory.create(
                        repository,
                        mock(TenantLimits.class),
                        mock(GenerationParamsInferenceService.class),
                        mock(ProviderModelCatalogService.class),
                        mock(ModelModalitiesService.class),
                        mock(PromptVersionRepository.class),
                        mock(VoiceProviderValidator.class),
                        mock(VideoProviderValidator.class),
                        mock(TextModelProviderValidator.class),
                        mock(TextToSpeechProviderValidator.class),
                        mock(ResourceAccessService.class),
                        mock(AgentSchemaService.class),
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0);
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
        catalog.put(
                CALC,
                classified(
                        CALC, ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC, false));
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
        service.setLethalTrifectaDetector(detector);
        service.setTrifectaEnforcement(
                new TrifectaEnforcementService(
                        providerOf(detector),
                        providerOf(service),
                        providerOf(toolProvider),
                        providerOf((MeterRegistry) meters),
                        mode,
                        true,
                        analyseAutoSelect,
                        true));

        agent =
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
        agentId = UUID.randomUUID();
        when(repository.findById(agentId)).thenReturn(Optional.of(agent));
        when(repository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }

    @Test
    void warnSavesALethalMixUnchanged() {
        AgentService service = serviceWith("warn", false, null);

        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, WEB, SEND));

        assertEquals(AgentAutonomyTier.SEMI_AUTONOMOUS, response.autonomyTier());
        assertEquals(Set.of(READ, WEB, SEND), response.allowedToolNames());
        assertEquals(1.0, meters.counter("agent.config.lethal_trifecta").count());
    }

    @Test
    void requireApprovalForcesTheSupervisedTier() {
        AgentService service = serviceWith("require-approval", false, null);
        agent.setAutonomyTier(AgentAutonomyTier.FULLY_AUTONOMOUS);

        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, WEB, SEND));

        assertEquals(AgentAutonomyTier.SUPERVISED, response.autonomyTier());
        assertEquals(AgentAutonomyTier.SUPERVISED, agent.getAutonomyTier());
        assertEquals(
                1.0, meters.counter(TrifectaEnforcementService.APPROVAL_FORCED_METRIC).count());
    }

    @Test
    void requireApprovalLeavesANonLethalSaveAlone() {
        AgentService service = serviceWith("require-approval", false, null);

        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, SEND));

        assertEquals(AgentAutonomyTier.SEMI_AUTONOMOUS, response.autonomyTier());
        assertEquals(
                0.0, meters.counter(TrifectaEnforcementService.APPROVAL_FORCED_METRIC).count());
    }

    @Test
    void blockRejectsAWorseningSaveBeforeAnythingIsMutatedOrSaved() {
        AgentService service = serviceWith("block", false, null);
        agent.setAllowedToolNames(Set.of(READ, SEND));

        IllegalArgumentException blocked =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setAllowedToolNamesEnforced(
                                        agentId, Set.of(READ, WEB, SEND)));

        assertTrue(
                blocked.getMessage().startsWith(TrifectaEnforcementPolicy.BLOCK_MESSAGE_PREFIX),
                blocked.getMessage());
        assertEquals(
                Set.of(READ, SEND),
                agent.getAllowedToolNames(),
                "the stored allow-list must be untouched");
        verify(repository, never()).save(any(Agent.class));
    }

    @Test
    void blockNeverFailsAServerSideWriterButFallsBackToTheSupervisedTier() {
        AgentService service = serviceWith("block", false, null);
        agent.setAllowedToolNames(Set.of(READ, SEND));

        // Template install / demo studio / service envoys / research runs call the plain setter.
        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, WEB, SEND));

        assertEquals(
                Set.of(READ, WEB, SEND),
                response.allowedToolNames(),
                "the allow-list must still be applied");
        assertEquals(AgentAutonomyTier.SUPERVISED, response.autonomyTier());
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.BLOCKED_METRIC).count());
    }

    @Test
    void blockStillAllowsASaveThatRemovesALeg() {
        AgentService service = serviceWith("block", false, null);
        agent.setAllowedToolNames(Set.of(READ, WEB, SEND));

        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, SEND));

        assertEquals(Set.of(READ, SEND), response.allowedToolNames());
    }

    @Test
    void blockStillAllowsAnUnrelatedChangeToAnAlreadyLethalAgent() {
        AgentService service = serviceWith("block", false, null);
        agent.setAllowedToolNames(Set.of(READ, WEB, SEND));

        AgentResponse response =
                service.setAllowedToolNames(agentId, Set.of(READ, WEB, SEND, CALC));

        assertEquals(Set.of(READ, WEB, SEND, CALC), response.allowedToolNames());
    }

    @Test
    void blockAlsoCoversSwitchingAToolRestrictedAgentToAutoSelectWhenThatIsAnalysed() {
        AgentToolPort toolProvider = mock(AgentToolPort.class);
        when(toolProvider.listAvailableToolsFor(any()))
                .thenReturn(
                        List.of(
                                new ToolDescriptor(READ, "r"),
                                new ToolDescriptor(WEB, "w"),
                                new ToolDescriptor(SEND, "s")));
        AgentService service = serviceWith("block", true, toolProvider);
        agent.setAllowedToolNames(Set.of(READ, SEND));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.setExperienceModeEnforced(agentId, ExperienceMode.SIMPLE));

        assertEquals(ExperienceMode.ADVANCED, agent.getExperienceMode());
        verify(repository, never()).save(any(Agent.class));
    }

    @Test
    void statusReportsTheModeAndWhetherTheAgentIsGated() {
        AgentService service = serviceWith("require-approval", false, null);
        agent.setAllowedToolNames(Set.of(READ, WEB, SEND));
        agent.setAutonomyTier(AgentAutonomyTier.SUPERVISED);

        TrifectaStatusResponse status = service.trifectaStatus(agentId);

        assertEquals("require-approval", status.enforcement());
        assertTrue(status.lethalTrifecta());
        assertTrue(status.enforced());
        assertTrue(status.gated());
    }

    @Test
    void withoutEnforcementWiredTheOldAdvisoryBehaviourRemains() {
        AgentService service = serviceWith("block", false, null);
        service.setTrifectaEnforcement(null);

        AgentResponse response = service.setAllowedToolNames(agentId, Set.of(READ, WEB, SEND));

        assertEquals(AgentAutonomyTier.SEMI_AUTONOMOUS, response.autonomyTier());
        assertEquals("warn", service.trifectaStatus(agentId).enforcement());
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
