package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.dto.GenerateGraphResponse;
import com.agenticai.core.workflow.dto.RefGraphResponse;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowSendApprovalGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The generator's deterministic contract around the model call: whatever the model returns, a send is never
 * handed back without an approval in front of it (flag on), and the response carries the explanation,
 * schedule suggestion and requirements derived from the final graph.
 */
class WorkflowGraphGeneratorServiceTest {

    /** A model reply with NO approval node: trigger -> fetch -> loop -> whatsapp, plus a Monday schedule. */
    private static final String UNGATED_REPLY = """
        {"graph": {"nodes": [
            {"id": "t", "type": "trigger", "position": {"x": 80, "y": 160}, "data": {"label": "Start", "config": {"channel": "WEBHOOK"}}},
            {"id": "fetch", "type": "http", "position": {"x": 340, "y": 160}, "data": {"label": "Unpaid invoices",
              "config": {"method": "GET", "url": "https://api.example.com/invoices",
                "headers": [{"name": "Authorization", "value": "Bearer {{secret.BILLING_API_KEY}}"}]}}},
            {"id": "each", "type": "loop", "position": {"x": 600, "y": 160}, "data": {"label": "Each invoice", "config": {"itemsField": ""}}},
            {"id": "wa", "type": "output", "position": {"x": 860, "y": 160}, "data": {"label": "Remind",
              "config": {"channel": "whatsapp", "whatsappTo": "{{ $json.phone }}"}}}
          ], "edges": [
            {"id": "e1", "source": "t", "target": "fetch", "sourceHandle": null, "targetHandle": null},
            {"id": "e2", "source": "fetch", "target": "each", "sourceHandle": null, "targetHandle": null},
            {"id": "e3", "source": "each", "target": "wa", "sourceHandle": null, "targetHandle": null}
          ]},
         "notes": ["Store your billing API key as a secret."],
         "schedule": {"cron": "0 9 * * 1", "label": "Every Monday 09:00"},
         "secretsNeeded": ["BILLING_API_KEY"],
         "knowledgeNeeded": []}
        """;

    private OrchestratorService orchestrator;
    private ChatClient chatClient;
    private WhatsAppDispatchService whatsApp;
    private ApprovalPolicyService approvalPolicy;
    private AnswerConsistencyChecker consistencyChecker;
    private ReflexionService reflexion;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        orchestrator = mock(OrchestratorService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        whatsApp = mock(WhatsAppDispatchService.class);
        approvalPolicy = mock(ApprovalPolicyService.class);
        when(chatClient.prompt().system(any(String.class)).user(any(String.class)).call().content())
            .thenReturn(UNGATED_REPLY);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorkflowGraphGeneratorService service(boolean enforceApproval) {
        return service(enforceApproval, 4096);
    }

    @SuppressWarnings("unchecked")
    private WorkflowGraphGeneratorService service(boolean enforceApproval, int maxTokens) {
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("acme")).thenReturn(TenantPlan.FREE);
        // The generator routes by usesPlatformPool (2b3d7e42), then calls the plan-aware overload.
        when(limits.usesPlatformPool("acme", TenantPlan.FREE)).thenReturn(true);
        FreeTierChatClientService freeTier = mock(FreeTierChatClientService.class);
        when(freeTier.buildClientForFreeTenant(anyString(), any(TenantPlan.class), anyInt(), anyDouble(), anyDouble()))
            .thenReturn(chatClient);
        AgentService agents = mock(AgentService.class);
        when(agents.listAll()).thenReturn(List.of());
        ObjectProvider<WhatsAppDispatchService> whatsAppProvider = mock(ObjectProvider.class);
        when(whatsAppProvider.getIfAvailable()).thenReturn(whatsApp);
        ObjectProvider<ApprovalPolicyService> policyProvider = mock(ObjectProvider.class);
        when(policyProvider.getIfAvailable()).thenReturn(approvalPolicy);
        consistencyChecker = mock(AnswerConsistencyChecker.class);
        reflexion = mock(ReflexionService.class);
        return new WorkflowGraphGeneratorService(
            mock(ProviderCredentialResolver.class), mock(ChatClientRegistry.class), mock(LlmGatewayService.class),
            mock(ProviderModelCatalogService.class), agents, orchestrator, new ObjectMapper(), limits, freeTier,
            consistencyChecker, reflexion, mock(TaskPlanningService.class),
            "test-model", maxTokens, enforceApproval, whatsAppProvider, policyProvider);
    }

