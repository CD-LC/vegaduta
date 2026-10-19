package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.FreeTierProviderPoolEmptyException;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabTurnMessage;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.SamplingOptions;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link AgentLabCompletionService}: which client a lab completion runs on, what happens to the
 * hosted pool's reservations when it fails, and what prompt Spring AI actually sends.
 */
class AgentLabCompletionServiceTest {

    private static final String TENANT = "t1";

    private final AgentService agentService = mock(AgentService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final ProviderErrorTranslator translator = mock(ProviderErrorTranslator.class);

    private final UUID agentId = UUID.randomUUID();
    private Agent agent;

    /** What the overridden {@code invoke} saw, in call order. */
    private final List<ChatClient> invokedClients = new ArrayList<>();
    private final AtomicReference<String> seenSystemPrompt = new AtomicReference<>();
    private final AtomicReference<List<LabTurnMessage>> seenHistory = new AtomicReference<>();
    private final AtomicReference<String> seenSessionId = new AtomicReference<>();
    private Function<ChatClient, String> answer = client -> "answer";

    private AgentLabCompletionService service;

    @BeforeEach
    void setUp() {
        agent = new Agent("Support copilot", "You are the stored prompt.", ProviderName.ANTHROPIC, "claude-sonnet-5",
            900, 0.7, 0.95, false, ExperienceMode.ADVANCED);
        when(agentService.getEntity(agentId)).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("claude-sonnet-5");
        when(limits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        service = new AgentLabCompletionService(agentService, limits, pool, credentials, gateway, translator) {
            @Override
            String invoke(
                ChatClient client, String systemPrompt, List<LabTurnMessage> history, String userMessage,
                UUID id, String sessionId
            ) {
                invokedClients.add(client);
                seenSystemPrompt.set(systemPrompt);
                seenHistory.set(history);
                seenSessionId.set(sessionId);
                return answer.apply(client);
            }
        };
        TenantContext.set(TenantContext.of(TENANT, "u1", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private LabCompletionRequest request(String systemPrompt, Double temperature, Integer maxTokens) {
        return new LabCompletionRequest(
            agentId, systemPrompt, List.of(), "What is your refund policy?", temperature, maxTokens, "u1",
            "lab:run-1:0");
    }

    // ---- hosted pool --------------------------------------------------------

    private final TurnReservationGuards guards = new TurnReservationGuards();

    private HostedChainLink link(ChatClient client, String label) {
        return new HostedChainLink(client, "row:" + label, label, label + "-model", 0, 1200L, 340L, guards);
    }

    private List<ChatClient> hostedChain(int size) {
        when(limits.usesPlatformPool(TENANT, TenantPlan.PREMIUM)).thenReturn(true);
        List<ChatClient> clients = new ArrayList<>();
        List<HostedChainLink> chain = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ChatClient client = mock(ChatClient.class);
            clients.add(client);
            chain.add(link(client, "p" + i));
        }
        when(pool.buildChainForFreeTenant(
            any(), any(), anyInt(), anyDouble(), anyDouble(), any(), any(), any(SamplingOptions.class)))
            .thenReturn(chain);
        return clients;
    }

    private void verifyReservationsReleasedOnce() {
        verify(pool, times(1)).releaseReservations(
            TENANT, TenantPlan.PREMIUM, "u1", PerUserBudgetService.Modality.TEXT, 1200L, 340L, guards);
    }

    private void verifyReservationsNotReleased() {
        verify(pool, never()).releaseReservations(any(), any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void hostedBranchMetersTheRequesterAgainstTheTextBudget() {
        List<ChatClient> clients = hostedChain(1);

        assertEquals("answer", service.complete(request(null, null, null)));

        verify(pool).buildChainForFreeTenant(
            eq(TENANT), eq(TenantPlan.PREMIUM), eq(900), eq(0.7), eq(0.95), eq("u1"),
            eq(PerUserBudgetService.Modality.TEXT), eq(ChatClientResolver.samplingOptions(agent)));
        assertEquals(clients, invokedClients);
        assertEquals("lab:run-1:0", seenSessionId.get());
        // Success is reconciled by the link's own tracking advisors, not released here.
        verifyReservationsNotReleased();
        verifyNoInteractions(credentials, gateway, translator);
    }

    @Test
    void hostedBranchCarriesTheOverrideTemperatureAndMaxTokens() {
        hostedChain(1);

        service.complete(request("judge prompt", 0.0, 1024));

        verify(pool).buildChainForFreeTenant(
            eq(TENANT), eq(TenantPlan.PREMIUM), eq(1024), eq(0.0), eq(0.95), eq("u1"),
            eq(PerUserBudgetService.Modality.TEXT), any(SamplingOptions.class));
    }

    @Test
    void anAgentThatNamesThePlatformPoolRunsHostedEvenForAByokTenant() {
        agent = new Agent("Hosted", "prompt", ProviderName.PLATFORM_HOSTED, "auto", 900, 0.7, 0.95, false,
            ExperienceMode.ADVANCED);
        when(agentService.getEntity(agentId)).thenReturn(agent);
        when(limits.usesPlatformPool(TENANT, TenantPlan.PREMIUM)).thenReturn(false);
        ChatClient client = mock(ChatClient.class);
        when(pool.buildChainForFreeTenant(
            any(), any(), anyInt(), anyDouble(), anyDouble(), any(), any(), any(SamplingOptions.class)))
            .thenReturn(List.of(link(client, "p0")));

        assertEquals("answer", service.complete(request(null, null, null)));

        assertEquals(List.of(client), invokedClients);
        verifyNoInteractions(credentials, gateway);
    }

    @Test
    void aRetryableFailureOnTheFirstLinkFallsOverToTheSecond() {
        List<ChatClient> clients = hostedChain(2);
        RuntimeException boom = new RuntimeException("503 from upstream");
        when(translator.translate(boom)).thenReturn(new UpstreamProviderException(503, "upstream 503", "Busy."));
        answer = client -> {
            if (client == clients.get(0)) {
                throw boom;
            }
            return "from the second provider";
        };

        assertEquals("from the second provider", service.complete(request(null, null, null)));

        assertEquals(clients, invokedClients);
        verifyReservationsNotReleased();
    }

    @Test
    void aNonRetryableFailureStopsAtTheFirstLinkAndReleasesTheReservations() {
        List<ChatClient> clients = hostedChain(2);
        RuntimeException tooLarge = new RuntimeException("payload too large");
        UpstreamProviderException translated = new UpstreamProviderException(413, "413 too large", "Shorten it.");
        when(translator.translate(tooLarge)).thenReturn(translated);
        answer = client -> {
            throw tooLarge;
        };

        assertSame(translated, assertThrows(UpstreamProviderException.class,
            () -> service.complete(request(null, null, null))));

        assertEquals(List.of(clients.get(0)), invokedClients);
        verifyReservationsReleasedOnce();
    }

    @Test
    void whenEveryLinkFailsTheLastErrorIsThrownAndTheReservationsAreReleasedOnce() {
        List<ChatClient> clients = hostedChain(3);
        List<UpstreamProviderException> translated = new ArrayList<>();
        when(translator.translate(any(Throwable.class))).thenAnswer(call -> {
            UpstreamProviderException e = new UpstreamProviderException(502, "bad gateway", "Try again.");
            translated.add(e);
            return e;
        });
        answer = client -> {
            throw new RuntimeException("down");
        };

        UpstreamProviderException thrown = assertThrows(UpstreamProviderException.class,
            () -> service.complete(request(null, null, null)));

        assertEquals(clients, invokedClients);
        assertSame(translated.get(2), thrown);
        verifyReservationsReleasedOnce();
    }

    @Test
    void aLimitFailureIsNeverTranslatedOrRetriedAndReleasesTheReservations() {
        List<ChatClient> clients = hostedChain(2);
        TenantLimitExceededException quota = new TenantLimitExceededException("Monthly quota reached.");
        answer = client -> {
            throw quota;
        };

        assertSame(quota, assertThrows(TenantLimitExceededException.class,
            () -> service.complete(request(null, null, null))));

        assertEquals(List.of(clients.get(0)), invokedClients);
        verifyReservationsReleasedOnce();
        verifyNoInteractions(translator);
    }

    @Test
    void aContentPolicyVerdictIsNeverTranslatedOrRetriedAndReleasesTheReservations() {
        List<ChatClient> clients = hostedChain(2);
        LlmGatewayContentPolicyViolationException blocked =
            new LlmGatewayContentPolicyViolationException("Blocked by your workspace's content policy.");
        answer = client -> {
            throw blocked;
        };

        assertSame(blocked, assertThrows(LlmGatewayContentPolicyViolationException.class,
            () -> service.complete(request(null, null, null))));

        assertEquals(List.of(clients.get(0)), invokedClients);
        verifyReservationsReleasedOnce();
        verifyNoInteractions(translator);
    }

    @Test
    void aQuotaRejectionWhileBuildingTheChainPropagatesUnchanged() {
        when(limits.usesPlatformPool(TENANT, TenantPlan.PREMIUM)).thenReturn(true);
        TenantLimitExceededException quota = new TenantLimitExceededException("Monthly quota reached.");
        when(pool.buildChainForFreeTenant(
            any(), any(), anyInt(), anyDouble(), anyDouble(), any(), any(), any(SamplingOptions.class)))
            .thenThrow(quota);

        assertSame(quota, assertThrows(TenantLimitExceededException.class,
            () -> service.complete(request(null, null, null))));

        // buildChainForFreeTenant hands back its own reservations when it throws.
        verifyReservationsNotReleased();
        assertTrue(invokedClients.isEmpty());
    }

    // ---- BYOK ---------------------------------------------------------------

    private GatewayCallSpec byokSpec() {
        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(eq(TENANT), spec.capture());
        return spec.getValue();
    }

    private ChatClient byokClient() {
        when(limits.usesPlatformPool(TENANT, TenantPlan.PREMIUM)).thenReturn(false);
        when(credentials.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-primary");
        ChatClient client = mock(ChatClient.class);
        when(gateway.resolveClient(eq(TENANT), any(GatewayCallSpec.class))).thenReturn(client);
        return client;
    }

    @Test
    void byokUsesTheAgentsOwnProviderModelAndSamplingByDefault() {
        ChatClient client = byokClient();

        assertEquals("answer", service.complete(request(null, null, null)));

        GatewayCallSpec spec = byokSpec();
        assertEquals(ProviderName.ANTHROPIC, spec.providerName());
        assertEquals("sk-primary", spec.apiKey());
        assertEquals("claude-sonnet-5", spec.model());
        assertEquals(900, spec.maxTokens());
        assertEquals(0.7, spec.temperature());
        assertEquals(0.95, spec.topP());
        // No sampling extras and no xAI server tools: an empty map keeps the spec memo-eligible.
        assertTrue(spec.providerOptions().isEmpty());
        assertEquals(List.of(client), invokedClients);
        verifyNoInteractions(pool);
    }

    @Test
    void byokSpecCarriesTheOverrideTemperatureAndMaxTokens() {
        byokClient();

        service.complete(request("judge prompt", 0.0, 1024));

        GatewayCallSpec spec = byokSpec();
        assertEquals(0.0, spec.temperature());
        assertEquals(1024, spec.maxTokens());
        assertEquals(0.95, spec.topP());
    }

    @Test
    void byokSpecCarriesTheAgentsSamplingControls() {
        byokClient();
        agent.setSamplingParams(40, List.of("END"), null, null, 7);

        service.complete(request(null, null, null));

        SamplingOptions sampling = SamplingOptions.from(byokSpec().providerOptions());
        assertEquals(Integer.valueOf(40), sampling.topK());
        assertEquals(List.of("END"), sampling.stopSequences());
        assertEquals(Integer.valueOf(7), sampling.seed());
    }

    @Test
    void byokBuildsAClientForEveryCallSoTheBudgetAndRateGatesFireEachTime() {
        byokClient();

        service.complete(request(null, null, null));
        service.complete(request(null, null, null));

        verify(gateway, times(2)).resolveClient(eq(TENANT), any(GatewayCallSpec.class));
    }

    @Test
    void byokRoutesToTheAgentsTextModelPairWhenItsCredentialIsActive() {
        byokClient();
        agent.setTextModelProvider(TextModelProvider.OPENAI, "gpt-5.1");
        when(credentials.resolveActiveKey(ProviderName.OPENAI)).thenReturn("sk-text");

        service.complete(request(null, null, null));

        GatewayCallSpec spec = byokSpec();
        assertEquals(ProviderName.OPENAI, spec.providerName());
        assertEquals("gpt-5.1", spec.model());
        assertEquals("sk-text", spec.apiKey());
        verify(credentials, never()).resolveActiveKey(ProviderName.ANTHROPIC);
    }

    @Test
    void byokFallsBackToThePrimaryPairWhenTheTextModelCredentialIsGone() {
        byokClient();
        agent.setTextModelProvider(TextModelProvider.OPENAI, "gpt-5.1");
        when(credentials.resolveActiveKey(ProviderName.OPENAI)).thenThrow(new NoActiveProviderException());

        assertEquals("answer", service.complete(request(null, null, null)));

        GatewayCallSpec spec = byokSpec();
        assertEquals(ProviderName.ANTHROPIC, spec.providerName());
        assertEquals("claude-sonnet-5", spec.model());
        assertEquals("sk-primary", spec.apiKey());
    }

    /**
     * The BYOK branch maps a text-model provider with {@code ProviderName.valueOf(name())} because
     * ChatClientResolver's own mapper is private. That is only equivalent while every mapping is
     * name-identical - this fails the day a TextModelProvider is added that is not.
     */
    @Test
    void everyTextModelProviderHasAProviderNameOfTheSameName() {
        for (TextModelProvider provider : TextModelProvider.values()) {
            assertNotNull(ProviderName.valueOf(provider.name()), provider.name());
        }
    }

    @Test
    void aByokProviderFailureIsTranslatedWithTheProviderThatWasCalled() {
        byokClient();
        RuntimeException boom = new RuntimeException("401");
        UpstreamProviderException translated = new UpstreamProviderException(401, "bad key", "Check the key.");
        when(translator.translate(boom, ProviderName.ANTHROPIC)).thenReturn(translated);
        answer = client -> {
            throw boom;
        };

        assertSame(translated, assertThrows(UpstreamProviderException.class,
            () -> service.complete(request(null, null, null))));
    }

    @Test
    void aByokLimitFailureIsNeverTranslated() {
        byokClient();
        LlmGatewayLimitExceededException limited = new LlmGatewayLimitExceededException("Rate limit reached.");
        answer = client -> {
            throw limited;
        };

        assertSame(limited, assertThrows(LlmGatewayLimitExceededException.class,
            () -> service.complete(request(null, null, null))));
        verifyNoInteractions(translator);
    }

    @Test
    void aGatewayBudgetRejectionWhileBuildingTheClientPropagatesUnchanged() {
        when(limits.usesPlatformPool(TENANT, TenantPlan.PREMIUM)).thenReturn(false);
        when(credentials.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-primary");
        LlmGatewayLimitExceededException overBudget = new LlmGatewayLimitExceededException("Monthly cap reached.");
        when(gateway.resolveClient(eq(TENANT), any(GatewayCallSpec.class))).thenThrow(overBudget);

        assertSame(overBudget, assertThrows(LlmGatewayLimitExceededException.class,
            () -> service.complete(request(null, null, null))));
        assertTrue(invokedClients.isEmpty());
    }

    // ---- prompt, history, context ---------------------------------------------

    @Test
    void aNullPromptUsesTheAgentsStoredPrompt() {
        byokClient();

        service.complete(request(null, null, null));

        assertEquals("You are the stored prompt.", seenSystemPrompt.get());
    }

    @Test
    void aBlankPromptIsPassedAsBlankNotReplacedByTheStoredOne() {
        byokClient();

        service.complete(request("", null, null));

        assertEquals("", seenSystemPrompt.get());
    }

    @Test
    void anOverridePromptAndTheHistoryReachTheCall() {
        byokClient();
        List<LabTurnMessage> history = List.of(
            new LabTurnMessage("user", "Hi"), new LabTurnMessage("assistant", "Hello."));

        service.complete(new LabCompletionRequest(
            agentId, "Variant without section 3", history, "And refunds?", null, null, "u1", "lab:run-1:4"));

        assertEquals("Variant without section 3", seenSystemPrompt.get());
        assertEquals(history, seenHistory.get());
        assertEquals("lab:run-1:4", seenSessionId.get());
    }

    @Test
    void aNullHistoryIsTreatedAsEmpty() {
        byokClient();

        service.complete(new LabCompletionRequest(agentId, null, null, "Q", null, null, "u1", "s"));

        assertEquals(List.of(), seenHistory.get());
    }

    @Test
    void aMissingTenantContextIsRejectedBeforeAnyClientIsBuilt() {
        TenantContext.clear();

        assertThrows(IllegalStateException.class, () -> service.complete(request(null, null, null)));

        verifyNoInteractions(pool, gateway, credentials);
    }

    @Test
    void limitFailuresAreExactlyTheFourFamilies() {
        assertTrue(AgentLabCompletionService.isLimitFailure(new TenantLimitExceededException("x")));
        assertTrue(AgentLabCompletionService.isLimitFailure(new LlmGatewayLimitExceededException("x")));
        assertTrue(AgentLabCompletionService.isLimitFailure(new FreeTierProviderPoolEmptyException()));
        assertTrue(AgentLabCompletionService.isLimitFailure(new TenantUnavailableException("x")));
        assertTrue(!AgentLabCompletionService.isLimitFailure(new UpstreamProviderException(429, "x", "y")));
        assertTrue(!AgentLabCompletionService.isLimitFailure(new LlmGatewayContentPolicyViolationException("x")));
        assertTrue(!AgentLabCompletionService.isLimitFailure(new RuntimeException("x")));
    }

    // ---- the real call: what Spring AI sends ----------------------------------

    /** A ChatModel that records the prompt it was handed and answers with a fixed text (or none). */
    private static final class RecordingChatModel implements ChatModel {

        private final String reply;
        private Prompt seen;

        RecordingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.seen = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private AgentLabCompletionService realService() {
        return new AgentLabCompletionService(agentService, limits, pool, credentials, gateway, translator);
    }

    /**
     * Settles the order Spring AI 1.1.7 assembles a prompt in when {@code system}, {@code messages}
     * and {@code user} are all set: system first, then the history, then the new user text - with
     * braces left alone (no template rendering without params).
     */
    @Test
    void invokeSendsSystemThenHistoryThenTheUserMessage() {
        RecordingChatModel model = new RecordingChatModel("It is 30 days.");
        ChatClient client = ChatClient.builder(model).build();

        String out = realService().invoke(
            client,
            "You are a support agent. Reply as {\"answer\": string}.",
            List.of(new LabTurnMessage("user", "Hi"), new LabTurnMessage("assistant", "Hello. How can I help?")),
            "What is the {refund} window?",
            agentId, "lab:run-1:0");

        assertEquals("It is 30 days.", out);
        List<Message> sent = model.seen.getInstructions();
        assertEquals(4, sent.size());
        assertEquals(MessageType.SYSTEM, sent.get(0).getMessageType());
        assertEquals("You are a support agent. Reply as {\"answer\": string}.", sent.get(0).getText());
        assertEquals(MessageType.USER, sent.get(1).getMessageType());
        assertEquals("Hi", sent.get(1).getText());
        assertEquals(MessageType.ASSISTANT, sent.get(2).getMessageType());
        assertEquals("Hello. How can I help?", sent.get(2).getText());
        assertEquals(MessageType.USER, sent.get(3).getMessageType());
        assertEquals("What is the {refund} window?", sent.get(3).getText());
    }

    @Test
    void invokeSendsNoSystemMessageForABlankPromptAndNoHistoryWhenThereIsNone() {
        RecordingChatModel model = new RecordingChatModel("ok");
        ChatClient client = ChatClient.builder(model).build();

        realService().invoke(client, "   ", List.of(), "Just the question", agentId, "lab:run-1:1");

        List<Message> sent = model.seen.getInstructions();
        assertEquals(1, sent.size());
        assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, sent.get(0));
        assertEquals("Just the question", sent.get(0).getText());
    }

    @Test
    void invokeTurnsAnEmptyCompletionIntoAnEmptyString() {
        RecordingChatModel model = new RecordingChatModel(null);
        ChatClient client = ChatClient.builder(model).build();

        assertEquals("", realService().invoke(client, null, null, "Q", null, null));
    }
}
