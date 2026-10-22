package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.AgentTurn.Message;
import com.agenticai.core.chat.AgentTurn.Tool;
import com.agenticai.core.chat.AgentTurn.ToolCall;
import com.agenticai.core.chat.gateway.GatewayGovernanceAdvisor;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Locks in the three things that make this endpoint safe and useful: (1) the model's tool calls
 * come back to the caller UNEXECUTED, (2) every reservation reaches exactly one terminal call and
 * the gate runs before any provider is touched, (3) failover walks the pool but never re-asks
 * another model about a forbidden prompt. The provider call is stubbed through the package-private
 * {@code callModel} seam rather than mocking ChatClient's fluent chain - same approach as {@code
 * FimCompletionServiceTest}.
 */
class AgentTurnServiceTest {

    private PlatformHostedLlmProviderService hostedProviders;
    private HostedLlmChatClientFactory chatClientFactory;
    private RecordingEntitlements entitlements;

    @BeforeEach
    void setUp() {
        hostedProviders = mock(PlatformHostedLlmProviderService.class);
        chatClientFactory = mock(HostedLlmChatClientFactory.class);
        when(chatClientFactory.create(any(), any(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(mock(ChatClient.class));
        entitlements = new RecordingEntitlements();
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Rows are built BEFORE the stubbing call - creating a mock inside thenReturn(...) breaks
     * Mockito.
     */
    private void givenPool(String... models) {
        List<HostedProviderSelection> rows = new ArrayList<>();
        for (String model : models) {
            rows.add(row(model));
        }
        when(hostedProviders.resolveEnabledChain()).thenReturn(rows);
    }

    private static HostedProviderSelection row(String model) {
        PlatformHostedLlmProvider provider = mock(PlatformHostedLlmProvider.class);
        when(provider.getModel()).thenReturn(model);
        return new HostedProviderSelection(provider, "key-" + model);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        // A fresh Stream per call, like the real ObjectProvider - a shared one is single-use.
        when(provider.orderedStream())
                .thenAnswer(invocation -> value == null ? Stream.empty() : Stream.of(value));
        return provider;
    }

    private static ObjectProvider<GatewayGovernanceAdvisor> noAdvisors() {
        return providerOf(null);
    }

    private static ChatResponse toolCallResponse() {
        AssistantMessage message =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call_1",
                                                "function",
                                                "read_file",
                                                "{\"path\":\"a.ts\"}")))
                        .build();
        return new ChatResponse(
                List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1200, 40)).build());
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }

    private static AgentTurn.Request request(String preferredModel) {
        return new AgentTurn.Request(
                List.of(
                        new Message("system", "be useful", null, null, null),
                        new Message("user", "fix it", null, null, null)),
                List.of(new Tool("read_file", "Read a file", Map.of("type", "object"))),
                null,
                null,
                preferredModel);
    }

    /** Stubs the provider call; records every invocation. */
    private static final class Stub extends AgentTurnService {
        final List<ToolCallingChatOptions> optionsSeen = new ArrayList<>();
        final List<List<org.springframework.ai.chat.messages.Message>> messagesSeen =
                new ArrayList<>();
        final List<ChatClient> clientsSeen = new ArrayList<>();
        java.util.function.Function<Integer, ChatResponse> behaviour = n -> textResponse("ok");
        int calls;

        Stub(
                ObjectProvider<FimCompletionEntitlements> e,
                PlatformHostedLlmProviderService p,
                HostedLlmChatClientFactory f,
                int maxConcurrent) {
            super(e, p, f, noAdvisors(), 2048, 4096, 0.2, maxConcurrent);
        }

        @Override
        ChatResponse callModel(
                ChatClient client,
                List<org.springframework.ai.chat.messages.Message> messages,
                ToolCallingChatOptions options) {
            calls++;
            optionsSeen.add(options);
            messagesSeen.add(messages);
            clientsSeen.add(client);
            return behaviour.apply(calls);
        }
    }

    private Stub service() {
        return new Stub(providerOf(entitlements), hostedProviders, chatClientFactory, 4);
    }

    // ---- tool calls come back unexecuted ----------------------------------

    @Test
    void toolCallsAreReturnedToTheCallerNotExecutedAndUsageIsSettled() {
        givenPool("model-a");
        Stub service = service();
        service.behaviour = n -> toolCallResponse();

        AgentTurn.Result result = service.turn(request(null));

        assertEquals("tool_calls", result.finishReason());
        assertEquals(1, result.toolCalls().size());
        assertEquals("call_1", result.toolCalls().get(0).id());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertEquals(Map.of("path", "a.ts"), result.toolCalls().get(0).args());
        assertEquals("model-a", result.model());

        ToolCallingChatOptions options = service.optionsSeen.get(0);
        assertTrue(
                CallerRunsToolLoop.isSet(options),
                "the server must never run the tools - the files and the shell are on the client");
        assertEquals(1, options.getToolCallbacks().size());
        assertEquals("read_file", options.getToolCallbacks().get(0).getToolDefinition().name());

        assertEquals(1, entitlements.settled);
        assertEquals("model-a", entitlements.settledModel);
        assertEquals(1200L, entitlements.settledPrompt);
        assertEquals(40L, entitlements.settledCompletion);
        assertEquals(0, entitlements.abandoned);
    }

    @Test
    void aDefinitionOnlyCallbackCanNeverSucceed() {
        ToolCallingChatOptions options =
                AgentTurnService.toolOptions(List.of(new Tool("x", "d", Map.of())));
        assertThrows(
                IllegalStateException.class, () -> options.getToolCallbacks().get(0).call("{}"));
    }

    @Test
    void plainTextFinishesWithStop() {
        givenPool("model-a");
        AgentTurn.Result result = service().turn(request(null));
        assertEquals("stop", result.finishReason());
        assertEquals("ok", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    // ---- message mapping ---------------------------------------------------

    @Test
    void consecutiveToolResultsMergeIntoOneToolResponseMessage() {
        List<Message> wire =
                List.of(
                        new Message("system", "s", null, null, null),
                        new Message("user", "u", null, null, null),
                        new Message(
                                "assistant",
                                "",
                                List.of(
                                        new ToolCall("c1", "read_file", Map.of("path", "a")),
                                        new ToolCall("c2", "read_file", Map.of("path", "b"))),
                                null,
                                null),
                        new Message("tool", "A", null, "c1", "read_file"),
                        new Message("tool", "B", null, "c2", "read_file"),
                        new Message("user", "go on", null, null, null));

        var mapped = AgentTurnService.toSpringMessages(wire);

        assertEquals(5, mapped.size(), "system, user, assistant, ONE tool-response message, user");
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, mapped.get(2));
        assertEquals(2, assistant.getToolCalls().size());
        assertEquals("{\"path\":\"a\"}", assistant.getToolCalls().get(0).arguments());
        ToolResponseMessage tools = assertInstanceOf(ToolResponseMessage.class, mapped.get(3));
        assertEquals(
                List.of("c1", "c2"),
                tools.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList());
    }

    @Test
    void aTrailingRunOfToolResultsIsFlushed() {
        var mapped =
                AgentTurnService.toSpringMessages(
                        List.of(
                                new Message("user", "u", null, null, null),
                                new Message("tool", "A", null, "c1", "t")));
        assertInstanceOf(ToolResponseMessage.class, mapped.get(1));
    }

    @Test
    void invalidToolArgumentsBecomeNoArgumentsRatherThanAFailedTurn() {
        assertEquals(Map.of(), AgentTurnService.parseArgs("{not json"));
        assertEquals(Map.of(), AgentTurnService.parseArgs(null));
    }

    // ---- the gate ----------------------------------------------------------

    @Test
    void withNoEntitlementsAdapterTheServiceRefusesAndTouchesNoProvider() {
        Stub service = new Stub(providerOf(null), hostedProviders, chatClientFactory, 4);
        assertThrows(
                AgentTurnService.EntitlementsUnavailableException.class,
                () -> service.turn(request(null)));
        verify(hostedProviders, never()).resolveEnabledChain();
        assertEquals(0, service.calls);
    }

    @Test
    void aRejectedReservationPropagatesAndNoProviderIsTouched() {
        RuntimeException rejection = new IllegalArgumentException("over quota");
        entitlements.rejectWith = rejection;
        Stub service = service();
        assertSame(
                rejection, assertThrows(RuntimeException.class, () -> service.turn(request(null))));
        verify(hostedProviders, never()).resolveEnabledChain();
        assertEquals(0, entitlements.abandoned);
    }

    @Test
    void reservationCoversEstimatedPromptPlusTheClampedCompletion() {
        givenPool("m");
        Stub service = service();
        AgentTurn.Request asksForTooMuch =
                new AgentTurn.Request(
                        request(null).messages(), request(null).tools(), 1_000_000, null, null);

        service.turn(asksForTooMuch);

        int prompt = AgentTurnService.estimatePromptTokens(asksForTooMuch);
        assertEquals(
                prompt + 4096,
                entitlements.reserved,
                "the caller's maxTokens is honoured only downwards");
    }

    @Test
    void anEmptyPoolAbandonsTheLeaseAndSaysSo() {
        givenPool();
        assertThrows(
                AgentTurnService.NoModelAvailableException.class,
                () -> service().turn(request(null)));
        assertEquals(1, entitlements.abandoned);
        assertEquals(0, entitlements.settled);
    }

    // ---- failover ----------------------------------------------------------

    @Test
    void aFailingRowFailsOverToTheNextAndTheLeaseIsSettledOnce() {
        givenPool("flaky", "steady");
        Stub service = service();
        service.behaviour =
                n -> {
                    if (n == 1) {
                        throw new IllegalStateException("502 from provider");
                    }
                    return textResponse("done");
                };

        AgentTurn.Result result = service.turn(request(null));

        assertEquals("steady", result.model());
        assertEquals(2, service.calls);
        assertEquals(1, entitlements.settled);
        assertEquals("steady", entitlements.settledModel);
        assertEquals(0, entitlements.abandoned);
    }

    @Test
    void whenEveryRowFailsTheLeaseIsAbandonedAndTheErrorIsBadGatewayMaterial() {
        givenPool("a", "b");
        Stub service = service();
        service.behaviour =
                n -> {
                    throw new IllegalStateException("down");
                };
        assertThrows(
                AgentTurnService.UpstreamFailedException.class, () -> service.turn(request(null)));
        assertEquals(2, service.calls);
        assertEquals(1, entitlements.abandoned);
        assertEquals(0, entitlements.settled);
    }

    @Test
    void aContentPolicyBlockIsNotFailedOverToAnotherModel() {
        givenPool("a", "b");
        Stub service = service();
        service.behaviour =
                n -> {
                    throw new LlmGatewayContentPolicyViolationException("blocked by policy");
                };
        assertThrows(
                LlmGatewayContentPolicyViolationException.class, () -> service.turn(request(null)));
        assertEquals(1, service.calls, "another model would answer the same forbidden prompt");
        assertEquals(1, entitlements.abandoned);
    }

    @Test
    void aModelWithNoOutputCountsAsAFailureAndFailsOver() {
        givenPool("mute", "talker");
        Stub service = service();
        service.behaviour = n -> n == 1 ? textResponse("") : textResponse("hi");
        assertEquals("talker", service.turn(request(null)).model());
    }

    @Test
    void aClientCanSteerToAnEnabledRowButNotNameAnArbitraryModel() {
        List<HostedProviderSelection> chain = List.of(row("a"), row("b"), row("c"));
        assertEquals("c", AgentTurnService.orderChain(chain, " c ").get(0).provider().getModel());
        assertEquals(
                chain,
                AgentTurnService.orderChain(chain, "not-in-the-pool"),
                "an unknown id leaves the operator's order alone");
        assertEquals(
                "a",
                AgentTurnService.orderChain(chain, "not-in-the-pool").get(0).provider().getModel());
    }

    // ---- concurrency -------------------------------------------------------

    @Test
    void whenEverySlotIsTakenANewTurnIsRefusedAndItsLeaseGivenBack() throws Exception {
        givenPool("slow");
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Stub service = new Stub(providerOf(entitlements), hostedProviders, chatClientFactory, 1);
        service.behaviour =
                n -> {
                    inCall.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return textResponse("late");
                };

        CompletableFuture<AgentTurn.Result> first =
                CompletableFuture.supplyAsync(() -> service.turn(request(null)));
        assertTrue(inCall.await(5, TimeUnit.SECONDS));

        assertThrows(AgentTurnService.BusyException.class, () -> service.turn(request(null)));
        assertEquals(1, entitlements.abandoned, "the refused turn's reservation must be returned");

        release.countDown();
        assertEquals("late", first.get(5, TimeUnit.SECONDS).content());
        assertEquals(1, entitlements.settled);
    }

    // ---- controller bounds -------------------------------------------------

    private static HttpStatus statusOf(AgentTurn.Request request) {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class, () -> AgentTurnController.validate(request));
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void theControllerRejectsMalformedRequestsBeforeAnythingIsReserved() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(new AgentTurn.Request(List.of(), null, null, null, null)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        new AgentTurn.Request(
                                List.of(new Message("assistant", "hi", null, null, null)),
                                null,
                                null,
                                null,
                                null)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        new AgentTurn.Request(
                                List.of(new Message("tool", "x", null, null, "t")),
                                null,
                                null,
                                null,
                                null)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        new AgentTurn.Request(
                                List.of(new Message("user", "hi", null, null, null)),
                                List.of(new Tool("bad name!", "d", Map.of())),
                                null,
                                null,
                                null)));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        new AgentTurn.Request(
                                List.of(
                                        new Message(
                                                "user",
                                                "hi",
                                                List.of(new ToolCall("1", "t", Map.of())),
                                                null,
                                                null)),
                                null,
                                null,
                                null,
                                null)));
        assertEquals(
                HttpStatus.PAYLOAD_TOO_LARGE,
                statusOf(
                        new AgentTurn.Request(
                                List.of(
                                        new Message(
                                                "user",
                                                "x".repeat(AgentTurnController.MAX_TOTAL_CHARS + 1),
                                                null,
                                                null,
                                                null)),
                                null,
                                null,
                                null,
                                null)));
    }

    @Test
    void aWellFormedRequestPassesValidation() {
        AgentTurnController.validate(request(null));
        AgentTurnController.validate(
                new AgentTurn.Request(
                        List.of(
                                new Message("user", "u", null, null, null),
                                new Message(
                                        "assistant",
                                        "",
                                        List.of(new ToolCall("c1", "read_file", Map.of())),
                                        null,
                                        null),
                                new Message("tool", "out", null, "c1", "read_file")),
                        null,
                        null,
                        null,
                        null));
    }

    // ---- fake entitlements -------------------------------------------------

    private static final class RecordingEntitlements implements FimCompletionEntitlements {
        RuntimeException rejectWith;
        int reserved;
        int settled;
        int abandoned;
        String settledModel;
        Long settledPrompt;
        Long settledCompletion;

        @Override
        public synchronized Lease reserve(int maxTokens) {
            if (rejectWith != null) {
                throw rejectWith;
            }
            reserved = maxTokens;
            return new Lease() {
                @Override
                public void settle(String model, Long promptTokens, Long completionTokens) {
                    synchronized (RecordingEntitlements.this) {
                        settled++;
                        settledModel = model;
                        settledPrompt = promptTokens;
                        settledCompletion = completionTokens;
                    }
                }

                @Override
                public void abandon() {
                    synchronized (RecordingEntitlements.this) {
                        abandoned++;
                    }
                }
            };
        }
    }
}
