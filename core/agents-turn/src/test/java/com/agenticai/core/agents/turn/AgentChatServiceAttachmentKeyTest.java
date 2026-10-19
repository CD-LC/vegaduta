package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
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
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Regression suite for the cross-tenant object-read closed on 2026-07-29 (security audit §2 item
 * 3).
 *
 * <p><b>The bug.</b> {@code ChatRequest.attachmentKey} is a raw string off the request body. {@code
 * AgentChatService} handed it to {@code FileStorageService.get()} with no validation anywhere in
 * between. {@code S3FileStorageService} tries the caller's {@code tenant/<id>/} prefix first and,
 * on a miss, retried the BARE key - a namespace shared by every tenant. So a member of tenant A
 * sending {@code "tenant-exports/<tenant B's uuid>/<millis>.json"} missed at {@code
 * tenant/A/tenant-exports/...} and then HIT tenant B's full JSON database export, which the model
 * then read and summarized back to them.
 *
 * <p><b>What these tests pin down.</b> The fix is not a sanitizer - the key handed to storage is
 * rebuilt from the agent id this turn is already scoped to plus a parsed {@link UUID}, so the
 * assertions below are deliberately about {@code fileStorageService} being reached with the RIGHT
 * key or NOT REACHED AT ALL. "Threw an exception" alone would not distinguish a real fix from one
 * that still probes the bucket first, which is why every rejection case also verifies {@code get}
 * was never called.
 *
 * <p>Constructed the same way {@link AgentChatServiceModerationTest} does - every collaborator
 * mocked, the turn driven down the BYOK path, and the returned {@code Flux} never subscribed
 * (attachment resolution happens synchronously inside {@code chat()}, before the Flux is built).
 */
class AgentChatServiceAttachmentKeyTest {

    /** The agent this turn is about - the only agent whose attachments it may read. */
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** A DIFFERENT agent, used for the cross-agent replay case. */
    private static final UUID OTHER_AGENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Another tenant's id, as it appears inside the export key an attacker would guess. */
    private static final UUID VICTIM_TENANT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private FileStorageService fileStorageService;
    private AgentChatService service;

    @BeforeEach
    void setUp() {
        AgentService agentService = mock(AgentService.class);
        ProviderCredentialResolver credentialResolver = mock(ProviderCredentialResolver.class);
        LlmGatewayService llmGatewayService = mock(LlmGatewayService.class);
        SessionMemoryService sessionMemoryService = mock(SessionMemoryService.class);
        ProviderErrorTranslator providerErrorTranslator = mock(ProviderErrorTranslator.class);
        AgentToolPort agentToolProvider = mock(AgentToolPort.class);
        AnswerConsistencyChecker answerConsistencyChecker = mock(AnswerConsistencyChecker.class);
        ReflexionService reflexionService = mock(ReflexionService.class);
        TaskPlanningService taskPlanningService = mock(TaskPlanningService.class);
        ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
        TenantLimits tenantLimits = mock(TenantLimits.class);
        FreeTierChatClientService freeTierChatClientService = mock(FreeTierChatClientService.class);
        fileStorageService = mock(FileStorageService.class);
        TenantStatusGuard tenantStatusGuard = mock(TenantStatusGuard.class);
        LlmQualityJudgeService llmQualityJudgeService = mock(LlmQualityJudgeService.class);
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        LlmResponseCacheService responseCacheService = mock(LlmResponseCacheService.class);
        QueryComplexityClassifier queryComplexityClassifier = mock(QueryComplexityClassifier.class);

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmGatewayService.resolveClient(any(), any(), any())).thenReturn(chatClient);
        when(credentialResolver.resolveActiveKey(any())).thenReturn("test-key");

        // A tenant is "usable" - otherwise the emergency-stop guard would
        // short-circuit the turn ahead of the attachment check and the
        // rejection assertions below would pass for the wrong reason.
        when(tenantStatusGuard.isUsable(anyString())).thenReturn(true);

