package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class PlatformPoolWarmerTest {

    private PlatformHostedLlmProviderService pool;
    private HostedLlmChatClientFactory clients;
    private PlatformHostedLlmProvider row;

    @BeforeEach
    void setUp() {
        pool = mock(PlatformHostedLlmProviderService.class);
        clients = mock(HostedLlmChatClientFactory.class);
        row = mock(PlatformHostedLlmProvider.class);
        when(row.getModel()).thenReturn("llama3.2");
    }

    private PlatformPoolWarmer warmer(boolean enabled, long intervalSeconds, Instant now) {
        PlatformPoolWarmer warmer = new PlatformPoolWarmer(pool, clients, enabled, intervalSeconds);
        warmer.setClock(Clock.fixed(now, ZoneOffset.UTC));
        return warmer;
    }

    @Test
    void warmsTheTopRowWithAOneTokenCallFromTheTurnFactory() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(pool.resolveTopPriorityEnabled())
                .thenReturn(Optional.of(new HostedProviderSelection(row, "key")));
        when(clients.create(eq(row), eq("key"), eq(1), anyDouble(), anyDouble()))
                .thenReturn(client);

        assertTrue(warmer(true, 240, Instant.EPOCH).warmNow().isPresent());

        verify(clients).create(eq(row), eq("key"), eq(1), anyDouble(), anyDouble());
        verify(client.prompt().advisors(any(java.util.function.Consumer.class)).user("hi").call())
                .content();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProbeIsMarkedSoItIsNeverBookedToTheMembersTenant() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(pool.resolveTopPriorityEnabled())
                .thenReturn(Optional.of(new HostedProviderSelection(row, "key")));
        when(clients.create(eq(row), eq("key"), eq(1), anyDouble(), anyDouble()))
                .thenReturn(client);

        warmer(true, 240, Instant.EPOCH).warmNow();

        org.mockito.ArgumentCaptor<java.util.function.Consumer<ChatClient.AdvisorSpec>> marker =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(client.prompt()).advisors(marker.capture());
        ChatClient.AdvisorSpec spec = mock(ChatClient.AdvisorSpec.class);
        marker.getValue().accept(spec);
        verify(spec).param(LlmMetricsAdvisor.SKIP_OBSERVATION_PARAM, true);
    }

    @Test
    void anEmptyPoolWarmsNothing() {
        when(pool.resolveTopPriorityEnabled()).thenReturn(Optional.empty());

        assertTrue(warmer(true, 240, Instant.EPOCH).warmNow().isEmpty());

        verify(clients, never()).create(any(), anyString(), anyInt(), anyDouble(), anyDouble());
    }

    @Test
    void aFailingModelNeverReachesTheCaller() {
        when(pool.resolveTopPriorityEnabled())
                .thenReturn(Optional.of(new HostedProviderSelection(row, "key")));
        when(clients.create(any(), any(), anyInt(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("cold start timed out"));

        assertTrue(warmer(true, 240, Instant.EPOCH).warmNow().isPresent());
    }

    @Test
    void anUnreadablePoolNeverReachesTheCaller() {
        when(pool.resolveTopPriorityEnabled()).thenThrow(new IllegalStateException("db down"));

        assertTrue(warmer(true, 240, Instant.EPOCH).warmNow().isEmpty());
    }

    @Test
    void oneWarmUpPerIntervalAndNeverTwoAtOnce() {
        Instant t0 = Instant.parse("2026-10-21T04:00:00Z");
        PlatformPoolWarmer warmer = warmer(true, 240, t0);

        assertTrue(warmer.claim(), "first sign-in starts a warm-up");
        assertFalse(warmer.claim(), "a second sign-in while it runs does not");

        when(pool.resolveTopPriorityEnabled()).thenReturn(Optional.empty());
        warmer.warmAndRelease();
        assertFalse(warmer.claim(), "finished, but still within the interval");

        warmer.setClock(Clock.fixed(t0.plusSeconds(241), ZoneOffset.UTC));
        assertTrue(warmer.claim(), "after the interval the next sign-in warms again");
    }

    @Test
    void switchedOffItNeverStarts() {
        PlatformPoolWarmer warmer = warmer(false, 240, Instant.EPOCH);

        assertFalse(warmer.claim());
        assertFalse(warmer.warmAsync());
        verify(pool, never()).resolveTopPriorityEnabled();
    }

    @Test
    void theControllerAnswersAtOnceWithWhetherItStarted() {
        PlatformPoolWarmer warmer = mock(PlatformPoolWarmer.class);
        when(warmer.warmAsync()).thenReturn(true, false);
        PlatformPoolWarmController controller = new PlatformPoolWarmController(warmer);

        assertEquals(202, controller.warm().getStatusCode().value());
        assertEquals(Boolean.FALSE, controller.warm().getBody().get("started"));
    }
}
