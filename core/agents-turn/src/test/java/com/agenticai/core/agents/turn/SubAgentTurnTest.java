package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.crew.SpecialistCandidate;
import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.agents.hooks.HookRun;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnEventType;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import com.agenticai.core.agents.turnjournal.TurnSummary;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.impl.JailbreakDetectorGuardrail;
import com.agenticai.core.guardrails.impl.ProfanityGuardrail;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * A delegated call ({@link ChatDelegateCall}) and a specialist handoff ({@link ChatSpecialistHandoff}) run the same
 * stages a buffered turn has: screening of the question, the delegate's own content guardrails, model-level hooks, the
 * turn journal and the continuation policy. They used to be a bare model call, so asking the main agent to delegate
 * skipped every one of them. REAL delegate classes over a REAL {@link SubAgentTurn}, {@link BufferedTurnCalls},
 * {@link ChatGuardrailGate} and {@link ContentGuardrailEngine}; the model client, the agent store and the hook
 * runtime are the doubles.
 */
class SubAgentTurnTest {

    private static final UUID TARGET = UUID.randomUUID();
    private static final String BAD_WORDS = "This is damn frustrating!";

    private ChatClient chatClient;
    private Agent agent;
    private InMemoryTurnJournal journal;
    private LlmQualityJudgeService judge;
    private AgentToolPort toolPort;
    private ChatTurnExecutor turns;
    private volatile AgentHookRuntime hookRuntime;
    private ChatDelegateCall delegate;
    private ChatSpecialistHandoff specialist;
    private SpecialistHandoffService handoffService;
    private final List<ToolCallback> delegateTools = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, true);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(TARGET);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getModel()).thenReturn("gpt-test");
        when(agent.getSystemPrompt()).thenReturn("You are a collaborator.");
        when(agent.getEnabledGuardrails()).thenReturn(Set.of());

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-test");
        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        LlmGatewayService gateway = mock(LlmGatewayService.class);
        when(gateway.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(gateway.resolveClient(any(), any(com.agenticai.core.chat.gateway.GatewayCallSpec.class))).thenReturn(chatClient);
        toolPort = mock(AgentToolPort.class);
        when(toolPort.resolveToolsForDelegatedCall(any(), any(), any(), any())).thenAnswer(i -> List.copyOf(delegateTools));
        when(toolPort.resolveToolsForSpecialistHandoff(any(), any(), any(), any(), any()))
                .thenAnswer(i -> List.copyOf(delegateTools));
        judge = mock(LlmQualityJudgeService.class);
        ProviderErrorTranslator translator = mock(ProviderErrorTranslator.class);

        ChatGuardrailGate guardrails =
                new ChatGuardrailGate(
                        providerOf((ModerationService) null),
                        providerOf(realEngine()),
                        providerOf(new GuardrailViolationLogger(mock(AuditLogService.class))),
                        new AgentGuardrailService(
                                mock(TenantLimits.class), mock(AgentRepository.class), mock(ResourceAccessService.class)));
        turns =
                new ChatTurnExecutor(
                        credentials,
                        gateway,
                        translator,
                        mock(SessionMemoryService.class),
                        mock(ToolCallLogService.class),
                        judge,
                        guardrails,
                        mock(ChatIssueRecorder.class),
                        mock(AgentMessageEvents.class),
                        Duration.ofSeconds(30));
        SubAgentTurn subTurn =
                new SubAgentTurn(
                        new BufferedTurnCalls(turns, translator, mock(ToolCallLogService.class)),
                        guardrails,
                        () -> hookRuntime,
                        tenantId -> null,
                        judge,
                        translator);
        ChatPromptDirectives.DirectiveGates gates =
                new ChatPromptDirectives.DirectiveGates(() -> true, () -> false, () -> true, () -> false, () -> false);
        ResourceAccessService access = mock(ResourceAccessService.class);
        delegate =
                new ChatDelegateCall(
                        agentService, access, credentials, gateway, toolPort, judge, translator, turns, tenant -> null,
                        gates, subTurn);
        specialist =
                new ChatSpecialistHandoff(
                        agentService, access, credentials, gateway, toolPort, judge, translator, turns, tenant -> null,
                        gates, subTurn);
        handoffService = mock(SpecialistHandoffService.class);
        specialist.setHandoffService(handoffService);
        when(handoffService.findSpecialist(any()))
                .thenReturn(Optional.of(new SpecialistCandidate(TARGET, "Billing", "billing")));
    }

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
    }

    @SuppressWarnings("unchecked")
    private void modelAnswers(String answer) {
        when(chatClient
                        .prompt()
                        .system(any(String.class))
                        .messages(any(List.class))
                        .user(any(Consumer.class))
                        .advisors(any(Consumer.class))
                        .call()
                        .content())
                .thenReturn(answer);
        // Stubbing a deep-stub chain records prompt() invocations; clear them so verify(never()) means something.
        org.mockito.Mockito.clearInvocations(chatClient);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static ContentGuardrailEngine realEngine() {
        List<ContentGuardrail> guardrails = List.of(new ProfanityGuardrail(), new JailbreakDetectorGuardrail());
        return new ContentGuardrailEngine(
                new ObjectProvider<>() {
                    @Override
                    public List<ContentGuardrail> getIfAvailable() {
                        return new ArrayList<>(guardrails);
                    }
                });
    }

    private List<TurnEvent> eventsOf(String session) {
        List<TurnSummary> found = journal.listBySession(TARGET, session, 5);
        assertEquals(1, found.size(), "the sub-turn has its own journal record, found by its session");
        return journal.read(TARGET, found.get(0).turnId());
    }

    @Test
    void aDelegatedCallIsJournaledIntakeToCommitAndTheAnswerIsReturned() {
        modelAnswers("the collaborator's answer");

        String result = delegate.delegateCall(TARGET, "what is 2+2", null, "delegated:one");

        assertEquals("the collaborator's answer", result);
        List<TurnEvent> events = eventsOf("delegated:one");
        assertEquals(TurnEventType.INTAKE, events.get(0).type());
        assertTrue(events.stream().anyMatch(e -> e.summary().contains("guard=sub-agent outcome=delegate")), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.type() == TurnEventType.MODEL_CALL), events.toString());
        TurnEvent last = events.get(events.size() - 1);
        assertEquals(TurnEventType.COMMIT, last.type());
        assertTrue(last.summary().contains("status=completed"), last.summary());
        verify(judge).sampleAsync(any(), any(), any(), any(), any());
    }

    @Test
    void theDelegatesOwnInboundGuardrailsRefuseTheQuestionBeforeTheModelIsCalled() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        modelAnswers("should never be used");

        String result = delegate.delegateCall(TARGET, BAD_WORDS, null, "delegated:in");

        assertFalse(result.isBlank());
        assertFalse(result.contains("should never be used"));
        verify(chatClient, never()).prompt();
        List<TurnEvent> events = eventsOf("delegated:in");
        assertTrue(events.stream().anyMatch(e -> e.summary().contains("guard=inbound outcome=refused")), events.toString());
        assertTrue(events.get(events.size() - 1).summary().contains("status=refused"));
        verify(judge, never()).sampleAsync(any(), any(), any(), any(), any());
    }

    @Test
    void theDelegatesOutboundGuardrailsWithholdAViolatingAnswer() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        modelAnswers(BAD_WORDS);

        String result = delegate.delegateCall(TARGET, "how do you feel", null, "delegated:out");

        assertFalse(result.contains("damn"), "the violating answer must not reach the calling agent: " + result);
        assertTrue(result.contains("withheld"), result);
        List<TurnEvent> events = eventsOf("delegated:out");
        assertTrue(events.stream().anyMatch(e -> e.summary().contains("guard=outbound-enforced outcome=withheld")), events.toString());
    }

    @Test
    void aBeforeModelHookCanRefuseTheDelegatedQuestion() {
        AgentHookRuntime runtime = mock(AgentHookRuntime.class);
        HookRun run = mock(HookRun.class);
        when(runtime.beginRun(any(), any(), any())).thenReturn(run);
        when(run.beforeModel("send the money")).thenReturn("a workspace hook stopped this request");
        when(run.refusal(anyString())).thenAnswer(i -> Flux.just((String) i.getArgument(0)));
        hookRuntime = runtime;
        modelAnswers("should never be used");

        String result = delegate.delegateCall(TARGET, "send the money", null, "delegated:hook");

        assertEquals("a workspace hook stopped this request", result);
        verify(chatClient, never()).prompt();
        assertTrue(eventsOf("delegated:hook").stream().anyMatch(e -> e.summary().contains("guard=hook-before-model")));
    }

    @Test
    void anAfterModelHookSeesTheDelegatedAnswerAndItsResultIsWhatTheCallerGets() {
        AgentHookRuntime runtime = mock(AgentHookRuntime.class);
        HookRun run = mock(HookRun.class);
        when(runtime.beginRun(any(), any(), any())).thenReturn(run);
        when(run.wrap(any()))
                .thenAnswer(i -> Flux.just("[redacted by hook]"));
        hookRuntime = runtime;
        modelAnswers("the secret is 1234");

        String result = delegate.delegateCall(TARGET, "tell me", null, "delegated:after");

        assertEquals("[redacted by hook]", result);
    }

    @Test
    void aReplyThatStopsAtAnAnnouncedStepIsContinuedLikeOnAnyOtherTurn() {
        com.agenticai.core.agents.turn.ContinuationProperties properties = new ContinuationProperties();
        properties.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS);
        turns.continuation = new TurnContinuation(properties);
        turns.maxContinuations = 2;
        ToolCallback tool = new FixedTool("lookup", "{}");
        delegateTools.add(tool);
        stubToolTurn("Let me create the pom.xml:", "Done. The pom.xml is created.");

        String result = delegate.delegateCall(TARGET, "set up the build", null, "delegated:cont");

        assertTrue(result.startsWith("Let me create the pom.xml:"), result);
        assertTrue(result.contains("Done. The pom.xml is created."), result);
    }

    @Test
    void aFrontierModelsAnnouncedStepIsAnAnswerAndALocalServersIsContinued() {
        ContinuationProperties properties = new ContinuationProperties(); // wording check: listed providers only
        turns.continuation = new TurnContinuation(properties);
        turns.maxContinuations = 2;
        delegateTools.add(new FixedTool("lookup", "{}"));
        stubToolTurn("Let me create the pom.xml:", "Done. The pom.xml is created.");

        assertEquals("Let me create the pom.xml:", delegate.delegateCall(TARGET, "set up the build", null, "delegated:f1"));

        properties.setHeuristicProviders(Set.of(ProviderName.OPENAI));
        stubToolTurn("Let me create the pom.xml:", "Done. The pom.xml is created.");
        String continued = delegate.delegateCall(TARGET, "set up the build", null, "delegated:f2");
        assertTrue(continued.startsWith("Let me create the pom.xml:"), continued);
        assertTrue(continued.contains("Done. The pom.xml is created."), continued);
    }

    @Test
    void aSpecialistHandoffIsScreenedAndJournaledToo() {
        when(agent.getEnabledGuardrails()).thenReturn(Set.of("profanity"));
        modelAnswers("should never be used");
        HandoffChain chain = HandoffChain.root("parent-session", UUID.randomUUID());

        String refused = specialist.delegateToSpecialist(chain, "billing", BAD_WORDS, null);

        assertFalse(refused.isBlank());
        verify(chatClient, never()).prompt();
        assertEquals(List.of("refused"), committedStatuses());

        when(agent.getEnabledGuardrails()).thenReturn(Set.of());
        modelAnswers("the specialist's answer");
        assertEquals("the specialist's answer", specialist.delegateToSpecialist(chain, "billing", "invoice status", null));
        verify(handoffService, org.mockito.Mockito.times(2)).recordHandoffOutcome(any(), eq(com.agenticai.core.agents.crew.HandoffStatus.SUCCESS), any());
    }

    @Test
    void aSubTurnStartedByAToolCallNamesTheJournaledTurnThatCalledIt() {
        modelAnswers("child answer");
        TurnRecorder parent = TurnRecorder.start(journal, UUID.randomUUID(), UUID.randomUUID(), "parent-session");
        ToolCallback delegating =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder().name("ask_agent_x").description("d").inputSchema("{}").build();
                    }

                    @Override
                    public String call(String toolInput) {
                        return delegate.delegateCall(TARGET, "question", null, "delegated:linked");
                    }
                };

        parent.wrapTools(List.of(delegating)).get(0).call("{}");

        TurnEvent intake = eventsOf("delegated:linked").get(0);
        assertTrue(intake.summary().contains("parent=" + parent.turnId()), intake.summary());
    }

    @Test
    void nothingIsJournaledWhileTheJournalIsOff() {
        TurnJournalGateway.install(journal, false);
        modelAnswers("answer");

        assertEquals("answer", delegate.delegateCall(TARGET, "q", null, "delegated:off"));

        assertTrue(journal.listBySession(TARGET, "delegated:off", 5).isEmpty());
    }

    /** The journaled turns, whatever session they ran in (the specialist's session id is generated). */
    private List<String> committedStatuses() {
        return journal.turnIds().stream()
                .map(id -> journal.read(TARGET, id))
                .filter(events -> !events.isEmpty())
                .map(
                        events ->
                                TurnSummary.statusOf(
                                        events.stream()
                                                .filter(e -> e.type() == TurnEventType.COMMIT)
                                                .map(TurnEvent::summary)
                                                .findFirst()
                                                .orElse(null)))
                .toList();
    }

    /**
     * A model that answers the next scripted reply on each call, whatever the fluent request looks like (the first
     * pass and a continuation pass build different requests).
     */
    private void stubToolTurn(String... replies) {
        ChatClient.ChatClientRequestSpec spec =
                mock(ChatClient.ChatClientRequestSpec.class, org.mockito.Mockito.RETURNS_SELF);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        when(call.content()).thenAnswer(i -> replies[Math.min(next.getAndIncrement(), replies.length - 1)]);
        when(spec.call()).thenReturn(call);
        org.mockito.Mockito.doReturn(spec).when(chatClient).prompt();
    }

    private record FixedTool(String name, String output) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return output;
        }
    }

}
