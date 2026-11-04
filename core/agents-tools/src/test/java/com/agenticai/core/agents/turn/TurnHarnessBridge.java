package com.agenticai.core.agents.turn;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.chat.LlmRetryPolicy;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/**
 * Builds the REAL {@link AgentChatService} (the production turn pipeline: preflight with its
 * SEC-004 scoped session key, prompt assembly with its history replay, the dispatcher, the FAST
 * stream and its continuation loop) from the few collaborators an end-to-end test cares about: the
 * agent lookup, the LLM gateway, session memory and the tool port. The many optional or peripheral
 * collaborators (moderation, caches, RL, vision, quality judges, attachments, load-test seam) are
 * absent or inert, the same state a deployment that has not enabled them is in.
 *
 * <p>Lives in this package only because the collaborator facades are package-private.
 */
public final class TurnHarnessBridge {

    private TurnHarnessBridge() {}

    /**
     * Collaborators a test wants to observe or tune: the response cache and the quality judge (so a
     * test can say "this reply was never cached / never judged"), the {@code app.chat.finish.*}
     * settings, and the tool-call log the turn reads which tools the conversation already used from.
     * Any {@code null} member gets an inert mock (or the shipped defaults).
     */
    public record Extras(
            LlmResponseCacheService responseCache,
            LlmQualityJudgeService judge,
            ChatFinishProperties finish,
            Tuning tuning,
            ToolCallLogService toolCallLog,
            com.agenticai.core.guardrails.ContentGuardrailEngine guardrailEngine) {

        public Extras(
                LlmResponseCacheService responseCache,
                LlmQualityJudgeService judge,
                ChatFinishProperties finish,
                Tuning tuning,
                ToolCallLogService toolCallLog) {
            this(responseCache, judge, finish, tuning, toolCallLog, null);
        }

        public Extras(
                LlmResponseCacheService responseCache,
                LlmQualityJudgeService judge,
                ChatFinishProperties finish,
                Tuning tuning) {
            this(responseCache, judge, finish, tuning, null, null);
        }

        public Extras(
                LlmResponseCacheService responseCache,
                LlmQualityJudgeService judge,
                ChatFinishProperties finish) {
            this(responseCache, judge, finish, null, null, null);
        }

        public static Extras none() {
            return new Extras(null, null, null, null, null, null);
        }
    }

    /**
     * How the turn behaves when it runs long or a provider fails: the stream idle and buffered-turn
     * timeouts, {@code app.chat.resilience.*}, {@code app.llm.retry.*}, and whether provider errors are
     * translated by the real {@link ProviderErrorTranslator} (the default is an inert mock, which maps
     * every error to {@code null}), and {@code app.chat.continuation.*} / {@code app.chat.follow-up.*}. A
     * {@code null} member keeps the production default. {@code complexityClassifier} is the cost-routing
     * classifier (an inert mock that judges nothing simple by default).
     */
    public record Tuning(
            Duration streamIdle,
            Duration bufferedTurn,
            ChatResilienceProperties resilience,
            LlmRetryPolicy retryPolicy,
            boolean realErrorTranslation,
            ContinuationProperties continuation,
            FollowUpProperties followUp,
            QueryComplexityClassifier complexityClassifier,
            ToolLedgerProperties toolLedger,
            ChatParityProperties parity,
            ChatCacheProperties cache,
            com.agenticai.core.agents.runtime.AgentRuntime runtime,
            com.agenticai.core.settings.SettingsResolver settings) {

        /** Without a settings catalog: every behaviour setting is its platform value. */
        public Tuning(
                Duration streamIdle,
                Duration bufferedTurn,
                ChatResilienceProperties resilience,
                LlmRetryPolicy retryPolicy,
                boolean realErrorTranslation,
                ContinuationProperties continuation,
                FollowUpProperties followUp,
                QueryComplexityClassifier complexityClassifier,
                ToolLedgerProperties toolLedger,
                ChatParityProperties parity,
                ChatCacheProperties cache,
                com.agenticai.core.agents.runtime.AgentRuntime runtime) {
            this(
                    streamIdle,
                    bufferedTurn,
                    resilience,
                    retryPolicy,
                    realErrorTranslation,
                    continuation,
                    followUp,
                    complexityClassifier,
                    toolLedger,
                    parity,
                    cache,
                    runtime,
                    null);
        }

        public Tuning(
                Duration streamIdle,
                Duration bufferedTurn,
                ChatResilienceProperties resilience,
                LlmRetryPolicy retryPolicy,
                boolean realErrorTranslation,
                ContinuationProperties continuation,
                FollowUpProperties followUp,
                QueryComplexityClassifier complexityClassifier,
                ToolLedgerProperties toolLedger,
                ChatParityProperties parity,
                ChatCacheProperties cache) {
            this(
                    streamIdle,
                    bufferedTurn,
                    resilience,
                    retryPolicy,
                    realErrorTranslation,
                    continuation,
                    followUp,
                    complexityClassifier,
                    toolLedger,
                    parity,
                    cache,
                    null);
        }

        public Tuning(
                Duration streamIdle,
                Duration bufferedTurn,
                ChatResilienceProperties resilience,
                LlmRetryPolicy retryPolicy,
                boolean realErrorTranslation,
                ContinuationProperties continuation,
                FollowUpProperties followUp,
                QueryComplexityClassifier complexityClassifier) {
            this(
                    streamIdle,
                    bufferedTurn,
                    resilience,
                    retryPolicy,
                    realErrorTranslation,
                    continuation,
                    followUp,
                    complexityClassifier,
                    null,
                    null,
                    null,
                    null);
        }

        public Tuning(
                Duration streamIdle,
                Duration bufferedTurn,
                ChatResilienceProperties resilience,
                LlmRetryPolicy retryPolicy,
                boolean realErrorTranslation) {
            this(streamIdle, bufferedTurn, resilience, retryPolicy, realErrorTranslation, null, null, null, null, null, null, null);
        }
    }

