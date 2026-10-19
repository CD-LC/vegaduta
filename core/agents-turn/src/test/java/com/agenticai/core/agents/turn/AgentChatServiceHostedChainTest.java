package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * P3 (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §6 + Appendix A) - the
 * platform-hosted pool's fallback advancement, i.e. {@link
 * AgentChatService#attemptHostedChain}. Drives the pure reactive control flow
 * directly with a stub {@code Function<ChatClient, Flux<String>>} standing in
 * for one dispatched turn, so the assertions pin exactly the discipline the
 * spec asks for - mirroring {@code callWithProviderFallback}: fallback ADVANCES
 * on a retryable provider failure, STOPS on the first success, STOPS at the
 * chain end (surfacing a pool-level error that carries the original primary one
 * as its cause), never switches once tokens have been emitted, and never
 * retries a non-provider error - and nothing more.
 *
 * <p>Extended for the hosted-pool failover hardening: an input-shaped failure
 * (content policy / context length) must fail FAST rather than be replayed
 * against every provider, and a ONE-row pool - the live production shape - must
 * still end in an honest pool-level message rather than a raw provider one.
 *
 * <p>The {@link ChatClient}s in the chain are opaque markers here (the stub
 * routes on their list index, never calls them), so no Spring AI graph or
 * provider credentials are needed - the same "test the resolution/routing
 * logic, not the upstream call" seam the provider-side chain test uses.
 */
class AgentChatServiceHostedChainTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION_ID = "session-1";

    private static UpstreamProviderException upstream(String reason) {
        return new UpstreamProviderException(429, "HTTP 429: " + reason, "The service is busy, try again.");
    }

    /** A three-provider chain of distinct, opaque marker clients (Groq -> Gemini -> Cerebras). */
    private static List<ChatClient> threeProviderChain() {
        return List.of(mock(ChatClient.class), mock(ChatClient.class), mock(ChatClient.class));
    }

    @Test
    void stopsOnFirstProviderWhenItSucceeds() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            attempts.incrementAndGet();
            return Flux.just("answer-from-", "primary");
        };

        List<String> out = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID)
            .collectList().block();

        assertEquals(List.of("answer-from-", "primary"), out);
        assertEquals(1, attempts.get(), "a succeeding primary must never touch the rest of the chain");
    }

    @Test
    void advancesToNextProviderOnUpstreamFailureThenSucceeds() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            int idx = chain.indexOf(client);
            attempts.incrementAndGet();
            // Primary 429s (nothing emitted); second provider answers.
            return idx == 0 ? Flux.error(upstream("primary down")) : Flux.just("recovered");
        };

        List<String> out = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID)
            .collectList().block();

        assertEquals(List.of("recovered"), out);
        assertEquals(2, attempts.get(), "should advance exactly once, to the second provider");
    }

    @Test
    void advancesAcrossMultipleFailuresUntilOneSucceeds() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            int idx = chain.indexOf(client);
            attempts.incrementAndGet();
            return idx < 2 ? Flux.error(upstream("provider " + idx + " down")) : Flux.just("third-time-lucky");
        };

        List<String> out = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID)
            .collectList().block();

        assertEquals(List.of("third-time-lucky"), out);
        assertEquals(3, attempts.get());
    }

    @Test
    void exhaustsChainAndSurfacesAPoolLevelErrorCarryingTheOriginalPrimaryOne() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        UpstreamProviderException primaryError = upstream("PRIMARY error - this one wins");
        Function<ChatClient, Flux<String>> attempt = client -> {
            int idx = chain.indexOf(client);
            attempts.incrementAndGet();
            return Flux.error(idx == 0 ? primaryError : upstream("provider " + idx + " error"));
        };

        Flux<String> flux = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> flux.collectList().block());

        // Every provider was tried (bounded at chain length)...
        assertEquals(3, attempts.get());
        // ...and what surfaces is a pool-level error, not any one provider's
        // raw message - see HostedPoolExhaustedException for why a hosted-pool
        // user must not be told to "choose a different model". The FIRST
        // provider's error is still the one preserved as the cause (the
        // original-error-wins discipline), not the last.
        assertInstanceOf(HostedPoolExhaustedException.class, thrown);
        assertSame(primaryError, thrown.getCause());
    }

    @Test
    void failsFastWithoutTouchingTheRestOfTheChainOnAUserInputError() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        // A content-policy refusal: every provider in the pool would refuse the
        // same input, so replaying it N times only delays the same message.
        UpstreamProviderException refusal = new UpstreamProviderException(
            400, "The LLM provider rejected the request (HTTP 400): content_policy_violation",
            "The AI provider declined to respond because the request tripped its content policy. Try rephrasing it."
        );
        Function<ChatClient, Flux<String>> attempt = client -> {
            attempts.incrementAndGet();
            return Flux.error(refusal);
        };

        Flux<String> flux = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> flux.collectList().block());

        assertEquals(1, attempts.get(), "an input-shaped refusal must not be replayed against the rest of the pool");
        assertSame(refusal, thrown, "and its own actionable message must reach the user unwrapped");
    }

    @Test
    void aSingleProviderPoolStillProducesAnHonestPoolLevelError() {
        // The live production shape: exactly ONE enabled row, whose model id the
        // upstream rejects with a 404. Before this hardening the chain wrapper
        // was skipped entirely for a one-row pool and the raw provider message
        // ("choose a different model for this agent") reached a user who cannot.
        List<ChatClient> chain = List.of(mock(ChatClient.class));
        Function<ChatClient, Flux<String>> attempt = client -> Flux.error(new UpstreamProviderException(
            404, "The LLM provider rejected the request (HTTP 404): models/whatever is not found",
            "The selected AI model isn't available from this provider anymore. Choose a different model for this agent."
        ));

        Flux<String> flux = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> flux.collectList().block());

        assertInstanceOf(HostedPoolExhaustedException.class, thrown);
        HostedPoolExhaustedException exhausted = (HostedPoolExhaustedException) thrown;
        assertEquals(503, exhausted.getUpstreamStatus());
        assertFalse(exhausted.getConsumerMessage().contains("Choose a different model"));
    }

    @Test
    void neverAdvancesOnceTokensHaveBeenEmitted() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            attempts.incrementAndGet();
            // Primary emits a token, THEN dies - switching now would interleave
            // two providers' output into one already-committed response.
            return Flux.concat(Flux.just("partial"), Flux.error(upstream("died mid-stream")));
        };

        Flux<String> flux = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID);
        assertThrows(RuntimeException.class, () -> flux.collectList().block());
        assertEquals(1, attempts.get(), "a mid-stream failure must not fall through to another provider");
    }

    @Test
    void neverRetriesANonProviderError() {
        List<ChatClient> chain = threeProviderChain();
        AtomicInteger attempts = new AtomicInteger();
        GuardrailViolationException guardrail = new GuardrailViolationException("some_tool", "blocked by guardrail");
        Function<ChatClient, Flux<String>> attempt = client -> {
            attempts.incrementAndGet();
            return Flux.error(guardrail);
        };

        Flux<String> flux = HostedChainFailover.attemptHostedChain(chain, 0, null, attempt, AGENT_ID, SESSION_ID);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> flux.collectList().block());

        assertSame(guardrail, thrown, "a guardrail failure isn't a provider outage - switching can't fix it");
        assertEquals(1, attempts.get());
    }
}
