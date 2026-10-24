package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.TurnLifecycleStepEvents;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.UpstreamProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

/**
 * The translation from the turn's own state to the lifecycle events' fields, and the hosted chain's failovers told
 * from outside the chain.
 */
class TurnLifecycleEventsTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> sent(AgentStepEventEmitter emitter) throws Exception {
        emitter.complete();
        List<JsonNode> out = new ArrayList<>();
        for (String chunk : emitter.asFlux().collectList().block()) {
            out.add(JSON.readTree(chunk.substring(AgentStepEventEmitter.STEP_EVENT_MARKER.length())));
        }
        return out;
    }

    @Test
    void everyReplyKindHasOneFinishReasonAndTheListIsComplete() {
        List<String> reasons = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            reasons.add(TurnLifecycleEvents.finishReasonOf(kind));
        }

        assertEquals(List.of("stop", "length", "safety", "malformed_tool_call", "empty", "interrupted"), reasons);
        assertTrue(TurnLifecycleStepEvents.FINISH_REASONS.containsAll(reasons));
        assertEquals("stop", TurnLifecycleEvents.finishReasonOf(null));
    }

    @Test
    void failuresAreNamedByACodeNotByTheirWording() {
        assertEquals("rate_limited", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(429, "slow down", "Busy.")));
        assertEquals("timeout", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(408, "timed out", "Slow.")));
        assertEquals("timeout", TurnLifecycleEvents.reasonOf(new RuntimeException("wrapped", new TimeoutException("x"))));
        assertEquals("auth_failed", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(401, "key", "Key.")));
        assertEquals("billing", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(402, "pay", "Pay.")));
        assertEquals("model_not_found", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(404, "gone", "Gone.")));
        assertEquals("provider_unavailable", TurnLifecycleEvents.reasonOf(new UpstreamProviderException(503, "down", "Down.")));
        assertEquals("provider_unavailable", TurnLifecycleEvents.reasonOf(new TransientAiException("overloaded")));
        assertEquals("provider_error", TurnLifecycleEvents.reasonOf(new IllegalStateException("odd")));
        assertEquals("provider_error", TurnLifecycleEvents.reasonOf(null));
    }

    @Test
    void theRawWordingOfAFailureNeverReachesTheEvent() throws Exception {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();

        TurnLifecycleEvents.failover(emitter, "m2", new UpstreamProviderException(503, "key hunter2-token rejected", "Busy."));
        TurnLifecycleEvents.callRetry(emitter, 1, 3, new UpstreamProviderException(429, "key hunter2-token busy", "Busy."));

        for (JsonNode event : sent(emitter)) {
            assertTrue(!event.toString().contains("hunter2-token"), event.toString());
        }
    }

    @Test
    void aPassBeforeTheRetryNumberCountsFromTheSecondTry() throws Exception {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();

        TurnLifecycleEvents.callRetry(emitter, 1, 3, new TransientAiException("503"));
        TurnLifecycleEvents.toolCallRetry(emitter, 0, 1);
        TurnLifecycleEvents.finishPass(emitter, true, 0, 4);
        TurnLifecycleEvents.finishPass(emitter, false, 0, 1);
        TurnLifecycleEvents.unfinishedStepPass(emitter, 2, 8);
        TurnLifecycleEvents.approvalContinuation(emitter);

        List<JsonNode> e = sent(emitter);
        assertEquals(2, e.get(0).path("attempt").asInt());
        assertEquals(3, e.get(0).path("maxAttempts").asInt());
        assertEquals(2, e.get(1).path("attempt").asInt());
        assertEquals(2, e.get(1).path("maxAttempts").asInt());
        assertEquals("malformed_tool_call", e.get(1).path("reason").asText());
        assertEquals("continuation", e.get(2).path("type").asText());
        assertEquals(1, e.get(2).path("pass").asInt());
        assertEquals(4, e.get(2).path("maxPasses").asInt());
        assertEquals("cut_off", e.get(2).path("reason").asText());
        assertEquals("retry", e.get(3).path("type").asText());
        assertEquals("unfinished_step", e.get(4).path("reason").asText());
        assertEquals(2, e.get(4).path("pass").asInt());
        assertEquals("approval", e.get(5).path("reason").asText());
    }

    // ------------------------------------------------------------------ the hosted chain

    private static HostedChainLink link(String model) {
        return new HostedChainLink(mock(ChatClient.class), "row:" + model, "provider-" + model, model, 0, 0, 0, null, null);
    }

    @Test
    void movingToTheNextRungAnnouncesTheModelItMovedToAndWhy() throws Exception {
        List<HostedChainLink> chain = List.of(link("model-1"), link("model-2"), link("model-3"));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleEvents.HostedChain events = new TurnLifecycleEvents.HostedChain(emitter, chain);
        AtomicInteger rung = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt =
                client ->
                        rung.getAndIncrement() < 2
                                ? Flux.error(new UpstreamProviderException(rung.get() == 1 ? 429 : 503, "failed", "Failed."))
                                : Flux.just("answer from the third");

        List<String> out =
                HostedChainFailover.attemptHostedChain(
                                chain, 0, null, events.attempt(attempt), AGENT, "s1", null, events.onRungFailure(null), null)
                        .collectList()
                        .block();

        List<JsonNode> sent = sent(emitter);
        assertEquals(List.of("answer from the third"), out);
        assertEquals(2, sent.size(), sent.toString());
        assertEquals("failover", sent.get(0).path("type").asText());
        assertEquals("model-2", sent.get(0).path("toModel").asText());
        assertEquals("rate_limited", sent.get(0).path("reason").asText());
        assertEquals("model-3", sent.get(1).path("toModel").asText());
        assertEquals("provider_unavailable", sent.get(1).path("reason").asText());
    }

    @Test
    void theFirstRungIsNotAFailoverAndTheListenerStillRuns() throws Exception {
        List<HostedChainLink> chain = List.of(link("model-1"), link("model-2"));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleEvents.HostedChain events = new TurnLifecycleEvents.HostedChain(emitter, chain);
        AtomicInteger listened = new AtomicInteger();
        AtomicInteger rung = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt =
                client ->
                        rung.getAndIncrement() == 0
                                ? Flux.error(new UpstreamProviderException(503, "failed", "Failed."))
                                : Flux.just("ok");

        HostedChainFailover.attemptHostedChain(
                        chain,
                        0,
                        null,
                        events.attempt(attempt),
                        AGENT,
                        "s1",
                        null,
                        events.onRungFailure((l, e) -> listened.incrementAndGet()),
                        null)
                .collectList()
                .block();

        assertEquals(1, listened.get(), "the self-heal listener the chain was given still hears about the failure");
        assertEquals(1, sent(emitter).size());
    }

    @Test
    void aRunOfTheChainThatSucceedsFirstTimeSendsNothingAndASecondRunStartsAgain() throws Exception {
        List<HostedChainLink> chain = List.of(link("model-1"), link("model-2"));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleEvents.HostedChain events = new TurnLifecycleEvents.HostedChain(emitter, chain);

        for (int run = 0; run < 2; run++) {
            HostedChainFailover.attemptHostedChain(
                            chain, 0, null, events.attempt(client -> Flux.just("ok")), AGENT, "s1", null, events.onRungFailure(null), null)
                    .collectList()
                    .block();
        }

        assertTrue(sent(emitter).isEmpty(), "the second run's first attempt is a first attempt, not a failover");
    }

    @Test
    void withoutStepEventsTheChainIsHandedBackUntouched() {
        List<HostedChainLink> chain = List.of(link("model-1"));
        TurnLifecycleEvents.HostedChain events =
                new TurnLifecycleEvents.HostedChain(AgentStepEventEmitter.disabled(), chain);
        Function<ChatClient, Flux<String>> attempt = client -> Flux.just("ok");
        java.util.function.BiConsumer<HostedChainLink, Throwable> listener = (l, e) -> {};

        assertTrue(events.attempt(attempt) == attempt);
        assertTrue(events.onRungFailure(listener) == listener);
    }
}