    private static WorkflowNodeDto node(WorkflowGraphDto g, String id) {
        return g.nodes().stream().filter(n -> id.equals(n.id())).findFirst().orElseThrow();
    }

    @Test
    void anUngatedSendIsGatedInCodeAndTheGuardedGraphIsTheOneValidated() {
        when(whatsApp.isAnyProviderConnected()).thenReturn(false);
        when(approvalPolicy.requiresApproval("workflow.node.approval")).thenReturn(false);

        GenerateGraphResponse response = service(true).generate(
            "Every Monday pull unpaid invoices and WhatsApp a reminder", ReasoningDepth.FAST, null);

        WorkflowGraphDto graph = response.graph();
        assertTrue(WorkflowSendApprovalGuard.ungatedSends(graph).isEmpty(), "no send without a gate");
        assertEquals("approval", node(graph, "auto-approval-each").type());
        assertEquals(1, response.insertedApprovals().size());
        assertEquals("each", response.insertedApprovals().get(0).beforeNodeId());
        assertEquals("wa", response.insertedApprovals().get(0).sendNodeId());
        verify(orchestrator).validate(graph);

        assertEquals("create", response.mode());
        assertEquals("0 9 * * 1", response.suggestedSchedule().cron());
        assertEquals("Every Monday 09:00", response.suggestedSchedule().label());
        assertEquals("UTC", response.suggestedSchedule().timezone());
        assertEquals(List.of("t", "fetch", "auto-approval-each", "each", "wa"),
            response.explanation().stream().map(GenerateGraphResponse.ExplanationStep::nodeId).toList());
        assertEquals(List.of("BILLING_API_KEY"), response.requirements().secrets());
        List<String> kinds = response.requirements().items().stream()
            .map(GenerateGraphResponse.RequirementItem::kind).toList();
        assertTrue(kinds.contains("secret"), kinds.toString());
        assertTrue(kinds.contains("channel"), kinds.toString());
        assertTrue(kinds.contains("approval-policy"), kinds.toString());
        assertTrue(response.notes().contains("Store your billing API key as a secret."), response.notes().toString());
        assertTrue(response.notes().stream().anyMatch(n -> n.startsWith("Added an approval step")), response.notes().toString());
    }

    @Test
    void flagOffReturnsTheModelGraphUntouched() {
        GenerateGraphResponse response = service(false).generate("remind people", ReasoningDepth.FAST, null);
        assertEquals(4, response.graph().nodes().size());
        assertTrue(response.insertedApprovals().isEmpty());
        assertFalse(WorkflowSendApprovalGuard.ungatedSends(response.graph()).isEmpty());
        assertNotNull(response.explanation());
    }

    @Test
    void connectedWorkspaceGetsNoChannelOrPolicyHint() {
        when(whatsApp.isAnyProviderConnected()).thenReturn(true);
        when(approvalPolicy.requiresApproval("workflow.node.approval")).thenReturn(true);
        List<String> kinds = service(true).generate("x", ReasoningDepth.FAST, null).requirements().items().stream()
            .map(GenerateGraphResponse.RequirementItem::kind).toList();
        assertEquals(List.of("secret"), kinds);
    }

