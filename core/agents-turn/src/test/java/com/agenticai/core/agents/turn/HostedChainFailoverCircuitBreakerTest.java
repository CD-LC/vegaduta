package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.agents.hosted.HostedProviderHealth;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.CircuitBreakerSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * The per-row circuit breaker inside the hosted-pool failover chain, driven with fake rows (marker
 * clients, scripted answers) and a manual clock. Flag off must be exactly today's behaviour.
 */
class HostedChainFailoverCircuitBreakerTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private final ChatClient rowA = mock(ChatClient.class);
    private final ChatClient rowB = mock(ChatClient.class);
    private final Map<ChatClient, AtomicInteger> attempts = new HashMap<>();
    private final Map<ChatClient, Function<ChatClient, Flux<String>>> scripts = new HashMap<>();

    private final List<HostedChainLink> twoRows =
            List.of(
                    new HostedChainLink(rowA, "row:1", "A", "m1", 0, 0, 0),
                    new HostedChainLink(rowB, "row:2", "B", "m2", 1, 0, 0));
    private final List<HostedChainLink> oneRow =
            List.of(new HostedChainLink(rowA, "row:1", "A", "m1", 0, 0, 0));

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(5_000_000L);

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }

    private HostedProviderHealth healthWithBreaker(boolean enabled, int consecutiveThreshold) {
        CircuitBreakerSettings settings =
                new CircuitBreakerSettings(
                        consecutiveThreshold,
                        0,
                        10,
                        10,
                        Duration.ofSeconds(60),
                        OPEN_FOR,
                        1,
                        Duration.ofSeconds(30));
        CircuitBreakerRegistry registry =
                new CircuitBreakerRegistry(
                        enabled, family -> settings, family -> 0, Duration.ZERO, clock, null);
        HostedProviderHealth health = new HostedProviderHealth(1000, 120, 180);
        health.setCircuitBreakerRegistry(registry);
        registryUnderTest = registry;
        return health;
    }

    private CircuitBreakerRegistry registryUnderTest;

    private Function<ChatClient, Flux<String>> attemptFn() {
        return client -> {
            attempts.computeIfAbsent(client, c -> new AtomicInteger()).incrementAndGet();
            return scripts.get(client).apply(client);
        };
    }

    private int attemptsOn(ChatClient client) {
        AtomicInteger n = attempts.get(client);
        return n == null ? 0 : n.get();
    }

    private static Flux<String> providerDown(ChatClient ignored) {
        return Flux.error(new UpstreamProviderException(503, "HTTP 503: provider down", "down"));
    }

    private static Flux<String> ok(ChatClient ignored) {
        return Flux.just("answer");
    }

    private List<String> run(List<HostedChainLink> chain, HostedProviderHealth health) {
        return HostedChainFailover.attemptHostedChain(
                        chain, 0, null, attemptFn(), AGENT, "s1", health, null, null)
                .collectList()
                .block();
    }

    @Test
    void flagOffEveryTurnStillTriesTheFailingRowFirstExactlyAsToday() {
        HostedProviderHealth health = healthWithBreaker(false, 2);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        for (int i = 0; i < 10; i++) {
            assertThat(run(twoRows, health)).containsExactly("answer");
        }
        assertThat(attemptsOn(rowA)).isEqualTo(10); // never skipped
        assertThat(attemptsOn(rowB)).isEqualTo(10);
    }

    @Test
    void noRegistryAtAllIsTheSameAsFlagOff() {
        HostedProviderHealth health =
                new HostedProviderHealth(1000, 120, 180); // as every existing test builds it
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        for (int i = 0; i < 5; i++) {
            assertThat(run(twoRows, health)).containsExactly("answer");
        }
        assertThat(attemptsOn(rowA)).isEqualTo(5);
    }

    @Test
    void anOpenBreakerSkipsTheRowToTheNextLikeAFailedRowWithoutCallingIt() {
        HostedProviderHealth health = healthWithBreaker(true, 2);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);

        assertThat(run(twoRows, health)).containsExactly("answer");
        assertThat(run(twoRows, health))
                .containsExactly("answer"); // second failure opens row A's breaker
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(attemptsOn(rowA)).isEqualTo(2);

        for (int i = 0; i < 5; i++) {
            assertThat(run(twoRows, health)).containsExactly("answer"); // user still gets an answer
        }
        assertThat(attemptsOn(rowA)).isEqualTo(2); // not called while open
        assertThat(attemptsOn(rowB)).isEqualTo(7);
        assertThat(registryUnderTest.breaker("hosted-llm:row:2").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theLastRowIsAlwaysAttemptedEvenWithItsBreakerOpen() {
        HostedProviderHealth health = healthWithBreaker(true, 2);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> run(oneRow, health))
                    .isInstanceOf(HostedPoolExhaustedException.class);
        }
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.OPEN);
        // a single-row pool must keep being tried, and a recovered provider must be reachable
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::ok);
        assertThat(run(oneRow, health)).containsExactly("answer");
        assertThat(attemptsOn(rowA)).isEqualTo(3);
    }

    @Test
    void whenEveryRowIsOpenOnlyTheLastIsTried() {
        HostedProviderHealth health = healthWithBreaker(true, 1);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::providerDown);
        assertThatThrownBy(() -> run(twoRows, health))
                .isInstanceOf(HostedPoolExhaustedException.class);
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registryUnderTest.breaker("hosted-llm:row:2").state())
                .isEqualTo(CircuitBreaker.State.OPEN);
        int aBefore = attemptsOn(rowA);
        int bBefore = attemptsOn(rowB);

        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        assertThat(run(twoRows, health)).containsExactly("answer");
        assertThat(attemptsOn(rowA)).isEqualTo(aBefore); // skipped
        assertThat(attemptsOn(rowB)).isEqualTo(bBefore + 1); // last resort, still attempted
    }

    @Test
    void aRecoveredRowIsProbedAfterTheOpenDurationAndTheBreakerCloses() {
        HostedProviderHealth health = healthWithBreaker(true, 1);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        run(twoRows, health);
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(OPEN_FOR);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::ok); // provider is back
        int aBefore = attemptsOn(rowA);
        assertThat(run(twoRows, health)).containsExactly("answer");
        assertThat(attemptsOn(rowA)).isEqualTo(aBefore + 1); // the half-open probe went to row A
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailingProbeReopensTheBreakerAndFallsOverToTheNextRow() {
        HostedProviderHealth health = healthWithBreaker(true, 1);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        run(twoRows, health);
        clock.advance(OPEN_FOR);
        assertThat(run(twoRows, health)).containsExactly("answer"); // probe on A fails, B answers
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.OPEN);
        int aAfter = attemptsOn(rowA);
        run(twoRows, health);
        assertThat(attemptsOn(rowA)).isEqualTo(aAfter); // shed again for a fresh open period
    }

    @Test
    void inputShapedRefusalsNeverCountAgainstTheRow() {
        HostedProviderHealth health = healthWithBreaker(true, 2);
        scripts.put(
                rowA,
                c ->
                        Flux.error(
                                new UpstreamProviderException(
                                        400, "content_policy_violation", "no")));
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> run(twoRows, health))
                    .isInstanceOf(UpstreamProviderException.class);
        }
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(attemptsOn(rowA)).isEqualTo(6);
        assertThat(attemptsOn(rowB))
                .isZero(); // fail fast: another provider would refuse it the same way
    }

    @Test
    void requestShapedBadRequestsFailOverButNeverTripTheSharedRowBreaker() {
        HostedProviderHealth health = healthWithBreaker(true, 2);
        scripts.put(
                rowA,
                c ->
                        Flux.error(
                                new UpstreamProviderException(
                                        400, "HTTP 400: bad tool schema", "no")));
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        for (int i = 0; i < 6; i++) {
            assertThat(run(twoRows, health))
                    .containsExactly("answer"); // 400 is still retryable: next row serves
        }
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(attemptsOn(rowA)).isEqualTo(6);
    }

    @Test
    void aMidStreamFailureAfterTheFirstTokenIsASuccessForTheBreaker() {
        HostedProviderHealth health = healthWithBreaker(true, 1);
        scripts.put(
                rowA,
                c ->
                        Flux.concat(
                                Flux.just("partial"),
                                Flux.<String>error(
                                        new UpstreamProviderException(503, "HTTP 503", "x"))));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> run(twoRows, health))
                    .isInstanceOf(UpstreamProviderException.class);
        }
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aCancelledTurnDoesNotLeakAHalfOpenProbeSlot() {
        HostedProviderHealth health = healthWithBreaker(true, 1);
        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::providerDown);
        scripts.put(rowB, HostedChainFailoverCircuitBreakerTest::ok);
        run(twoRows, health);
        clock.advance(OPEN_FOR);

        // the probe turn is cancelled before the provider produced anything
        scripts.put(rowA, c -> Flux.never());
        HostedChainFailover.attemptHostedChain(
                        twoRows, 0, null, attemptFn(), AGENT, "s1", health, null, null)
                .subscribe()
                .dispose();

        scripts.put(rowA, HostedChainFailoverCircuitBreakerTest::ok);
        int aBefore = attemptsOn(rowA);
        assertThat(run(twoRows, health)).containsExactly("answer");
        assertThat(attemptsOn(rowA))
                .isEqualTo(aBefore + 1); // slot was freed, so the next probe was admitted
        assertThat(registryUnderTest.breaker("hosted-llm:row:1").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
