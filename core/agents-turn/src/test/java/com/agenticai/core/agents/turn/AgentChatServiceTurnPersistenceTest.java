package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
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
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/**
 * Turn-loss fix (2026-08-07) - before {@link AgentChatService#withAssistantTurnPersistence}
 * existed, the FAST streaming path persisted the assistant turn ONLY in {@code doOnComplete}: a
 * mid-stream provider failure, stream-idle timeout, or client cancel silently dropped every token
 * the user had already been shown from session memory. This suite pins the repaired contract
 * through that package-private seam (the full streaming pipeline above it is not reasonably
 * drivable through Mockito deep stubs - same reasoning as {@code callWithProviderFallback}'s seam
 * in {@link AgentChatServiceByokFallbackGatingTest}):
 *
 * <ul>
 *   <li>complete → the caller's complete-path body runs exactly once, and the interrupted-persist
 *       path writes nothing;
 *   <li>error/cancel after tokens → the partial answer is persisted once, suffixed with {@link
 *       AgentChatService#INTERRUPTED_REPLY_MARKER}, and the error still reaches the subscriber
 *       unchanged (persistence-only);
 *   <li>error before any token → nothing is persisted, so hosted-chain rungs that fail over cleanly
 *       write no phantom turns.
 * </ul>
 */
class AgentChatServiceTurnPersistenceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "sess-1";

    private AgentChatService service;
    private SessionMemoryService sessionMemoryService;

    @BeforeEach
    void setUp() {
        sessionMemoryService = mock(SessionMemoryService.class);
        service = buildService();
    }

    /** Same construction harness as {@link AgentChatServiceByokFallbackGatingTest}. */
    private AgentChatService buildService() {
        return AgentChatServiceTestFactory.create(
                mock(AgentService.class),
                mock(ProviderCredentialResolver.class),
                mock(LlmGatewayService.class),
                sessionMemoryService,
                mock(ProviderErrorTranslator.class),
                mock(AgentToolPort.class),
                providerOf(mock(AgentMemoryService.class)),
                providerOf((TenantMemoryService) null),
                mock(AnswerConsistencyChecker.class),
                mock(ReflexionService.class),
                mock(TaskPlanningService.class),
                mock(ToolCallLogService.class),
                mock(TenantLimits.class),
                mock(FreeTierChatClientService.class),
                mock(FileStorageService.class),
                mock(TenantStatusGuard.class),
                mock(LlmQualityJudgeService.class),
                providerOf((PlatformIssueService) null),
                mock(FeatureFlagService.class),
                mock(LlmResponseCacheService.class),
                providerOf((SemanticResponseCacheService) null),
                mock(QueryComplexityClassifier.class),
                providerOf((RlPolicyPort) null),
                providerOf((RlTurnRecorder) null),
                providerOf((ModerationService) null),
                providerOf((ContentGuardrailEngine) null),
                providerOf((GuardrailViolationLogger) null),
                mock(AgentGuardrailService.class),
                mock(InlineAttachmentProcessor.class),
                mock(ResourceAccessService.class),
                mock(VisionUsageService.class),
                mock(VisionSessionService.class),
                providerOf((com.agenticai.core.providers.PlatformHostedLlmProviderService) null),
                mock(LoadTestTenantAllowlist.class),
                mock(ChatTurnCancelService.class),
                java.time.Duration.ofSeconds(90),
                // Buffered-depth total-turn ceiling and SSE keepalive interval
                // (2026-09-17). Neither fires in this suite - every turn here resolves
                // in milliseconds - so both are just non-null values the constructor needs.
                java.time.Duration.ofSeconds(240),
                java.time.Duration.ofSeconds(20));
    }

    @Test
    void completedStreamRunsTheCompleteBodyExactlyOnceAndNeverTheInterruptedPersist() {
        AtomicInteger completeCalls = new AtomicInteger();
        AtomicReference<String> completedAnswer = new AtomicReference<>();

        Flux<String> decorated =
                service.withAssistantTurnPersistence(
                        Flux.just("Hello, ", "world."),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        fullAnswer -> {
                            completeCalls.incrementAndGet();
                            completedAnswer.set(fullAnswer);
                        });

        List<String> emitted = decorated.collectList().block();

        assertEquals(
                List.of("Hello, ", "world."), emitted, "the token stream itself must be untouched");
        assertEquals(1, completeCalls.get(), "the complete-path body must run exactly once");
        assertEquals("Hello, world.", completedAnswer.get());
        // The interrupted-persist path must not ALSO write a turn - that would
        // double-persist every normal answer.
        verify(sessionMemoryService, never()).appendTurn(any(), any());
    }

    @Test
    void emptyCompletedStreamRunsNothing() {
        AtomicInteger completeCalls = new AtomicInteger();

        service.withAssistantTurnPersistence(
                        Flux.empty(),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        f -> completeCalls.incrementAndGet())
                .collectList()
                .block();

        assertEquals(0, completeCalls.get(), "an empty answer must not be persisted as a turn");
        verify(sessionMemoryService, never()).appendTurn(any(), any());
    }

    @Test
    void midStreamErrorPersistsThePartialAnswerMarkedInterruptedAndStillPropagates() {
        IllegalStateException streamDeath = new IllegalStateException("provider died mid-answer");
        AtomicInteger completeCalls = new AtomicInteger();

        Flux<String> decorated =
                service.withAssistantTurnPersistence(
                        Flux.concat(Flux.just("The answer is"), Flux.error(streamDeath)),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        f -> completeCalls.incrementAndGet());

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> decorated.collectList().block());
        // Reactor's block() rethrows the terminal error as-is for unchecked
        // exceptions - persistence must be a pure side-channel on top of that.
        assertSame(streamDeath, thrown, "error propagation to the client must be unchanged");

        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq(SESSION_ID), turn.capture());
        assertEquals(MessageRole.ASSISTANT, turn.getValue().role());
        assertEquals(
                "The answer is" + AgentChatService.INTERRUPTED_REPLY_MARKER,
                turn.getValue().content());
        assertEquals(
                0, completeCalls.get(), "the complete body must never run for a failed stream");
    }

    @Test
    void preTokenErrorPersistsNothing() {
        // The retryable failure shape: auth/billing/model errors reject before
        // any output, and the fallback/hosted-chain branches replay the turn
        // against another provider - a phantom empty turn here would corrupt
        // the history every failover replays from.
        Flux<String> decorated =
                service.withAssistantTurnPersistence(
                        Flux.error(new IllegalStateException("rejected before any token")),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        f -> {});

        assertThrows(RuntimeException.class, () -> decorated.collectList().block());

        verify(sessionMemoryService, never()).appendTurn(any(), any());
    }

    // ---- whole-turn failure with no text (prod signed-in pass 2026-10-05, bug 13) ----

    private static final String REJECTED =
            "The AI provider rejected our credentials. Check the API key for this provider in Providers settings.";

    /**
     * The turn as a whole failed before any text: the transcript must keep an assistant entry
     * carrying the message the person saw, or a reload shows their question with nothing under it.
     */
    @Test
    void aTurnThatFailsWithNoTextIsKeptInTheTranscriptAsAFailedReply() {
        com.agenticai.core.chat.UpstreamProviderException rejected =
                new com.agenticai.core.chat.UpstreamProviderException(
                        401, "[GOOGLE_VERTEX] HTTP 401", REJECTED);

        Flux<String> decorated =
                service.withFailedReplyPersistence(Flux.error(rejected), AGENT_ID, SESSION_ID);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> decorated.collectList().block());
        assertSame(rejected, thrown, "error propagation to the client must be unchanged");
        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq(SESSION_ID), turn.capture());
        assertEquals(MessageRole.ASSISTANT, turn.getValue().role());
        assertEquals(ChatTurn.FAILED_REPLY_PREFIX + REJECTED, turn.getValue().content());
        assertTrue(ChatTurn.isFailedReply(turn.getValue()));
    }

    @Test
    void aFailedReplyNeverShowsRawInternalDetailOnlyTheGenericSentence() {
        service.withFailedReplyPersistence(
                        Flux.error(
                                new IllegalStateException(
                                        "NullPointerException at com.x.Y.z(Y.java:12)")),
                        AGENT_ID,
                        SESSION_ID)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block();

        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq(SESSION_ID), turn.capture());
        assertEquals(
                ChatTurn.FAILED_REPLY_PREFIX
                        + "Something went wrong finishing this reply. Please try again.",
                turn.getValue().content());
    }

    /**
     * The other way a turn can fail after the question was recorded: the reply stream cannot even
     * be BUILT (thrown synchronously, not delivered as a stream error). That path used to leave the
     * question unanswered.
     */
    @Test
    void aFailureWhileBuildingTheReplyStreamIsKeptInTheTranscriptAndStillThrown() {
        com.agenticai.core.chat.UpstreamProviderException rejected =
                new com.agenticai.core.chat.UpstreamProviderException(
                        401, "[GOOGLE_VERTEX] HTTP 401", REJECTED);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.buildingTurnStream(
                                        AGENT_ID,
                                        SESSION_ID,
                                        () -> {
                                            throw rejected;
                                        }));

        assertSame(rejected, thrown);
        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq(SESSION_ID), turn.capture());
        assertEquals(ChatTurn.FAILED_REPLY_PREFIX + REJECTED, turn.getValue().content());
    }

    @Test
    void buildingAStreamThatSucceedsWritesNothing() {
        Flux<String> built = Flux.just("hi");

        assertSame(built, service.buildingTurnStream(AGENT_ID, SESSION_ID, () -> built));

        verify(sessionMemoryService, never()).appendTurn(any(), any());
    }

    @Test
    void aFailureAfterTextOrACompletedOrCancelledTurnWritesNoFailedReply() {
        // After text: the inner hook already kept the partial reply marked interrupted.
        service.withFailedReplyPersistence(
                        Flux.concat(
                                Flux.just("The answer is"),
                                Flux.error(new IllegalStateException("died"))),
                        AGENT_ID,
                        SESSION_ID)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block();
        // Completed normally.
        service.withFailedReplyPersistence(Flux.just("Hello."), AGENT_ID, SESSION_ID)
                .collectList()
                .block();
        // Cancelled before anything arrived.
        service.withFailedReplyPersistence(Flux.<String>never(), AGENT_ID, SESSION_ID)
                .subscribe(
                        new BaseSubscriber<>() {
                            @Override
                            protected void hookOnSubscribe(
                                    org.reactivestreams.Subscription subscription) {
                                subscription.cancel();
                            }
                        });

        verify(sessionMemoryService, never()).appendTurn(any(), any());
    }

    @Test
    void aFailingTranscriptWriteNeverMasksTheOriginalError() {
        IllegalStateException original = new IllegalStateException("provider down");
        doThrow(new IllegalStateException("redis down"))
                .when(sessionMemoryService)
                .appendTurn(any(), any());

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.withFailedReplyPersistence(
                                                Flux.error(original), AGENT_ID, SESSION_ID)
                                        .collectList()
                                        .block());

        assertSame(original, thrown);
    }

    @Test
    void clientCancelAfterTokensPersistsThePartialAnswerMarkedInterrupted() {
        AtomicInteger completeCalls = new AtomicInteger();
        // Emits one token then hangs - the shape of a stream a user abandons.
        Flux<String> decorated =
                service.withAssistantTurnPersistence(
                        Flux.concat(Flux.just("Partial thought"), Flux.never()),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        f -> completeCalls.incrementAndGet());

        decorated.subscribe(
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnNext(String value) {
                        cancel();
                    }
                });

        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(sessionMemoryService).appendTurn(eq(SESSION_ID), turn.capture());
        assertEquals(
                "Partial thought" + AgentChatService.INTERRUPTED_REPLY_MARKER,
                turn.getValue().content());
        assertEquals(0, completeCalls.get());
    }

    @Test
    void aPersistFailureNeverMasksTheOriginalStreamError() {
        // Redis being down is a plausible CAUSE of the interruption - it must
        // not turn the persistence side-channel into the error the user sees.
        doThrow(new IllegalStateException("redis down"))
                .when(sessionMemoryService)
                .appendTurn(any(), any());
        IllegalStateException streamDeath = new IllegalStateException("provider died mid-answer");

        Flux<String> decorated =
                service.withAssistantTurnPersistence(
                        Flux.concat(Flux.just("Partial"), Flux.error(streamDeath)),
                        AGENT_ID,
                        SESSION_ID,
                        new StringBuilder(),
                        f -> {});

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> decorated.collectList().block());
        assertSame(streamDeath, thrown);
    }

    @Test
    void markerIsAVisibleHonestSuffixNotAReplacement() {
        // Guards the marker's shape itself: partial content must survive
        // verbatim, with the marker appended - not replaced by it.
        assertTrue(AgentChatService.INTERRUPTED_REPLY_MARKER.contains("[reply interrupted]"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
