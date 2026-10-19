package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.skills.SkillService.AgentSkillSettings;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tutor.dto.TutorDtos.TutorAgentResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Find-or-create of the caller's tutor agent: idempotent, plan-limit aware, and configured exactly
 * once.
 */
class TutorAgentProvisionerTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SkillService skillService = mock(SkillService.class);
    private final TenantLimits tenantLimits = mock(TenantLimits.class);
    private final ProviderCredentialResolver credentialResolver =
            mock(ProviderCredentialResolver.class);
    private final StudyPlanService studyPlans = mock(StudyPlanService.class);
    private final TutorAgentProvisioner provisioner =
            new TutorAgentProvisioner(
                    agentRepository,
                    agentService,
                    skillService,
                    tenantLimits,
                    credentialResolver,
                    studyPlans);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static Agent agent(UUID id, String name, Instant createdAt) {
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getName()).thenReturn(name);
        when(agent.getCreatedAt()).thenReturn(createdAt);
        return agent;
    }

    private AgentResponse createdResponse(UUID id) {
        AgentResponse response = mock(AgentResponse.class);
        when(response.id()).thenReturn(id);
        when(agentService.create(any(AgentCreateRequest.class))).thenReturn(response);
        return response;
    }

    @Test
    void createsTheTutorOnTheHostedPoolWithTheTutorSkillsAndSharedMemory() {
        UUID id = UUID.randomUUID();
        Agent created =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(), List.of(created));
        createdResponse(id);

        TutorAgentResponse response = provisioner.ensureTutorAgent("u1", null);

        assertEquals(id, response.agentId());
        assertTrue(response.created());
        ArgumentCaptor<AgentCreateRequest> request =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(request.capture());
        assertEquals(TutorAgentProvisioner.TUTOR_AGENT_NAME, request.getValue().name());
        assertEquals(ProviderName.PLATFORM_HOSTED, request.getValue().providerName());
        assertEquals(Boolean.FALSE, request.getValue().knowledgeBaseEnabled());
        assertFalse(request.getValue().systemPrompt().isBlank());
        verify(skillService).setAgentSettings(id, true, Set.of("tutor", "generative-ui"));
        verify(agentService).setShareMemoryWithTenant(id, true);
        verifyNoInteractions(studyPlans);
    }

    @Test
    void aSecondCallReturnsTheSameAgentWithoutCreatingAnother() {
        UUID id = UUID.randomUUID();
        Agent other =
                agent(UUID.randomUUID(), "Sales helper", Instant.parse("2026-01-01T00:00:00Z"));
        Agent tutor =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(other, tutor));
        when(skillService.getAgentSettings(id))
                .thenReturn(new AgentSkillSettings(true, Set.of("tutor", "generative-ui")));
        when(agentService.isShareMemoryWithTenant(id)).thenReturn(true);

        TutorAgentResponse response = provisioner.ensureTutorAgent("u1", null);

        assertEquals(id, response.agentId());
        assertFalse(response.created());
        verify(agentService, never()).create(any(AgentCreateRequest.class));
        verify(skillService, never()).setAgentSettings(any(UUID.class), anyBoolean(), any());
        verify(agentService, never()).setShareMemoryWithTenant(any(UUID.class), anyBoolean());
    }

    @Test
    void aHalfProvisionedTutorIsRepairedNotDuplicated() {
        UUID id = UUID.randomUUID();
        Agent tutor =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(tutor));
        when(skillService.getAgentSettings(id)).thenReturn(new AgentSkillSettings(true, Set.of()));

        TutorAgentResponse response = provisioner.ensureTutorAgent("u1", null);

        assertFalse(response.created());
        verify(agentService, never()).create(any(AgentCreateRequest.class));
        verify(skillService).setAgentSettings(id, true, Set.of("tutor", "generative-ui"));
        verify(agentService).setShareMemoryWithTenant(id, true);
    }

    @Test
    void aTutorWithSkillsButNoSharedMemoryIsRepairedToo() {
        UUID id = UUID.randomUUID();
        Agent tutor =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(tutor));
        when(skillService.getAgentSettings(id))
                .thenReturn(new AgentSkillSettings(true, Set.of("tutor", "generative-ui")));
        when(agentService.isShareMemoryWithTenant(id)).thenReturn(false);

        assertEquals(id, provisioner.ensureTutorAgent("u1", null).agentId());

        verify(agentService).setShareMemoryWithTenant(id, true);
        verify(agentService, never()).create(any(AgentCreateRequest.class));
    }

    @Test
    void anEditLockedTutorIsStillReturnedWhenTheRepairIsRefused() {
        UUID id = UUID.randomUUID();
        Agent tutor =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(tutor));
        when(skillService.getAgentSettings(id))
                .thenReturn(new AgentSkillSettings(true, Set.of("tutor", "generative-ui")));
        when(agentService.isShareMemoryWithTenant(id)).thenReturn(false);
        when(agentService.setShareMemoryWithTenant(id, true))
                .thenThrow(
                        new TenantLimitExceededException("This agent is locked on the Free plan."));

        TutorAgentResponse response = provisioner.ensureTutorAgent("u1", null);

        assertEquals(id, response.agentId());
        assertFalse(response.created());
    }

    @Test
    void theLoserOfACrossReplicaRaceDeletesItsOwnDuplicate() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Agent older =
                agent(
                        theirs,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T09:59:59Z"));
        Agent newer =
                agent(
                        mine,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(), List.of(newer, older));
        createdResponse(mine);

        TutorAgentResponse response = provisioner.ensureTutorAgent("u1", null);

        assertEquals(theirs, response.agentId());
        assertFalse(response.created());
        verify(agentService).delete(mine);
        verify(agentService, never()).delete(theirs);
    }

    @Test
    void whenTwoExistTheOldestWins() {
        UUID older = UUID.randomUUID();
        Agent first =
                agent(
                        older,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-01T10:00:00Z"));
        Agent second =
                agent(
                        UUID.randomUUID(),
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(second, first));
        when(skillService.getAgentSettings(older))
                .thenReturn(new AgentSkillSettings(true, Set.of("tutor")));

        assertEquals(older, provisioner.ensureTutorAgent("u1", null).agentId());
    }

    @Test
    void aPaidTenantWithItsOwnKeyGetsThatProviderAndTheContextSubjectOwnsTheAgent() {
        TenantContext.set(TenantContext.of("acme", "kc-subject", Set.of()));
        UUID id = UUID.randomUUID();
        when(agentRepository.findByCreatedBy("kc-subject")).thenReturn(List.of());
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(credentialResolver.resolveAnyActiveKey())
                .thenReturn(
                        new ProviderCredentialResolver.ActiveProvider(ProviderName.ANTHROPIC, "k"));
        createdResponse(id);

        TutorAgentResponse response = provisioner.ensureTutorAgent("principal-name", null);

        assertEquals(id, response.agentId());
        ArgumentCaptor<AgentCreateRequest> request =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(request.capture());
        assertEquals(ProviderName.ANTHROPIC, request.getValue().providerName());
        verify(agentRepository, never()).findByCreatedBy("principal-name");
    }

    @Test
    void aPaidTenantWithoutAKeyFallsBackToTheHostedPool() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of());
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        when(credentialResolver.resolveAnyActiveKey())
                .thenThrow(new IllegalStateException("no key"));
        createdResponse(UUID.randomUUID());

        provisioner.ensureTutorAgent("u1", null);

        ArgumentCaptor<AgentCreateRequest> request =
                ArgumentCaptor.forClass(AgentCreateRequest.class);
        verify(agentService).create(request.capture());
        assertEquals(ProviderName.PLATFORM_HOSTED, request.getValue().providerName());
    }

    @Test
    void anAgentLimitIsA409WithAPlainMessage() {
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of());
        when(agentService.create(any(AgentCreateRequest.class)))
                .thenThrow(new TenantLimitExceededException("This plan allows up to 3 agents."));

        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> provisioner.ensureTutorAgent("u1", UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("This plan allows up to 3 agents."), e.getReason());
        verifyNoInteractions(skillService);
        verifyNoInteractions(studyPlans);
    }

    @Test
    void aPlanIdLinksTheTutorToTheCallersOwnPlan() {
        UUID id = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Agent tutor =
                agent(
                        id,
                        TutorAgentProvisioner.TUTOR_AGENT_NAME,
                        Instant.parse("2026-10-03T10:00:00Z"));
        when(agentRepository.findByCreatedBy("u1")).thenReturn(List.of(tutor));
        when(skillService.getAgentSettings(id))
                .thenReturn(new AgentSkillSettings(true, Set.of("tutor")));

        provisioner.ensureTutorAgent("u1", planId);

        verify(studyPlans).setTutorAgent(eq("u1"), eq(planId), eq(id));
    }

    @Test
    void theControllerPassesThePrincipalAndTheOptionalPlan() {
        TutorAgentProvisioner mocked = mock(TutorAgentProvisioner.class);
        TutorAgentController controller = new TutorAgentController(mocked);
        Principal principal = () -> "u1";
        UUID planId = UUID.randomUUID();
        TutorAgentResponse response = new TutorAgentResponse(UUID.randomUUID(), true);
        when(mocked.ensureTutorAgent("u1", null)).thenReturn(response);
        when(mocked.ensureTutorAgent("u1", planId)).thenReturn(response);

        assertEquals(response, controller.ensure(null, principal));
        assertEquals(
                response,
                controller.ensure(
                        new com.agenticai.core.tutor.dto.TutorDtos.TutorAgentRequest(planId),
                        principal));
        assertThrows(ResponseStatusException.class, () -> controller.ensure(null, null));
    }
}
