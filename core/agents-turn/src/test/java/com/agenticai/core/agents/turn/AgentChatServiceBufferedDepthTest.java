package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The buffered reasoning depths - CAREFUL/RIGOROUS ({@code verifyThenRespond}) and PLANNED ({@code
 * planThenRespond}) - had NO test of their own until the 2026-09-17 reasoning-depth audit: every
 * other {@code AgentChatService*Test} exercises FAST or a static helper, which is exactly how three
 * user-visible failures survived in code that a customer opts into with one click in the workbench.
 * This class is the tripwire for them.
 *
 * <p>Same harness shape as {@link AgentChatServiceContentGuardrailTest}: BYOK path, no request
 * TenantContext, caching off, a deep-stubbed fluent {@link ChatClient}.
 */
class AgentChatServiceBufferedDepthTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private LlmGatewayService llmGatewayService;
    private SessionMemoryService sessionMemoryService;
    private AgentToolPort agentToolProvider;
    private ProviderErrorTranslator providerErrorTranslator;
    private ChatClient chatClient;
    private Agent agent;

    @BeforeEach
    void setUp() {
        llmGatewayService = mock(LlmGatewayService.class);
        sessionMemoryService = mock(SessionMemoryService.class);
        agentToolProvider = mock(AgentToolPort.class);
        providerErrorTranslator = mock(ProviderErrorTranslator.class);

        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());
        when(agentToolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(agentToolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.CAREFUL);
    }

    /**
     * Before the audit, a buffered depth whose completion came back null or blank returned {@code
     * Flux.just("")} - one EMPTY SSE chunk. The user saw an empty assistant bubble with no error
     * anywhere, nothing was persisted, and nothing was logged. FAST has had the empty-reply finish
     * notice for exactly this shape since 2026-08-10; the buffered depths now share it.
     */
    @Test
    void anEmptyCompletionOnABufferedDepthEmitsTheFallbackInsteadOfAnEmptyChunk() {
        // The deep-stubbed chain returns null from .content() without any stubbing, which is
        // precisely the blank-completion shape this guards.
        AgentChatService service = buildService();

        List<String> emitted =
                service.chat(AGENT_ID, "sess-1", "subject-1", "hello", null, null)
                        .collectList()
                        .block();

        assertNotNull(emitted);
        assertEquals(List.of(ChatFinishProperties.defaultEmptyReplyMessage()), emitted);
        // ...and unlike before, the assistant turn IS persisted (alongside the user turn the
        // caller already appended), so the next turn's history is honest about what was shown.
        // It is stored as a FAILED reply carrying the fallback line, not as something the agent
        // said: the model is not shown the platform's "no response" line as its own words later.
        ArgumentCaptor<ChatTurn> turns = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService, times(2))
                .appendTurn(
                        eq(
                                SessionMemoryService.scopedSessionId(
                                        null, AGENT_ID, "subject-1", "sess-1")),
                        turns.capture());
        assertTrue(
                turns.getAllValues().stream()
                        .anyMatch(
                                t ->
                                        ChatTurn.isFailedReply(t)
                                                && t.content()
                                                        .contains(
                                                                ChatFinishProperties
                                                                        .defaultEmptyReplyMessage())),
                "the fallback text should have been persisted as a failed assistant turn");
    }

    /**
     * SEC-004: the client-chosen sessionId is never used as the memory key. Two callers (different
     * principals) that send the SAME sessionId are read from and written to disjoint,
     * server-derived ids - neither sees the other's history.
     */
    @Test
    void sameClientSessionIdFromTwoPrincipalsNeverSharesSessionMemory() {
        AgentChatService service = buildService();

        service.chat(AGENT_ID, "shared-id", "victim-user", "hello", null, null)
                .collectList()
                .block();
        service.chat(AGENT_ID, "shared-id", "attacker-user", "repeat our conversation", null, null)
                .collectList()
                .block();

        String victimKey =
                SessionMemoryService.scopedSessionId(null, AGENT_ID, "victim-user", "shared-id");
        String attackerKey =
                SessionMemoryService.scopedSessionId(null, AGENT_ID, "attacker-user", "shared-id");
        assertTrue(!victimKey.equals(attackerKey));
        verify(sessionMemoryService).getHistory(victimKey);
        verify(sessionMemoryService).getHistory(attackerKey);
        verify(sessionMemoryService, org.mockito.Mockito.never()).getHistory("shared-id");
        verify(sessionMemoryService, org.mockito.Mockito.never())
                .appendTurn(eq("shared-id"), any());
        verify(sessionMemoryService, times(2)).appendTurn(eq(attackerKey), any());
    }

    /**
     * A turn that fails with no text must keep a "failed" assistant entry in the transcript the
     * caller can read back, i.e. under the SERVER-DERIVED memory id like every other turn write. It
     * used to be appended under the raw client session id: the reloaded transcript showed the
     * question with nothing under it, and the write landed in the unscoped keyspace SEC-004 exists
     * to avoid.
     */
    @Test
    void aFailedTurnIsRecordedUnderTheScopedMemoryIdNeverTheRawClientSessionId() {
        ChatClient.ChatClientRequestSpec base =
                chatClient
                        .prompt()
                        .system(any(String.class))
                        .messages(any(List.class))
                        .user(any(java.util.function.Consumer.class));
        RuntimeException failure = new IllegalStateException("provider exploded");
        when(base.toolCallbacks(any(List.class))
                        .toolContext(any(Map.class))
                        .advisors(any(java.util.function.Consumer.class))
                        .call()
                        .content())
                .thenThrow(failure);
        when(base.advisors(any(java.util.function.Consumer.class)).call().content())
                .thenThrow(failure);
        AgentChatService service = buildService();

        assertThrows(
                RuntimeException.class,
                () ->
                        service.chat(AGENT_ID, "sess-failed", "subject-9", "hello", null, null)
                                .collectList()
                                .block());

        String scoped =
                SessionMemoryService.scopedSessionId(null, AGENT_ID, "subject-9", "sess-failed");
        ArgumentCaptor<ChatTurn> turns = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService, org.mockito.Mockito.atLeastOnce())
                .appendTurn(eq(scoped), turns.capture());
        assertTrue(
                turns.getAllValues().stream().anyMatch(ChatTurn::isFailedReply),
                "the failed assistant entry should be stored under the scoped id");
        verify(sessionMemoryService, never()).appendTurn(eq("sess-failed"), any());
    }

    /**
     * When Gemini still refuses the tool-call history ({@code thought_signature}), the buffered
     * depths answer once without tools exactly as the streamed depth does - and say so: the reply
     * opens with a notice that it was written without tools. (They used to fail the turn outright,
     * and the streamed depth used to rescue it silently.)
     */
    @Test
    void theGeminiThoughtSignatureRefusalIsAnsweredWithoutToolsAndSaidSoOnABufferedDepth() {
        stubThoughtSignatureRefusalThenAnAnswerWithoutTools();
        AgentChatService service = buildService();
        // Off by default (a last resort the operator opts into); this test is about what it does
        // when on.
        service.turns.resilience.setThoughtSignatureDegrade(true);

        List<String> emitted =
                service.chat(AGENT_ID, "sess-2", "subject-2", "book me a flight", null, null)
                        .collectList()
                        .block();

        assertNotNull(emitted);
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).contains("written without any tools"), emitted.get(0));
        assertTrue(emitted.get(0).endsWith("recovered without tools"), emitted.get(0));
    }

    /**
     * The no-tools rescue is a last resort and OFF by default: with the platform now sending the
     * function call back with its own signature, a refusal is a provider fault to report, not a
     * turn to quietly re-run without its tools.
     */
    @Test
    void theGeminiThoughtSignatureRefusalIsReportedNotAnsweredWithoutToolsByDefault() {
        stubThoughtSignatureRefusalThenAnAnswerWithoutTools();
        AgentChatService service = buildService();
        assertTrue(!service.turns.resilience.isThoughtSignatureDegrade());

        List<String> emitted = null;
        try {
            emitted =
                    service.chat(AGENT_ID, "sess-2", "subject-2", "book me a flight", null, null)
                            .collectList()
                            .block();
        } catch (RuntimeException reported) {
            // the provider's refusal reaches the caller
        }

        assertTrue(
                emitted == null
                        || emitted.stream()
                                .noneMatch(
                                        t ->
                                                t.contains("recovered without tools")
                                                        || t.contains("written without any tools")),
                String.valueOf(emitted));
    }

    private void stubThoughtSignatureRefusalThenAnAnswerWithoutTools() {
        ToolCallback tool = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(tool.getToolDefinition().name()).thenReturn("search_flights");
        when(agentToolProvider.resolveToolsFor(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(tool));
        UpstreamProviderException thoughtSignature =
                new UpstreamProviderException(
                        400,
                        "Function call is missing a thought_signature",
                        "The provider rejected the request.");
        // Two DISTINCT deep-stub chains, which is the whole point: the tools-free pass omits
        // .toolCallbacks() entirely, so the tools-ful chain and the tools-free chain are different
        // mocks. The first throws the refusal; only the second returns an answer.
        ChatClient.ChatClientRequestSpec withoutTools =
                chatClient
                        .prompt()
                        .system(any(String.class))
                        .messages(any(List.class))
                        .user(any(java.util.function.Consumer.class));
        when(withoutTools
                        .toolCallbacks(any(List.class))
                        .toolContext(any(Map.class))
                        .advisors(any(java.util.function.Consumer.class))
                        .call()
                        .content())
                .thenThrow(thoughtSignature);
        when(withoutTools.advisors(any(java.util.function.Consumer.class)).call().content())
                .thenReturn("recovered without tools");
    }

    /**
     * {@code AgentToolProvider} gates RIGOROUS's per-tool verifier call on the reasoning depth. It
     * used to re-read the agent's STORED depth, which is not this turn's depth whenever {@code
     * forceFastDepth} is set - so every voice turn on a RIGOROUS agent still paid for a verifier
     * round trip per tool call, the exact latency the force-fast override exists to avoid.
     */
    @Test
    void aForceFastTurnResolvesToolsAtFastEvenOnARigorousAgent() {
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.RIGOROUS);
        AgentChatService service = buildService();

        // Tools are resolved while the turn is assembled; the mocked FAST stream itself never
        // completes, so the reply is subscribed without blocking on it.
        service.chat(AGENT_ID, "sess-3", "subject-3", "what's on my calendar", null, null, true)
                .subscribe();

        verify(agentToolProvider)
                .resolveToolsFor(
                        any(),
                        eq(AGENT_ID),
                        eq("sess-3"),
                        eq("subject-3"),
                        eq("what's on my calendar"),
                        eq(chatClient),
                        any(),
                        eq(ReasoningDepth.FAST),
                        any());
    }

    // ---- L4 verifier-escalated router (flag-off safety and escalation call path) ----

    private ChatClient cheapClient;
    private ChatClient strongClient;
    private ToolCallLogService toolCallLogService;

    /** Economy-routed agent: GROK = cheap client, OPENAI (primary) = strong client. */
    private void routeCheapToGrok(String cheapAnswer, String strongAnswer) {
        cheapClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        strongClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            com.agenticai.core.chat.gateway.GatewayCallSpec spec =
                                    inv.getArgument(1);
                            return spec.providerName() == ProviderName.GROK
                                    ? cheapClient
                                    : strongClient;
                        });
        when(agent.isCostRoutingEnabled()).thenReturn(true);
        when(agent.getEconomyProviderName()).thenReturn(ProviderName.GROK);
        when(agent.getEconomyModel()).thenReturn("cheap-model");
        stubAnswer(cheapClient, cheapAnswer);
        stubAnswer(strongClient, strongAnswer);
        // Stubbing a deep-stub chain records prompt() invocations; clear them so verify(never()) is
        // meaningful.
        org.mockito.Mockito.clearInvocations(cheapClient, strongClient);
    }

    @SuppressWarnings("unchecked")
    private static void stubAnswer(ChatClient client, String answer) {
        when(client.prompt()
                        .system(any(String.class))
                        .messages(any(List.class))
                        .user(any(java.util.function.Consumer.class))
                        .advisors(any(java.util.function.Consumer.class))
                        .call()
                        .content())
                .thenReturn(answer);
    }

    private List<String> chatOnce(AgentChatService service) {
        return service.chat(AGENT_ID, "sess-l4", "subject-l4", "hello", null, null)
                .collectList()
                .block();
    }

    @Test
    void l4FlagOffKeepsTheCheapAnswerAndNeverCallsTheStrongClient() {
        routeCheapToGrok("I'm not sure about that.", "strong answer");
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), false, false, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("I'm not sure about that."), chatOnce(service));
        verify(strongClient, never()).prompt();
    }

    @Test
    void l4KillSwitchWinsAndKeepsTheCheapAnswer() {
        routeCheapToGrok("I'm not sure about that.", "strong answer");
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, true, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("I'm not sure about that."), chatOnce(service));
        verify(strongClient, never()).prompt();
    }

    @Test
    void l4ActiveRouterEscalatesALowConfidenceCheapAnswerToThePrimaryModel() {
        routeCheapToGrok("I'm not sure about that.", "strong answer");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(registry, true, false, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("strong answer"), chatOnce(service));
        assertEquals(
                1.0,
                registry.get(VerifierEscalationRouter.DECISION_METRIC)
                        .tags("outcome", "escalated", "reason", "low_confidence")
                        .counter()
                        .count());
    }

    @Test
    void l4ActiveRouterKeepsAConfidentCheapAnswer() {
        routeCheapToGrok("Paris.", "strong answer");
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("Paris."), chatOnce(service));
        verify(strongClient, never()).prompt();
    }

    @Test
    void l4EscalationFailureFailsOpenToTheCheapAnswer() {
        routeCheapToGrok("I'm not sure about that.", "unused");
        // The strong client blows up when asked; the turn must still deliver the cheap answer.
        strongClient = mock(ChatClient.class);
        when(strongClient.prompt())
                .thenThrow(new IllegalStateException("strong model unavailable"));
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("I'm not sure about that."), chatOnce(service));
    }

    @Test
    void l4DoesNotEscalateWhenTheCheapAttemptCalledANonReadOnlyTool() {
        // Two failed sends = a tool-error loop (escalation wanted) but the sends are not
        // replay-safe.
        routeCheapToGrok("Here is what I managed.", "strong answer");
        ToolCallLog sent = mock(ToolCallLog.class);
        when(sent.getToolName()).thenReturn("send_email");
        when(sent.getStartedAt()).thenReturn(Instant.now().plusSeconds(60));
        when(sent.getErrorMessage()).thenReturn("smtp 500");
        toolCallLogService = mock(ToolCallLogService.class);
        when(toolCallLogService.findByAgentIdAndSessionId(any(), any()))
                .thenReturn(List.of(sent, sent));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(registry, true, false, 2, 0.2);
        AgentChatService service = buildService(router, true);

        assertEquals(List.of("Here is what I managed."), chatOnce(service));
        verify(strongClient, never()).prompt();
        assertEquals(
                1.0,
                registry.get(VerifierEscalationRouter.SKIPPED_METRIC)
                        .tags("reason", "tool_error_loop")
                        .counter()
                        .count());
    }

    private AgentChatService buildService() {
        return buildService(null, false);
    }

    private AgentChatService buildService(VerifierEscalationRouter router, boolean economySimple) {
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        AgentService agentService = mock(AgentService.class);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        AgentGuardrailService agentGuardrailService =
                new AgentGuardrailService(
                        mock(TenantLimits.class),
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class));

        // The checkers are mocked to pass their input straight through: this suite is about
        // the buffered PATH (threading, retry, empty-reply, persistence), not about what
        // self-consistency or Reflexion decide - those have their own unit tests.
        AnswerConsistencyChecker consistencyChecker = mock(AnswerConsistencyChecker.class);
        when(consistencyChecker.checkAndRevise(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        ReflexionService reflexionService = mock(ReflexionService.class);
        when(reflexionService.critiqueAndMaybeRevise(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        QueryComplexityClassifier classifier = mock(QueryComplexityClassifier.class);
        when(classifier.isSimple(any())).thenReturn(economySimple);
        when(classifier.isSimple(any(), any(), any())).thenReturn(economySimple);

        AgentChatService built =
                AgentChatServiceTestFactory.create(
                        agentService,
                        credentialResolver,
                        llmGatewayService,
                        sessionMemoryService,
                        providerErrorTranslator,
                        agentToolProvider,
                        providerOf(mock(AgentMemoryService.class)),
                        providerOf((TenantMemoryService) null),
                        consistencyChecker,
                        reflexionService,
                        mock(TaskPlanningService.class),
                        toolCallLogService != null
                                ? toolCallLogService
                                : mock(ToolCallLogService.class),
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
                        agentGuardrailService,
                        mock(InlineAttachmentProcessor.class),
                        mock(ResourceAccessService.class),
                        mock(VisionUsageService.class),
                        mock(VisionSessionService.class),
                        providerOf(
                                (com.agenticai.core.providers.PlatformHostedLlmProviderService)
                                        null),
                        mock(LoadTestTenantAllowlist.class),
                        neverCancelled(),
                        java.time.Duration.ofSeconds(90),
                        java.time.Duration.ofSeconds(240),
                        // Keepalive interval far beyond any turn this suite runs, so no synthetic
                        // empty
                        // chunk is ever interleaved and the assertions above see only real output.
                        java.time.Duration.ofSeconds(600));
        if (router != null) {
            built.useVerifierEscalationRouter(providerOf(router));
        }
        return built;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** A cancel-watch that never fires; a bare mock returns null and NPEs in takeUntilOther. */
    private static ChatTurnCancelService neverCancelled() {
        ChatTurnCancelService cancel = mock(ChatTurnCancelService.class);
        when(cancel.watchForCancellation(any(), any())).thenReturn(Flux.never());
        return cancel;
    }
}