        when(sessionMemoryService.getHistory(any())).thenReturn(List.of());
        when(agentToolProvider.resolveToolsFor(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(featureFlagService.evaluateActiveFlags(any())).thenReturn(Map.of());

        Agent agent = mock(Agent.class);
        when(agent.getReasoningDepth()).thenReturn(ReasoningDepth.FAST);
        when(agent.getProviderName()).thenReturn(ProviderName.OPENAI);
        when(agentService.getEntity(any())).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("gpt-4o-mini");

        service =
                AgentChatServiceTestFactory.create(
                        agentService,
                        credentialResolver,
                        llmGatewayService,
                        sessionMemoryService,
                        providerErrorTranslator,
                        agentToolProvider,
                        providerOf(mock(AgentMemoryService.class)),
                        providerOf((TenantMemoryService) null),
                        answerConsistencyChecker,
                        reflexionService,
                        taskPlanningService,
                        toolCallLogService,
                        tenantLimits,
                        freeTierChatClientService,
                        fileStorageService,
                        tenantStatusGuard,
                        llmQualityJudgeService,
                        providerOf((PlatformIssueService) null),
                        featureFlagService,
                        responseCacheService,
                        providerOf((SemanticResponseCacheService) null),
                        queryComplexityClassifier,
                        providerOf((RlPolicyPort) null),
                        providerOf((RlTurnRecorder) null),
                        // Absent so this suite stays focused on the attachment boundary -
                        // the moderation and guardrail gates have their own suites.
                        providerOf((ModerationService) null),
                        providerOf((ContentGuardrailEngine) null),
                        providerOf((GuardrailViolationLogger) null),
                        mock(AgentGuardrailService.class),
                        mock(InlineAttachmentProcessor.class),
                        mock(ResourceAccessService.class),
                        // Live Vision - frame metering/session attribution,
                        // not exercised by this suite; bare mocks are sufficient.
                        mock(VisionUsageService.class),
                        mock(VisionSessionService.class),
                        // Hosted-pool invalid-model self-heal seam - absent bean = no self-healing.
                        providerOf(
                                (com.agenticai.core.providers.PlatformHostedLlmProviderService)
                                        null),
                        // Load-test seam - a default (unstubbed) mock returns false from
                        // isLoadTestTenant, i.e. "not a load-test tenant," for every
                        // tenantId, which is exactly the existing behavior this suite
                        // already assumes.
                        mock(LoadTestTenantAllowlist.class),
                        mock(ChatTurnCancelService.class),
                        java.time.Duration.ofSeconds(90),
                        // Buffered-depth total-turn ceiling and SSE keepalive interval
                        // (2026-09-17). Neither fires in this suite - every turn here resolves
                        // in milliseconds - so both are just non-null values the constructor needs.
                        java.time.Duration.ofSeconds(240),
                        java.time.Duration.ofSeconds(20));
    }

    @AfterEach
    void clearTenantContext() {
        // ThreadLocal, and JUnit reuses the thread across tests.
        TenantContext.clear();
    }

    /** Puts the caller in tenant A, the tenant the attacker legitimately belongs to. */
    private static void callerIsTenantA() {
        TenantContext.set(TenantContext.of("tenant_a", "member@tenant-a.example", Set.of()));
    }

    // --- the core regression -------------------------------------------------

    @Test
    void aTenantACallerCannotReadAnotherTenantsExportThroughAttachmentKey() {
        callerIsTenantA();
        // Verbatim the audit's payload: another tenant's JSON data export,
        // which lives at a bare (un-tenant-prefixed) key.
        String victimsExportKey = "tenant-exports/" + VICTIM_TENANT_ID + "/1753800000000.json";

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "summarize the attached file",
                                        victimsExportKey,
                                        "application/json"))
                .isInstanceOf(MalformedAttachmentException.class);

