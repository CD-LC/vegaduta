package com.agenticai.core.common.quicktask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.decision.AgentRelevanceSelector;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.VoiceCallService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.tenancy.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Plain unit test (mocked collaborators, no Spring context, no DB - see {@code docs/}' "core/app
 * tests need live DB" note for why a full context boot is off-limits here) for {@link
 * QuickTaskService#buildAdditionalAgentTools}'s tenant-context wrap (2026-08-19 MCP-blackout
 * follow-up sweep).
 *
 * <p>The invariant under test is the one {@code AgentJobExecution#runOneTurn} documents for the
 * exact same {@code extraTools} seam: "the wrap is mandatory, not stylistic" - extraTools bypass
 * guardAndLog and execute on a reactive thread that does not inherit the plain-ThreadLocal {@code
 * TenantContext}, so an unwrapped {@code DelegateToAgentToolCallback} resolved its tenant-scoped
 * agent lookup against the empty public schema. QuickTaskService was the one unwrapped extraTools
 * producer in the repo.
 */
class QuickTaskServiceTest {

    private static AgentResponse agentResponse(String name, String systemPrompt) {
        return AgentResponse.from(
                new Agent(
                        name,
                        systemPrompt,
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.ADVANCED));
    }

    @Test
    void buildAdditionalAgentToolsWrapsEachDelegationToolInTenantContextPropagation() {
        AgentService agentService = mock(AgentService.class);
        AgentChatService agentChatService = mock(AgentChatService.class);
        AgentRelevanceSelector agentRelevanceSelector = mock(AgentRelevanceSelector.class);
        QuickTaskService service =
                new QuickTaskService(
                        mock(AppUserRepository.class),
                        agentService,
                        agentChatService,
                        mock(AgentToolProvider.class),
                        agentRelevanceSelector,
                        mock(WhatsAppDispatchService.class),
                        mock(SmsDispatchService.class),
                        mock(VoiceCallService.class),
                        mock(HumanApprovalGateService.class),
                        mock(ToolCallLogService.class),
                        mock(QuickTaskRunService.class),
                        com.agenticai.core.json.JsonMappers.plain(),
                        mock(AuditLogService.class),
                        mock(ChannelTextSends.class));

        AgentResponse primary = agentResponse("Primary", "You are the primary agent.");
        AgentResponse collaborator =
                agentResponse("CRM Logger", "Logs completed work into the CRM.");
        when(agentService.listAll()).thenReturn(List.of(primary, collaborator));
        when(agentRelevanceSelector.selectAdditionalRelevant(any(), any(), any(), anyInt()))
                .thenReturn(List.of(collaborator));

        List<ToolCallback> tools =
                service.buildAdditionalAgentTools(primary, "translate this and log it in the CRM");

        assertEquals(1, tools.size());
        // The wrap itself - the whole fix. Against the old code this was a raw
        // DelegateToAgentToolCallback and this assertion fails.
        assertInstanceOf(TenantContextPropagatingToolCallback.class, tools.get(0));
        // Definition delegation survives the wrap: the model still sees the real
        // ask_agent_<slug> tool, not the wrapper.
        assertEquals("ask_agent_crm_logger", tools.get(0).getToolDefinition().name());
    }

    @Test
    void buildAdditionalAgentToolsReturnsEmptyWhenNoOtherAgentIsRelevant() {
        AgentService agentService = mock(AgentService.class);
        AgentRelevanceSelector agentRelevanceSelector = mock(AgentRelevanceSelector.class);
        QuickTaskService service =
                new QuickTaskService(
                        mock(AppUserRepository.class),
                        agentService,
                        mock(AgentChatService.class),
                        mock(AgentToolProvider.class),
                        agentRelevanceSelector,
                        mock(WhatsAppDispatchService.class),
                        mock(SmsDispatchService.class),
                        mock(VoiceCallService.class),
                        mock(HumanApprovalGateService.class),
                        mock(ToolCallLogService.class),
                        mock(QuickTaskRunService.class),
                        com.agenticai.core.json.JsonMappers.plain(),
                        mock(AuditLogService.class),
                        mock(ChannelTextSends.class));
        AgentResponse primary = agentResponse("Primary", "You are the primary agent.");
        when(agentService.listAll()).thenReturn(List.of(primary));
        when(agentRelevanceSelector.selectAdditionalRelevant(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        assertTrue(service.buildAdditionalAgentTools(primary, "just answer this").isEmpty());
    }
}
