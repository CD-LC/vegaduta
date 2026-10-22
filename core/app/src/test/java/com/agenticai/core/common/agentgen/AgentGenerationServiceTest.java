package com.agenticai.core.common.agentgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.AssistService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (mocked collaborators, no Spring context, no DB - the repo's app-test convention)
 * for {@link AgentGenerationService}'s spec parsing, normalization and channel-occupancy rules -
 * the parts that decide whether an LLM's loose JSON becomes a sane agent or a clean failure.
 */
class AgentGenerationServiceTest {

    private final AssistService assistService = mock(AssistService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
    private final ProviderCredentialResolver credentialResolver =
            mock(ProviderCredentialResolver.class);
    private final TenantLimits tenantLimits = mock(TenantLimits.class);

    private final AgentGenerationService service =
            new AgentGenerationService(
                    assistService,
                    agentService,
                    channelSessionService,
                    credentialResolver,
                    tenantLimits,
                    new ObjectMapper());

    private UUID stubAgent() {
        UUID id = UUID.randomUUID();
        AgentResponse response = mock(AgentResponse.class);
        when(response.id()).thenReturn(id);
        when(response.name()).thenReturn("Returns Helper");
        when(agentService.create(any())).thenReturn(response);
        return id;
    }

    @Test
    void generatesAgentFromFencedJsonAndBindsFreeChannels() {
        UUID agentId = stubAgent();
        when(assistService.complete(any(), any()))
                .thenReturn(
                        """
            ```json
            {"name": "Returns Helper", "description": "Handles refunds",
             "systemPrompt": "You are a refunds specialist. Be polite.",
             "knowledgeBaseEnabled": true, "shareMemoryWithTenant": false,
             "reasoningDepth": "careful", "suggestedChannels": ["telegram", "whatsapp", "bogus"]}
            ```
            """);
        when(channelSessionService.routingFor(any())).thenReturn(Optional.empty());
        when(tenantLimits.plan(any())).thenReturn(TenantPlan.FREE);

        Map<String, Object> result = service.generate("an agent that handles refunds");

        assertEquals(agentId, result.get("agentId"));
        assertEquals(List.of("TELEGRAM"), result.get("boundChannels"));
        verify(channelSessionService).setDefaultAgent(ChannelType.TELEGRAM, agentId);
        // WhatsApp is never bound from generation (it needs a declared task: policy guard), and the
        // invented channel name lands in skipped, never throws.
        verify(channelSessionService, never()).setDefaultAgent(eq(ChannelType.WHATSAPP), any());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> skipped =
                (List<Map<String, String>>) result.get("skippedChannels");
        assertTrue(
                skipped.stream()
                        .anyMatch(
                                m ->
                                        "WHATSAPP".equals(m.get("channel"))
                                                && m.get("reason").contains("declared task")));
        assertTrue(skipped.stream().anyMatch(m -> "BOGUS".equals(m.get("channel"))));
        @SuppressWarnings("unchecked")
        List<String> applied = (List<String>) result.get("appliedCapabilities");
        assertTrue(applied.contains("knowledgeBaseEnabled"));
        assertTrue(applied.contains("reasoningDepth=CAREFUL"));
        // FREE tenant → PLATFORM_HOSTED, no credential resolution attempted.
        verify(credentialResolver, never()).resolveAnyActiveKey();
    }

    @Test
    void skipsChannelAlreadyRoutedToAnotherAgent() {
        UUID agentId = stubAgent();
        UUID otherAgent = UUID.randomUUID();
        when(assistService.complete(any(), any()))
                .thenReturn(
                        "{\"name\": \"A\", \"systemPrompt\": \"Be helpful.\", \"suggestedChannels\": [\"SLACK\"]}");
        when(channelSessionService.routingFor(ChannelType.SLACK))
                .thenReturn(Optional.of(new ChannelDefaultAgent(ChannelType.SLACK, otherAgent)));
        when(tenantLimits.plan(any())).thenReturn(TenantPlan.FREE);

        Map<String, Object> result = service.generate("an agent");

        // An occupied channel is reported, never re-routed - the
        // least-surprise rule the class javadoc names.
        verify(channelSessionService, never()).setDefaultAgent(any(), any());
        assertTrue(((List<?>) result.get("boundChannels")).isEmpty());
        assertTrue(result.get("skippedChannels").toString().contains(otherAgent.toString()));
        assertEquals(agentId, result.get("agentId"));
    }

    @Test
    void missingSystemPromptIsAGenerationFailureNotASilentDefault() {
        when(assistService.complete(any(), any())).thenReturn("{\"name\": \"A\"}");

        assertThrows(AgentGenerationException.class, () -> service.generate("an agent"));
        verify(agentService, never()).create(any());
    }

    @Test
    void unparseableSpecIsAGenerationFailure() {
        when(assistService.complete(any(), any())).thenReturn("sure! here's an agent idea:");

        assertThrows(AgentGenerationException.class, () -> service.generate("an agent"));
        verify(agentService, never()).create(any());
    }
}