    @Test
    void legacyTwoArgOverloadStillWorksAndEditModeIsReported() {
        assertEquals("create", service(true).generate("x", ReasoningDepth.FAST).mode());

        WorkflowGraphDto current = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("t", "trigger", Map.of("x", 80, "y", 160), Map.of("label", "Start", "config", Map.of()))),
            List.<WorkflowEdgeDto>of());
        assertEquals("edit", service(true).generate("add a reminder", ReasoningDepth.FAST, current).mode());
        assertEquals("create", service(true).generate("x", ReasoningDepth.FAST, new WorkflowGraphDto(List.of(), List.of())).mode());
    }

    @Test
    void anOversizedCurrentGraphIsRejectedBeforeAnyModelCall() {
        String big = "x".repeat(WorkflowGeneratorSupport.MAX_EDIT_GRAPH_CHARS + 1);
        WorkflowGraphDto current = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("t", "trigger", Map.of(), Map.of("label", big, "config", Map.of()))), List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service(true).generate("change it", ReasoningDepth.FAST, current));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void briefReferenceModeIsNeverGuardedBecauseTheTrustLadderOwnsItsApprovals() {
        // TrustGraphMaterializer adds (and later strips) eaMaterialised approval nodes at apply time; a
        // permanent auto-approval node here would double-gate and could never be removed.
        RefGraphResponse response = service(true).generateWithRefs("remind", ReasoningDepth.FAST, List.of(), List.of());
        assertEquals(4, response.graph().nodes().size());
        assertTrue(response.graph().nodes().stream()
            .noneMatch(n -> n.id().startsWith(WorkflowSendApprovalGuard.AUTO_APPROVAL_ID_PREFIX)));
        assertTrue(response.notes().isEmpty());
        assertEquals(List.of("BILLING_API_KEY"), response.secretsNeeded());
        assertNull(response.graph().nodes().stream().filter(n -> "schedule".equals(n.type())).findFirst().orElse(null));
    }

    @Test
    void theEditCapFollowsTheOutputBudget() {
        // 1000 tokens of output cannot carry a 3000-character graph back, so it is refused before any model call.
        WorkflowGraphDto current = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("t", "trigger", Map.of(), Map.of("label", "x".repeat(3000), "config", Map.of()))),
            List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service(true, 1000).generate("change it", ReasoningDepth.FAST, current));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertEquals("edit", service(true, 4096).generate("change it", ReasoningDepth.FAST, current).mode());
    }

    @Test
    void anEditNeverSendsALiteralCredentialToTheModelAndPutsItBackAfterwards() {
        String key = "sk-live-abcdefghijklmnop1234";
        WorkflowGraphDto current = new WorkflowGraphDto(List.of(
            new WorkflowNodeDto("t", "trigger", Map.of("x", 80, "y", 160),
                Map.of("label", "Start", "config", Map.of("channel", "WEBHOOK"))),
            new WorkflowNodeDto("call", "http", Map.of("x", 340, "y", 160), Map.of("label", "Call", "config", Map.of(
                "method", "GET", "url", "https://api.example.com/x",
                "headers", List.of(Map.of("name", "Authorization", "value", "Bearer " + key)))))),
            List.of(new WorkflowEdgeDto("e1", "t", "call", null, null)));
        when(chatClient.prompt().system(any(String.class)).user(any(String.class)).call().content()).thenReturn("""
            {"graph": {"nodes": [
                {"id": "t", "type": "trigger", "position": {"x": 80, "y": 160}, "data": {"label": "Start", "config": {"channel": "WEBHOOK"}}},
                {"id": "call", "type": "http", "position": {"x": 340, "y": 160}, "data": {"label": "Call renamed",
                  "config": {"method": "GET", "url": "https://api.example.com/x",
                    "headers": [{"name": "Authorization", "value": "Bearer [[REDACTED_1]]"}]}}}
              ], "edges": [{"id": "e1", "source": "t", "target": "call", "sourceHandle": null, "targetHandle": null}]},
             "notes": []}
            """);

        GenerateGraphResponse response = service(true).generate("rename the call step", ReasoningDepth.CAREFUL, current);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt().system("probe"), org.mockito.Mockito.atLeastOnce()).user(user.capture());
        String sent = user.getValue();
        assertTrue(sent.contains("CURRENT GRAPH"), sent);
        assertFalse(sent.contains(key), "the literal key must not reach the provider");
        assertTrue(sent.contains("Bearer [[REDACTED_1]]"), sent);

        Object headers = node(response.graph(), "call").config().get("headers");
        assertEquals(List.of(Map.of("name", "Authorization", "value", "Bearer " + key)), headers);
        assertEquals("Call renamed", node(response.graph(), "call").label());
        // An edit is judged against a change request, not a full description: the revision passes are skipped.
        org.mockito.Mockito.verifyNoInteractions(consistencyChecker, reflexion);
    }
}