    /**
     * @param maxContinuations {@code app.chat.continuation.max-passes} (production default 8).
     */
    public static AgentChatService chatService(
            AgentService agentService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            SessionMemoryService sessionMemoryService,
            AgentToolPort toolPort,
            TenantLimits tenantLimits,
            TenantStatusGuard tenantStatusGuard,
            FeatureFlagService featureFlagService,
            int maxContinuations) {
        return chatService(
                agentService,
                credentialResolver,
                llmGatewayService,
                sessionMemoryService,
                toolPort,
                tenantLimits,
                tenantStatusGuard,
                featureFlagService,
                maxContinuations,
                Extras.none());
    }

    public static AgentChatService chatService(
            AgentService agentService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            SessionMemoryService sessionMemoryService,
            AgentToolPort toolPort,
            TenantLimits tenantLimits,
            TenantStatusGuard tenantStatusGuard,
            FeatureFlagService featureFlagService,
            int maxContinuations,
            Extras extras) {
        LlmResponseCacheService responseCache =
                extras.responseCache() != null
                        ? extras.responseCache()
                        : mock(LlmResponseCacheService.class);
        LlmQualityJudgeService judge =
                extras.judge() != null ? extras.judge() : mock(LlmQualityJudgeService.class);
        // The buffered depths run the answer through these two; a mock would return null (an empty
        // answer), so they hand the answer back unchanged, i.e. "the checker found nothing to fix".
        AnswerConsistencyChecker consistencyChecker = mock(AnswerConsistencyChecker.class);
        when(consistencyChecker.checkAndRevise(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
        ReflexionService reflexion = mock(ReflexionService.class);
        when(reflexion.critiqueAndMaybeRevise(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
        AgentGuardrailService agentGuardrailService =
                new AgentGuardrailService(
                        tenantLimits,
                        mock(AgentRepository.class),
                        mock(ResourceAccessService.class));
        Tuning tuning = extras.tuning() != null ? extras.tuning() : new Tuning(null, null, null, null, false);
        ProviderErrorTranslator translator =
                tuning.realErrorTranslation()
                        ? new ProviderErrorTranslator(new com.fasterxml.jackson.databind.ObjectMapper(), null)
                        : mock(ProviderErrorTranslator.class);
        ChatModelFacade model =
                new ChatModelFacade(
                        credentialResolver,
                        llmGatewayService,
                        translator,
                        mock(FreeTierChatClientService.class),
                        judge,
                        tuning.complexityClassifier() != null
                                ? tuning.complexityClassifier()
                                : mock(QueryComplexityClassifier.class),
                        absent());
        ChatTurnCancelService cancelService = mock(ChatTurnCancelService.class);
        when(cancelService.watchForCancellation(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Flux.never());
        ChatPolicyFacade policy =
                new ChatPolicyFacade(
                        tenantLimits,
                        tenantStatusGuard,
                        featureFlagService,
                        mock(LoadTestTenantAllowlist.class),
                        cancelService,
                        agentGuardrailService,
                        mock(ResourceAccessService.class),
                        absent(),
                        extras.guardrailEngine() == null ? absent() : present(extras.guardrailEngine()),
                        extras.guardrailEngine() == null
                                ? absent()
                                : present(
                                        new com.agenticai.core.guardrails.GuardrailViolationLogger(
                                                mock(com.agenticai.core.audit.AuditLogService.class))));
        ChatObservabilityFacade observability =
                new ChatObservabilityFacade(absent(), null, null, absent(), absent(), null);
        ChatQualityFacade quality =
                new ChatQualityFacade(
                        responseCache,
                        absent(),
                        consistencyChecker,
                        reflexion,
                        mock(TaskPlanningService.class));
        ChatContextFacade context =
                new ChatContextFacade(
                        present(mock(AgentMemoryService.class)),
                        absent(),
                        mock(FileStorageService.class),
                        mock(InlineAttachmentProcessor.class),
                        mock(VisionUsageService.class),
                        mock(VisionSessionService.class),
                        null,
                        null,
                        null,
                        null);
        ChatTimeouts timeouts =
                new ChatTimeouts(
                        tuning.streamIdle() != null ? tuning.streamIdle() : Duration.ofSeconds(90),
                        tuning.bufferedTurn() != null ? tuning.bufferedTurn() : Duration.ofSeconds(240),
                        Duration.ofSeconds(20),
                        maxContinuations,
                        extras.finish() != null ? extras.finish() : new ChatFinishProperties(),
                        tuning.resilience() != null ? tuning.resilience() : new ChatResilienceProperties(),
                        tuning.retryPolicy() != null ? tuning.retryPolicy() : LlmRetryPolicy.defaults(),
                        null);
        timeouts.useConversationPolicies(
                tuning.continuation() != null ? tuning.continuation() : new ContinuationProperties(),
                tuning.followUp() != null ? tuning.followUp() : new FollowUpProperties());
        timeouts.useToolLedger(tuning.toolLedger());
        timeouts.useParity(tuning.parity());
        timeouts.useCache(tuning.cache());
        timeouts.useRuntime(tuning.runtime());
        if (tuning.settings() != null) {
            timeouts.useSettings(present(tuning.settings()));
        }
        AgentChatService service = new AgentChatService(
                agentService,
                sessionMemoryService,
                toolPort,
                extras.toolCallLog() != null ? extras.toolCallLog() : mock(ToolCallLogService.class),
                model,
                policy,
                observability,
                quality,
                context,
                new ChatRoutingFacade(null, null, null, null, null),
                timeouts);
        // Production resolves every model-facing text through the prompt registry, where the deprecated
        // app.chat.finish.* / app.chat.continuation.* properties are only a platform-default seed
        // (PromptPropertyAliasConfiguration). Wire the same seeds here so a harness that tunes those
        // properties exercises the path a deployment's properties take.
        ChatFinishProperties finish = extras.finish() != null ? extras.finish() : new ChatFinishProperties();
        ContinuationProperties continuation =
                tuning.continuation() != null ? tuning.continuation() : new ContinuationProperties();
        com.agenticai.core.prompts.DefaultPromptResolver promptResolver =
                new com.agenticai.core.prompts.DefaultPromptResolver(
                        com.agenticai.core.prompts.PromptResolver.defaultsOnly().catalog(),
                        new com.agenticai.core.prompts.InMemoryPromptOverrideStore(),
                        new com.agenticai.core.prompts.PromptProperties(),
                        java.util.List.of(
                                PromptPropertyAliases.continueUnfinished(continuation),
                                PromptPropertyAliases.cutOff(finish),
                                PromptPropertyAliases.retryToolCall(finish),
                                PromptPropertyAliases.truncatedNotice(finish),
                                PromptPropertyAliases.refusalNotice(finish),
                                PromptPropertyAliases.malformedToolCallNotice(finish),
                                PromptPropertyAliases.emptyReplyMessage(finish)));
        service.usePromptDirectiveSettings(PromptDirectiveSettings.forTest(promptResolver));
        return service;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return (ObjectProvider<T>) new StaticListableBeanFactory().getBeanProvider(Object.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> present(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
