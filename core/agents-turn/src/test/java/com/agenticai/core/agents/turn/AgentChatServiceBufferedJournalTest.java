package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnEventType;
import com.agenticai.core.agents.turnjournal.TurnHashChain;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.agents.turnjournal.TurnSummary;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The turn journal records the buffered reasoning depths (CAREFUL / RIGOROUS / PLANNED), not only the streamed one,
 * with the tool calls the way the guard chain ended them, and the turn is found by the id the entry gave it or by its
 * session. REAL {@link AgentChatService} and REAL {@link GuardedToolCallback}s; the model client is a stub that, like
 * the real tool loop, calls the tools it was handed.
 */
class AgentChatServiceBufferedJournalTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private AgentToolPort agentToolProvider;
    private ChatClient chatClient;
    private Agent agent;
    private InMemoryTurnJournal journal;
    private ApprovalPolicyService policy;
    private HumanApprovalGateService gate;

    @BeforeEach
    void setUp() {
        llmGatewayService = mock(LlmGatewayService.class);
        sessionMemoryService = mock(SessionMemoryService.class);
        agentToolProvider = mock(AgentToolPort.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());
        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getModel()).thenReturn("gpt-test");
        journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, true);
        policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.send_invoice")).thenReturn(true);
        gate = mock(HumanApprovalGateService.class);
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
                when(gate.recordToolCallWithOrigin(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
    }

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
    }

    private static final class Raw implements ToolCallback {
        final String name;
        final String output;

        Raw(String name, String output) {
            this.name = name;
            this.output = output;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return output;
        }
    }

    /** Two real guarded tools: one runs, one is held for a human. They share the turn's approval state. */
    private List<ToolCallback> guardedTools(TurnApprovalState turnState) {
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(1));
        // The turn's own approval state, as the real tool provider hands it to the guards it builds.
        TurnApprovalState state = turnState != null ? turnState : new TurnApprovalState("c1:mem");
        AtomicInteger steps = new AtomicInteger();
        return List.of(
                new GuardedToolCallback(
                                new Raw("lookup", "{\"found\":true}"), steps, 10, guardrails, policy, gate, AGENT_ID, "sess-j")
                        .withTurnApproval(state),
                new GuardedToolCallback(
                                new Raw("send_invoice", "{\"sent\":true}"), steps, 10, guardrails, policy, gate, AGENT_ID, "sess-j")
                        .withTurnApproval(state));
    }

    /** The model stub: the final call hands the tools to {@code onTools}, then answers "final answer". */
    @SuppressWarnings("unchecked")
    private void modelCallsTools() {
        AtomicReference<List<ToolCallback>> handed = new AtomicReference<>(List.of());
        ChatClient.ChatClientRequestSpec base =
                chatClient
                        .prompt()
                        .system(any(String.class))
                        .messages(any(List.class))
                        .user(any(Consumer.class));
        ChatClient.ChatClientRequestSpec afterTools = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(afterTools.toolContext(any(Map.class)).advisors(any(Consumer.class)).call().content())
                .thenAnswer(
                        inv -> {
                            handed.get().forEach(tool -> tool.call("{}"));
                            return "final answer";
                        });
        when(base.toolCallbacks(any(List.class)))
                .thenAnswer(
                        inv -> {
                            handed.set(inv.getArgument(0));
                            return afterTools;
                        });
    }

    private AgentChatService service(ReasoningDepth depth) {
        when(agent.getReasoningDepth()).thenReturn(depth);
        when(agentToolProvider.resolveToolsFor(any(TurnContext.class), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> guardedTools(((TurnContext) inv.getArgument(0)).approval()));
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");
        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());
        AnswerConsistencyChecker consistencyChecker = mock(AnswerConsistencyChecker.class);
        when(consistencyChecker.checkAndRevise(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        ReflexionService reflexionService = mock(ReflexionService.class);
        when(reflexionService.critiqueAndMaybeRevise(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        QueryComplexityClassifier classifier = mock(QueryComplexityClassifier.class);
        ChatTurnCancelService cancel = mock(ChatTurnCancelService.class);
        when(cancel.watchForCancellation(any(), any())).thenReturn(Flux.never());
        return AgentChatServiceTestFactory.create(
                agentService,
                credentialResolver,
                llmGatewayService,
                sessionMemoryService,
                mock(ProviderErrorTranslator.class),
                agentToolProvider,
                providerOf(mock(AgentMemoryService.class)),
                providerOf((TenantMemoryService) null),
                consistencyChecker,
                reflexionService,
                mock(TaskPlanningService.class),
                mock(ToolCallLogService.class),
                mock(TenantLimits.class),
                mock(FreeTierChatClientService.class),
                mock(FileStorageService.class),
                mock(TenantStatusGuard.class),
                mock(LlmQualityJudgeService.class),
                providerOf((PlatformIssueService) null),
                featureFlagService,
                mock(LlmResponseCacheService.class),
                providerOf((SemanticResponseCacheService) null),
                classifier,
                providerOf((RlPolicyPort) null),
                providerOf((RlTurnRecorder) null),
                providerOf((ModerationService) null),
                providerOf((ContentGuardrailEngine) null),
                providerOf(new GuardrailViolationLogger(mock(AuditLogService.class))),
                new AgentGuardrailService(
                        mock(TenantLimits.class), mock(AgentRepository.class), mock(ResourceAccessService.class)),
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
                mock(LoadTestTenantAllowlist.class),
                cancel,
                java.time.Duration.ofSeconds(90),
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(600));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private List<TurnEvent> runAndRead(ReasoningDepth depth) {
        modelCallsTools();
        AgentChatService service = service(depth);
        ChatTurnRequest request =
                ChatTurnRequest.builder(AGENT_ID, "sess-j", "subject-j", "look it up then invoice them").build();
        TurnContext context = TurnContext.forEntry(null, request);

        List<String> emitted = service.chat(context, request).collectList().block();

        assertEquals(List.of("final answer"), emitted);
        // The id the entry gave the turn (the response header names this one) is the id of the journal record.
        List<TurnEvent> events = journal.read(AGENT_ID, context.turnId());
        assertFalse(events.isEmpty(), "no journal record under the turn id the entry created");
        assertTrue(TurnHashChain.verify(events).intact());
        // ...and the same turn is found by its session.
        List<TurnSummary> bySession = journal.listBySession(AGENT_ID, "sess-j", 10);
        assertEquals(List.of(context.turnId()), bySession.stream().map(TurnSummary::turnId).toList());
        assertEquals("completed", bySession.get(0).status());
        return events;
    }

    private static void assertToolAndApprovalEvents(List<TurnEvent> events) {
        List<String> toolSummaries =
                events.stream().filter(e -> e.type() == TurnEventType.TOOL_CALL).map(TurnEvent::summary).toList();
        assertEquals(2, toolSummaries.size(), events.toString());
        assertTrue(toolSummaries.get(0).contains("tool=lookup") && toolSummaries.get(0).contains("status=ok"), toolSummaries.toString());
        assertTrue(
                toolSummaries.get(1).contains("tool=send_invoice") && toolSummaries.get(1).contains("status=parked"),
                "a call held for a human is not an ok call: " + toolSummaries);
        List<TurnEvent> approvals = events.stream().filter(e -> e.type() == TurnEventType.APPROVAL).toList();
        assertEquals(1, approvals.size(), events.toString());
        assertTrue(approvals.get(0).summary().contains("decision=pending"), approvals.get(0).summary());
        assertTrue(approvals.get(0).summary().contains("request="), approvals.get(0).summary());
    }

    @Test
    void aCarefulTurnIsJournaledWithItsToolCallsAnApprovalAndACommit() {
        List<TurnEvent> events = runAndRead(ReasoningDepth.CAREFUL);

        assertEquals(TurnEventType.INTAKE, events.get(0).type());
        assertEquals(TurnEventType.COMMIT, events.get(events.size() - 1).type());
        assertTrue(events.stream().anyMatch(e -> e.type() == TurnEventType.MODEL_CALL), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type() == TurnEventType.VERIFY), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type() == TurnEventType.GUARD), events.toString());
        assertToolAndApprovalEvents(events);
        assertTrue(events.get(events.size() - 1).summary().contains("status=completed"));
        assertFalse(events.toString().contains("look it up"), "the prompt is hashed, never stored");
    }

    @Test
    void aRigorousTurnAlsoJournalsTheReflexionCheck() {
        List<TurnEvent> events = runAndRead(ReasoningDepth.RIGOROUS);

        assertTrue(events.stream().anyMatch(e -> e.summary().contains("check=reflexion")), events.toString());
        assertToolAndApprovalEvents(events);
    }

    @Test
    void aPlannedTurnIsJournaledToo() {
        List<TurnEvent> events = runAndRead(ReasoningDepth.PLANNED);

        assertTrue(events.stream().anyMatch(e -> e.summary().contains("guard=plan")), events.toString());
        assertToolAndApprovalEvents(events);
        assertEquals(TurnEventType.COMMIT, events.get(events.size() - 1).type());
    }

    @Test
    void nothingIsJournaledWhileTheJournalIsOff() {
        TurnJournalGateway.install(journal, false);
        modelCallsTools();
        AgentChatService service = service(ReasoningDepth.CAREFUL);
        ChatTurnRequest request = ChatTurnRequest.builder(AGENT_ID, "sess-off", "subject", "hi").build();
        TurnContext context = TurnContext.forEntry(null, request);

        assertEquals(List.of("final answer"), service.chat(context, request).collectList().block());

        assertTrue(journal.read(AGENT_ID, context.turnId()).isEmpty());
        assertTrue(journal.listBySession(AGENT_ID, "sess-off", 10).isEmpty());
    }
}
