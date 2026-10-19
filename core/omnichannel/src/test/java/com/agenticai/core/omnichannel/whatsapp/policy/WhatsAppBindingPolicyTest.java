package com.agenticai.core.omnichannel.whatsapp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WhatsAppBindingPolicyTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentService agents;
    private ChannelSessionService sessions;
    private AuditLogService audit;
    private WhatsAppBindingPolicy policy;
    private ChannelDefaultAgent row;

    @BeforeEach
    void setUp() {
        agents = mock(AgentService.class);
        sessions = mock(ChannelSessionService.class);
        audit = mock(AuditLogService.class);
        policy = new WhatsAppBindingPolicy(agents, sessions, audit);
        row = new ChannelDefaultAgent(ChannelType.WHATSAPP, agentId);
        when(sessions.routingFor(ChannelType.WHATSAPP)).thenReturn(Optional.of(row));
    }

    private void agentWithPrompt(String prompt) {
        AgentResponse agent = mock(AgentResponse.class);
        when(agent.systemPrompt()).thenReturn(prompt);
        when(agent.allowedToolNames()).thenReturn(Set.of());
        when(agents.getGrantedAgent(agentId)).thenReturn(agent);
    }

    private static final String SCOPED =
            "You are the front desk for a clinic. Help customers book appointments. Only answer"
                    + " questions about the clinic.";

    @Test
    void missingTaskIsRefusedAndNothingIsSaved() {
        agentWithPrompt(SCOPED);
        var o = policy.bindDefault(agentId, null, false);
        assertEquals(WhatsAppBindingPolicy.Status.TASK_REQUIRED, o.status());
        assertEquals(HttpStatus.BAD_REQUEST, WhatsAppBindingResponses.toResponse(o).getStatusCode());
        verify(sessions, never()).setDefaultAgent(any(), any());

        assertEquals(
                WhatsAppBindingPolicy.Status.TASK_REQUIRED,
                policy.bindDefault(agentId, "general chat", false).status());
    }

    @Test
    void scopedAgentBindsAndTheDeclarationIsStored() {
        agentWithPrompt(SCOPED);
        var o = policy.bindDefault(agentId, "booking", false);

        assertEquals(WhatsAppBindingPolicy.Status.BOUND, o.status());
        verify(sessions).setDefaultAgent(ChannelType.WHATSAPP, agentId);
        assertEquals("BOOKING", row.getDeclaredTask());
        assertTrue(row.getDeclaredTaskAt() != null);
        assertEquals(false, row.isOpenEndedOverride());
        verify(audit)
                .record(
                        anyString(),
                        eq("WHATSAPP_AGENT_BOUND"),
                        eq("agent"),
                        eq(agentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void openEndedAgentWarnsWithoutSaving() {
        agentWithPrompt("You are a general-purpose assistant. Answer anything the user asks.");
        var o = policy.bindDefault(agentId, "SUPPORT", false);

        assertEquals(WhatsAppBindingPolicy.Status.OPEN_ENDED_WARNING, o.status());
        assertEquals(HttpStatus.CONFLICT, WhatsAppBindingResponses.toResponse(o).getStatusCode());
        assertTrue(!o.reasons().isEmpty());
        verify(sessions, never()).setDefaultAgent(any(), any());
    }

    @Test
    void acknowledgedOverrideSavesAndIsLogged() {
        agentWithPrompt("You are a general-purpose assistant. Answer anything the user asks.");
        var o = policy.bindDefault(agentId, "SUPPORT", true);

        assertEquals(WhatsAppBindingPolicy.Status.BOUND, o.status());
        verify(sessions).setDefaultAgent(ChannelType.WHATSAPP, agentId);
        assertTrue(row.isOpenEndedOverride());
        verify(audit)
                .record(
                        anyString(),
                        eq("WHATSAPP_OPEN_ENDED_OVERRIDE"),
                        eq("agent"),
                        eq(agentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void unknownAgentIs404() {
        when(agents.getGrantedAgent(agentId)).thenThrow(new AgentNotFoundException(agentId));
        var o = policy.bindDefault(agentId, "SUPPORT", false);
        assertEquals(HttpStatus.NOT_FOUND, WhatsAppBindingResponses.toResponse(o).getStatusCode());
    }
}