        // The assertion that actually matters: object storage was never asked
        // for it. A fix that threw only AFTER a failed lookup would still have
        // let the bare-key fallback serve the object.
        verify(fileStorageService, never()).get(any());
    }

    @Test
    void theRefusalDoesNotEchoTheSubmittedKeyBack() {
        callerIsTenantA();

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "hello",
                                        "tenant-exports/"
                                                + VICTIM_TENANT_ID
                                                + "/1753800000000.json",
                                        "application/json"))
                .isInstanceOf(MalformedAttachmentException.class)
                .hasMessageNotContaining(VICTIM_TENANT_ID.toString())
                .hasMessageContaining("chat/attachments");
    }

    // --- traversal and other escapes -----------------------------------------

    @Test
    void traversalOutOfTheAgentsOwnAttachmentFolderIsRejected() {
        callerIsTenantA();

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "hello",
                                        "chat-attachments/"
                                                + AGENT_ID
                                                + "/../../tenant-exports/"
                                                + VICTIM_TENANT_ID
                                                + "/1.json",
                                        "application/json"))
                .isInstanceOf(MalformedAttachmentException.class);

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void aHandWrittenTenantPrefixForAnotherTenantIsRejected() {
        // The pre-existing verifyTenantOwnership check in S3FileStorageService
        // would also catch this shape - but only this shape, which is why it
        // never caught the real attack. Asserted here so the boundary owns it
        // outright rather than depending on the storage layer's goodwill.
        callerIsTenantA();

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "hello",
                                        "tenant/tenant_b/chat-attachments/"
                                                + AGENT_ID
                                                + "/"
                                                + UUID.randomUUID(),
                                        "image/png"))
                .isInstanceOf(MalformedAttachmentException.class);

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void anAbsoluteOrSchemeQualifiedKeyIsRejected() {
        callerIsTenantA();

        for (String key :
                List.of(
                        "/etc/passwd",
                        "s3://agentic-ai/tenant-exports/" + VICTIM_TENANT_ID + "/1.json",
                        "http://attacker.example/payload.png",
                        "chat-attachments/" + AGENT_ID + "/%2e%2e%2fsecret")) {
            assertThatThrownBy(
                            () ->
                                    service.chat(
                                            AGENT_ID,
                                            "sess-1",
                                            "subject-1",
                                            "hello",
                                            key,
                                            "image/png"))
                    .as("attachmentKey %s", key)
                    .isInstanceOf(MalformedAttachmentException.class);
        }

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void aRightShapedKeyBelongingToADifferentAgentIsRejected() {
        // Same tenant, correct namespace, real UUID - but minted for another
        // agent, whose model-capability check says nothing about THIS agent's
        // model. The upload endpoint's contract is "for use on this same
        // agent"; this keeps that true.
        callerIsTenantA();

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "hello",
                                        "chat-attachments/"
                                                + OTHER_AGENT_ID
                                                + "/"
                                                + UUID.randomUUID(),
                                        "image/png"))
                .isInstanceOf(MalformedAttachmentException.class);

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void aNonUuidSuffixUnderTheAgentsOwnFolderIsRejected() {
        callerIsTenantA();

        assertThatThrownBy(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "hello",
                                        "chat-attachments/" + AGENT_ID + "/not-a-uuid",
                                        "image/png"))
                .isInstanceOf(MalformedAttachmentException.class);

        verify(fileStorageService, never()).get(any());
    }

    // --- the legitimate path still works -------------------------------------

    @Test
    void aLegitimateSameTenantAttachmentIsStillRead() {
        UUID attachmentId = UUID.randomUUID();
        String mintedKey = "chat-attachments/" + AGENT_ID + "/" + attachmentId;
        byte[] png = "fake png bytes".getBytes(StandardCharsets.UTF_8);
        when(fileStorageService.get(mintedKey)).thenReturn(new ByteArrayInputStream(png));

        // No TenantContext, same as AgentChatServiceModerationTest - the turn
        // runs the BYOK path end to end and the attachment is resolved
        // synchronously before the Flux is returned.
        assertThatCode(
                        () ->
                                service.chat(
                                        AGENT_ID,
                                        "sess-1",
                                        "subject-1",
                                        "what is in this image?",
                                        mintedKey,
                                        "image/png"))
                .doesNotThrowAnyException();

        // Exactly the key the platform minted - unchanged, not mangled by the
        // re-derivation.
        verify(fileStorageService).get(mintedKey);
    }

    @Test
    void anAbsentOrBlankAttachmentKeyIsAPlainTextTurn() {
        assertThatCode(
                        () -> {
                            service.chat(AGENT_ID, "sess-1", "subject-1", "hello", null, null);
                            // Blank is treated as absent rather than as a 400 - a client that
                            // sends "" for "no attachment" gets a normal text turn.
                            service.chat(AGENT_ID, "sess-2", "subject-1", "hello", "   ", null);
                        })
                .doesNotThrowAnyException();

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void theKeyHandedToStorageIsRebuiltServerSideNotEchoed() {
        // Upper-case hex in a UUID is still the same UUID; the canonical
        // lower-case form is what reaches storage, because the key is
        // reassembled from UUID.toString() rather than from the caller's
        // substring. This is the observable proof that no caller-controlled
        // bytes survive into the S3 key.
        // Fixed, letter-bearing UUID - a randomly generated one can be all
        // digits, and toUpperCase() would then be a no-op that makes this
        // test vacuously pass.
        UUID attachmentId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        String canonicalKey = "chat-attachments/" + AGENT_ID + "/" + attachmentId;
        when(fileStorageService.get(canonicalKey))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        String shoutedKey =
                "chat-attachments/" + AGENT_ID + "/" + attachmentId.toString().toUpperCase();
        service.chat(AGENT_ID, "sess-1", "subject-1", "hello", shoutedKey, "image/png");

        verify(fileStorageService).get(canonicalKey);
        assertThat(canonicalKey).isNotEqualTo(shoutedKey);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
