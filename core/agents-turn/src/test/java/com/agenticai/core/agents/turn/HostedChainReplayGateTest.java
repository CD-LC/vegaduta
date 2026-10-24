package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.chat.UpstreamProviderException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * The hosted pool advances to the next rung when one fails before emitting a token. A tool-calling turn
 * emits no token while its tools run, so that test alone let a turn that had already sent an email be
 * re-run, email included, on the next rung. The gate adds: not after the turn ran tools that could act
 * a second time.
 */
class HostedChainReplayGateTest {

    private static final UUID AGENT = UUID.randomUUID();

    private static List<HostedChainLink> chain(int rungs) {
        return java.util.stream.IntStream.range(0, rungs)
                .mapToObj(i -> new HostedChainLink(mock(ChatClient.class), "row:" + i, "provider-" + i, "m", i, 0, 0, null, null))
                .toList();
    }

    private static UpstreamProviderException overloaded() {
        return new UpstreamProviderException(503, "HTTP 503: overloaded", "Busy.");
    }

    private static Flux<String> drive(
            List<HostedChainLink> chain, AtomicInteger rungsTried, java.util.function.Supplier<String> gate) {
        Function<ChatClient, Flux<String>> attempt = client -> {
            int rung = rungsTried.getAndIncrement();
            return rung == 0 ? Flux.error(overloaded()) : Flux.just("answered by rung " + rung);
        };
        return HostedChainFailover.attemptHostedChain(
                chain, 0, null, attempt, AGENT, "session-1", null, null, null, gate);
    }

    @Test
    void aRungThatFailedAfterToolsRanIsNotReplayedOnTheNextRung() {
        AtomicInteger tried = new AtomicInteger();

        UpstreamProviderException failure = assertThrows(
                UpstreamProviderException.class,
                () -> drive(chain(3), tried, () -> "send_email").collectList().block());

        assertEquals(503, failure.getUpstreamStatus());
        assertEquals(1, tried.get(), "the next rung would have sent the email a second time");
        assertEquals(false, failure instanceof HostedPoolExhaustedException);
    }

    @Test
    void theSameHoldsOnALoneRungWithASynchronousHealAvailable() {
        AtomicInteger tried = new AtomicInteger();
        AtomicInteger heals = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            tried.incrementAndGet();
            return Flux.error(overloaded());
        };

        assertThrows(
                UpstreamProviderException.class,
                () -> HostedChainFailover.attemptHostedChain(
                                chain(1), 0, null, attempt, AGENT, "s", null, null,
                                (link, failure) -> {
                                    heals.incrementAndGet();
                                    return link;
                                },
                                () -> "send_email")
                        .collectList()
                        .block());

        assertEquals(1, tried.get(), "the self-healed retry replays the turn too");
    }

    @Test
    void aTurnThatRanNothingOrOnlyReadsFailsOverAsBefore() {
        for (String gateAnswer : new String[] {null, ""}) {
            AtomicInteger tried = new AtomicInteger();

            List<String> out = drive(chain(3), tried, () -> gateAnswer).collectList().block();

            assertEquals(List.of("answered by rung 1"), out);
            assertEquals(2, tried.get());
        }
    }

    @Test
    void withNoGateTheChainBehavesAsItAlwaysDid() {
        AtomicInteger tried = new AtomicInteger();

        List<String> out = drive(chain(3), tried, null).collectList().block();

        assertEquals(List.of("answered by rung 1"), out);
    }

    @Test
    void anInputShapedFailureStillFailsFastWhateverTheGateSays() {
        UpstreamProviderException tooLong = new UpstreamProviderException(400, "HTTP 400: context length exceeded", "Shorten.");
        AtomicInteger tried = new AtomicInteger();

        UpstreamProviderException thrown = assertThrows(
                UpstreamProviderException.class,
                () -> HostedChainFailover.attemptHostedChain(
                                chain(3), 0, null,
                                client -> {
                                    tried.incrementAndGet();
                                    return Flux.error(tooLong);
                                },
                                AGENT, "s", null, null, null, () -> null)
                        .collectList()
                        .block());

        assertSame(tooLong, thrown);
        assertEquals(1, tried.get());
        assertInstanceOf(UpstreamProviderException.class, thrown);
    }
}
