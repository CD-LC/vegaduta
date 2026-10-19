package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
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
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds an {@link AgentChatService} from the flat, pre-facade argument list the hand-built test
 * fixtures were written against, so those fixtures keep their one positional call while production
 * code takes the grouped constructor. Optional collaborators the fixtures never supplied (workflow
 * events, meter registry, hooks, experiments, ...) are {@code null} providers, i.e. absent - the
 * same state a hand-built service always had.
 */
final class AgentChatServiceTestFactory {

    private AgentChatServiceTestFactory() {}

    @SuppressWarnings("java:S107")
    static AgentChatService create(
            AgentService agentService,
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            SessionMemoryService sessionMemoryService,
            ProviderErrorTranslator providerErrorTranslator,
            AgentToolPort agentToolProvider,
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService,
            AnswerConsistencyChecker answerConsistencyChecker,
            ReflexionService reflexionService,
            TaskPlanningService taskPlanningService,
            ToolCallLogService toolCallLogService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            FileStorageService fileStorageService,
            TenantStatusGuard tenantStatusGuard,
            LlmQualityJudgeService llmQualityJudgeService,
            ObjectProvider<PlatformIssueService> platformIssueService,
            FeatureFlagService featureFlagService,
            LlmResponseCacheService responseCacheService,
            ObjectProvider<SemanticResponseCacheService> semanticResponseCacheService,
            QueryComplexityClassifier queryComplexityClassifier,
            ObjectProvider<RlPolicyPort> rlPolicyPort,
            ObjectProvider<RlTurnRecorder> rlTurnRecorder,
            ObjectProvider<ModerationService> moderationService,
            ObjectProvider<ContentGuardrailEngine> contentGuardrailEngine,
            ObjectProvider<GuardrailViolationLogger> guardrailViolationLogger,
            AgentGuardrailService agentGuardrailService,
            InlineAttachmentProcessor inlineAttachmentProcessor,
            ResourceAccessService resourceAccessService,
            VisionUsageService visionUsageService,
            VisionSessionService visionSessionService,
            ObjectProvider<PlatformHostedLlmProviderService> platformHostedLlmProviderService,
            LoadTestTenantAllowlist loadTestTenantAllowlist,
            ChatTurnCancelService chatTurnCancelService,
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval) {
        return new AgentChatService(
                agentService,
                sessionMemoryService,
                agentToolProvider,
                toolCallLogService,
                new ChatModelFacade(
                        credentialResolver,
                        llmGatewayService,
                        providerErrorTranslator,
                        freeTierChatClientService,
                        llmQualityJudgeService,
                        queryComplexityClassifier,
                        platformHostedLlmProviderService),
                new ChatPolicyFacade(
                        tenantLimits,
                        tenantStatusGuard,
                        featureFlagService,
                        loadTestTenantAllowlist,
                        chatTurnCancelService,
                        agentGuardrailService,
                        resourceAccessService,
                        moderationService,
                        contentGuardrailEngine,
                        guardrailViolationLogger),
                new ChatObservabilityFacade(
                        platformIssueService, null, null, rlPolicyPort, rlTurnRecorder, null),
                new ChatQualityFacade(
                        responseCacheService,
                        semanticResponseCacheService,
                        answerConsistencyChecker,
                        reflexionService,
                        taskPlanningService),
                new ChatContextFacade(
                        agentMemoryService,
                        tenantMemoryService,
                        fileStorageService,
                        inlineAttachmentProcessor,
                        visionUsageService,
                        visionSessionService,
                        null,
                        null,
                        null),
                new ChatRoutingFacade(null, null, null, null, null),
                new ChatTimeouts(streamIdleTimeout, bufferedTurnTimeout, bufferedKeepAliveInterval));
    }
}
