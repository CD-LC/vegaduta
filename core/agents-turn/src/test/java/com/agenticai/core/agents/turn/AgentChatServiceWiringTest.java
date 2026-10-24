package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.HandoffChain;
import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code AgentChatService#resolveOptionalCollaborators} connects the optional parts of a turn (hooks, compaction,
 * experiments, the specialist handoff, the hot path, the escalation router, workflow events, TTFT metrics) after the
 * service is built. Every hand-built fixture skips it, so until now nothing proved it ran or that it reads the right
 * providers: a part left unconnected is not an error, the turn just quietly loses the feature.
 *
 * <p>This builds the service the way the application does - the real constructor, its facades as the real
 * {@code @Component} records autowired from a Spring context, {@code @PostConstruct} run by the container - with each
 * optional bean present, then absent.
 */
class AgentChatServiceWiringTest {

    /** A context holding every hard dependency of the chat service as a double, and the optional beans on request. */
    private static AnnotationConfigApplicationContext context(boolean withOptionalBeans) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        bean(ctx, AgentService.class);
        bean(ctx, SessionMemoryService.class);
        bean(ctx, AgentToolPort.class);
        bean(ctx, ToolCallLogService.class);
        bean(ctx, ProviderCredentialResolver.class);
        bean(ctx, LlmGatewayService.class);
        bean(ctx, ProviderErrorTranslator.class);
        bean(ctx, FreeTierChatClientService.class);
        bean(ctx, LlmQualityJudgeService.class);
        bean(ctx, QueryComplexityClassifier.class);
        bean(ctx, TenantLimits.class);
        bean(ctx, TenantStatusGuard.class);
        bean(ctx, FeatureFlagService.class);
        bean(ctx, LoadTestTenantAllowlist.class);
        bean(ctx, ChatTurnCancelService.class);
        bean(ctx, AgentGuardrailService.class);
        bean(ctx, ResourceAccessService.class);
        bean(ctx, LlmResponseCacheService.class);
        bean(ctx, AnswerConsistencyChecker.class);
        bean(ctx, ReflexionService.class);
        bean(ctx, TaskPlanningService.class);
        bean(ctx, FileStorageService.class);
        bean(ctx, InlineAttachmentProcessor.class);
        bean(ctx, VisionUsageService.class);
        bean(ctx, VisionSessionService.class);
        ctx.registerBean(
                ChatTimeouts.class,
                () -> new ChatTimeouts(Duration.ofSeconds(90), Duration.ofSeconds(240), Duration.ofSeconds(600)));
        if (withOptionalBeans) {
            bean(ctx, AgentHookRuntime.class);
            bean(ctx, ChatContextCompaction.class);
            bean(ctx, AgentExperimentService.class);
            bean(ctx, SpecialistHandoffService.class);
            bean(ctx, SandboxFileRegistry.class);
            bean(ctx, ModelModalitiesService.class);
            bean(ctx, WorkflowEventPublisher.class);
            ctx.registerBean(SimpleMeterRegistry.class);
            ctx.registerBean(
                    ChatHotpath.class,
                    () -> new ChatHotpath(Duration.ofSeconds(5), Duration.ofSeconds(5), true, true));
            ctx.registerBean(
                    VerifierEscalationRouter.class,
                    () -> new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2));
        }
        // The service and the facades are the production components: constructor-autowired, optional parts as
        // ObjectProviders.
        ctx.register(
                ChatModelFacade.class,
                ChatPolicyFacade.class,
                ChatObservabilityFacade.class,
                ChatQualityFacade.class,
                ChatContextFacade.class,
                ChatRoutingFacade.class,
                AgentChatService.class);
        ctx.refresh();
        return ctx;
    }

    private static <T> void bean(AnnotationConfigApplicationContext ctx, Class<T> type) {
        ctx.registerBean(type, () -> mock(type));
    }

    @Test
    void everyOptionalPartIsConnectedWhenItsBeanIsPresent() {
        try (AnnotationConfigApplicationContext ctx = context(true)) {
            AgentChatService service = ctx.getBean(AgentChatService.class);

            assertSame(ctx.getBean(AgentHookRuntime.class), service.agentHooks, "agent hooks");
            assertSame(ctx.getBean(ChatContextCompaction.class), service.contextCompaction, "context compaction");
            assertSame(ctx.getBean(AgentExperimentService.class), service.agentExperimentService, "experiments");
            assertSame(ctx.getBean(SandboxFileRegistry.class), service.sandboxFiles, "sandbox files");
            assertSame(ctx.getBean(ModelModalitiesService.class), service.modelModalities, "model modalities");
            assertSame(ctx.getBean(ChatHotpath.class), service.hotpath, "hot path");
            assertNotSame(ChatTtftRecorder.NOOP, service.ttft, "TTFT recorder is registry-backed");

            ChatSpecialistHandoff handoff = (ChatSpecialistHandoff) ReflectionTestUtils.getField(service, "specialistHandoffs");
            assertSame(
                    ctx.getBean(SpecialistHandoffService.class),
                    ReflectionTestUtils.getField(handoff, "specialistHandoffService"),
                    "specialist handoff");

            VerifierEscalationRouter router = ctx.getBean(VerifierEscalationRouter.class);
            assertSame(router, ReflectionTestUtils.getField(service.clientResolver, "escalationRouter"), "router on the client resolver");
            Object calls = ReflectionTestUtils.getField(service.bufferedTurns, "calls");
            assertSame(router, ReflectionTestUtils.getField(calls, "escalationRouter"), "router on the buffered turns");
            assertSame(
                    ctx.getBean(WorkflowEventPublisher.class),
                    ReflectionTestUtils.getField(service.events, "workflowEventPublisher"),
                    "workflow events");
        }
    }

    @Test
    void everyOptionalPartIsAbsentButSafeWhenItsBeanIsMissing() {
        try (AnnotationConfigApplicationContext ctx = context(false)) {
            AgentChatService service = ctx.getBean(AgentChatService.class);

            assertNull(service.agentHooks);
            assertNull(service.contextCompaction);
            assertNull(service.agentExperimentService);
            assertNull(service.sandboxFiles);
            assertNull(service.modelModalities);
            assertSame(ChatHotpath.OFF, service.hotpath, "no hot path bean = today's calls");
            assertSame(ChatTtftRecorder.NOOP, service.ttft, "no meter registry = no recorder");
            assertNull(ReflectionTestUtils.getField(service.clientResolver, "escalationRouter"));
            Object calls = ReflectionTestUtils.getField(service.bufferedTurns, "calls");
            assertNull(ReflectionTestUtils.getField(calls, "escalationRouter"));
            assertNull(ReflectionTestUtils.getField(service.events, "workflowEventPublisher"));
            // A missing handoff service is a readable refusal to the calling agent, not an NPE.
            assertEquals(
                    "Specialist delegation is not available right now.",
                    service.delegateToSpecialist(
                            HandoffChain.root("s", UUID.randomUUID()), "billing", "do it", null));
        }
    }
}
