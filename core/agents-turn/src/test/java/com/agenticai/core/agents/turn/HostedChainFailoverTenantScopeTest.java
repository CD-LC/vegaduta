package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The hosted-pool self-heal retry and every later rung run inside {@code
 * onErrorResume} / on {@code boundedElastic}, where the plain-ThreadLocal
 * {@link TenantContext} is absent. They must run with the tenant that was in
 * force when the chain was assembled, or the retried turn's advisors (metrics
 * attribution, content policy, governance) run tenantless.
 */
class HostedChainFailoverTenantScopeTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final TenantContext TENANT = TenantContext.of("tenant-a", "user-1", Set.of());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theSelfHealAndItsRetryRunWithTheOriginatingTenant() {
        ChatClient failing = mock(ChatClient.class);
        ChatClient healedClient = mock(ChatClient.class);
        List<HostedChainLink> chain = List.of(new HostedChainLink(failing, null, "primary", "m1", 0, 0, 0));
        HostedChainLink healedLink = new HostedChainLink(healedClient, null, "primary", "m2", 0, 0, 0);

        AtomicReference<Optional<TenantContext>> tenantDuringHeal = new AtomicReference<>();
        AtomicReference<Optional<TenantContext>> tenantDuringRetry = new AtomicReference<>();
        Function<ChatClient, Flux<String>> attempt = client -> {
            if (client == failing) {
                // Fail off the calling thread, the way a real provider error arrives.
                return Flux.<String>error(new UpstreamProviderException(429, "HTTP 429: busy", "busy"))
                    .subscribeOn(Schedulers.boundedElastic());
            }
            tenantDuringRetry.set(TenantContext.current());
            return Flux.just("healed-answer");
        };
        BiFunction<HostedChainLink, Throwable, HostedChainLink> heal = (link, error) -> {
            tenantDuringHeal.set(TenantContext.current());
            return healedLink;
        };

        TenantContext.set(TENANT);
        Flux<String> result = HostedChainFailover.attemptHostedChain(
            chain, 0, null, attempt, AGENT_ID, "s1", null, null, heal);
        TenantContext.clear();

        assertEquals(List.of("healed-answer"), result.collectList().block());
        assertEquals("tenant-a", tenantDuringHeal.get().orElseThrow().getTenantId());
        assertEquals("tenant-a", tenantDuringRetry.get().orElseThrow().getTenantId());
    }

    @Test
    void laterRungsAlsoRunWithTheOriginatingTenant() {
        ChatClient first = mock(ChatClient.class);
        ChatClient second = mock(ChatClient.class);
        List<HostedChainLink> chain = List.of(
            new HostedChainLink(first, null, "first", "m1", 0, 0, 0),
            new HostedChainLink(second, null, "second", "m2", 1, 0, 0));
        AtomicReference<Optional<TenantContext>> tenantOnSecondRung = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        Function<ChatClient, Flux<String>> attempt = client -> {
            attempts.incrementAndGet();
            if (client == first) {
                return Flux.<String>error(new UpstreamProviderException(429, "HTTP 429: busy", "busy"))
                    .subscribeOn(Schedulers.boundedElastic());
            }
            tenantOnSecondRung.set(TenantContext.current());
            return Flux.just("second-answer");
        };

        TenantContext.set(TENANT);
        Flux<String> result = HostedChainFailover.attemptHostedChain(
            chain, 0, null, attempt, AGENT_ID, "s1", null, null, null);
        TenantContext.clear();

        assertEquals(List.of("second-answer"), result.collectList().block());
        assertEquals(2, attempts.get());
        assertEquals("tenant-a", tenantOnSecondRung.get().orElseThrow().getTenantId());
    }

    @Test
    void noCapturedTenantMeansNothingIsImposed() {
        ChatClient only = mock(ChatClient.class);
        List<HostedChainLink> chain = List.of(new HostedChainLink(only, null, "only", "m1", 0, 0, 0));
        AtomicReference<Optional<TenantContext>> seen = new AtomicReference<>();

        Flux<String> result = HostedChainFailover.attemptHostedChain(chain, 0, null, client -> {
            seen.set(TenantContext.current());
            return Flux.just("ok");
        }, AGENT_ID, "s1", null, null, null);

        assertEquals(List.of("ok"), result.collectList().block());
        assertEquals(Optional.empty(), seen.get());
    }
}
