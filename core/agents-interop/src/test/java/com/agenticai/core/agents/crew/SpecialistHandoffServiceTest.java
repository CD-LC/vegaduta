package com.agenticai.core.agents.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SpecialistHandoffService} with every collaborator mocked (no DB, no Spring context) -
 * mirrors {@code AgentExperimentServiceTest}'s structure. Covers the two contracts {@code
 * AgentChatService#delegateToSpecialist} and {@code AgentToolProvider#resolveSpecialistDelegationTools}
 * both depend on: {@link SpecialistHandoffService#findSpecialist}'s "first resolvable match wins,
 * skip stale registrations" fail-open behavior, and that the audit calls are plain pass-throughs
 * to {@link AgentHandoffAuditService}.
 */
class SpecialistHandoffServiceTest {

    private static final UUID ROOT_AGENT = UUID.randomUUID();

    private SpecialistRegistry specialistRegistry;
    private AgentHandoffAuditService auditService;
    private AgentService agentService;
    private SpecialistHandoffServiceImpl service;

    @BeforeEach
    void setUp() {
        specialistRegistry = mock(SpecialistRegistry.class);
        auditService = mock(AgentHandoffAuditService.class);
        agentService = mock(AgentService.class);
        service = new SpecialistHandoffServiceImpl(specialistRegistry, auditService, agentService);
    }

    private static AgentSpecialization registration(UUID agentId, String tag) {
        return new AgentSpecialization(agentId, tag, "a specialist");
    }

    private static AgentResponse agentResponse(String name) {
        // AgentResponse is a large record - mocked rather than constructed so this test does not
        // have to keep its (unrelated) full field list in sync. Only name() is stubbed since
        // that is the only accessor SpecialistHandoffService#findSpecialist actually reads.
        AgentResponse response = mock(AgentResponse.class);
        when(response.name()).thenReturn(name);
        return response;
    }

    @Test
    void findSpecialistReturnsTheFirstResolvableMatch() {
        UUID specialistId = UUID.randomUUID();
        when(specialistRegistry.findByCapability("billing"))
            .thenReturn(List.of(registration(specialistId, "billing")));
        AgentResponse response1 = agentResponse("Billing Bot");

        when(agentService.get(specialistId)).thenReturn(response1);

        Optional<SpecialistCandidate> found = service.findSpecialist("billing");

        assertTrue(found.isPresent());
        assertEquals(specialistId, found.get().agentId());
        assertEquals("Billing Bot", found.get().agentName());
        assertEquals("billing", found.get().capabilityTag());
    }

    @Test
    void findSpecialistSkipsARegistrationThatNoLongerResolvesAndFallsThroughToTheNextOne() {
        UUID deletedAgentId = UUID.randomUUID();
        UUID liveAgentId = UUID.randomUUID();
        when(specialistRegistry.findByCapability("billing")).thenReturn(List.of(
            registration(deletedAgentId, "billing"),
            registration(liveAgentId, "billing")
        ));
        when(agentService.get(deletedAgentId)).thenThrow(new RuntimeException("agent not found"));
        AgentResponse response2 = agentResponse("Live Billing Agent");

        when(agentService.get(liveAgentId)).thenReturn(response2);

        Optional<SpecialistCandidate> found = service.findSpecialist("billing");

        assertTrue(found.isPresent());
        assertEquals(liveAgentId, found.get().agentId());
    }

    @Test
    void findSpecialistReturnsEmptyWhenNothingIsRegisteredForTheTag() {
        when(specialistRegistry.findByCapability("legal-review")).thenReturn(List.of());

        assertTrue(service.findSpecialist("legal-review").isEmpty());
    }

    @Test
    void listCapabilityTagsDelegatesToTheRegistry() {
        when(specialistRegistry.listDistinctCapabilityTags()).thenReturn(List.of("billing", "legal-review"));

        assertEquals(List.of("billing", "legal-review"), service.listCapabilityTags());
    }

    @Test
    void recordHandoffStartDelegatesToTheAuditService() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT_AGENT);
        UUID targetAgentId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        when(auditService.recordStart(chain, targetAgentId, "billing", "refund question")).thenReturn(auditId);

        UUID result = service.recordHandoffStart(chain, targetAgentId, "billing", "refund question");

        assertEquals(auditId, result);
        verify(auditService).recordStart(chain, targetAgentId, "billing", "refund question");
    }

    @Test
    void recordHandoffOutcomeAndRefusalDelegateToTheAuditServiceUnchanged() {
        HandoffChain chain = HandoffChain.root("session-1", ROOT_AGENT);
        UUID auditId = UUID.randomUUID();

        service.recordHandoffOutcome(auditId, HandoffStatus.SUCCESS, null);
        verify(auditService).recordOutcome(auditId, HandoffStatus.SUCCESS, null);

        service.recordHandoffRefusal(chain, null, "billing", "task", HandoffStatus.NO_SPECIALIST_FOUND, "no match");
        verify(auditService).recordRefusal(chain, null, "billing", "task", HandoffStatus.NO_SPECIALIST_FOUND, "no match");

        verify(auditService, never()).recordStart(any(), any(), any(), any());
    }

    @Test
    void findChainForTurnDelegatesToTheAuditService() {
        when(auditService.findChainForTurn("session-1")).thenReturn(List.of());

        assertEquals(List.of(), service.findChainForTurn("session-1"));
        verify(auditService).findChainForTurn(eq("session-1"));
    }
}
