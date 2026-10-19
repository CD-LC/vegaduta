package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.agenticai.core.chat.gateway.GatewayClientMemo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A config typo must degrade (warn + skip), never fail context startup. */
class ChatHotpathConfigBootSafetyTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noRegistry() {
        return mock(ObjectProvider.class);
    }

    @Test
    void unknownProviderInClientCacheListDoesNotFailBoot() {
        ChatHotpathConfig config = new ChatHotpathConfig();
        assertThatCode(() -> config.gatewayClientMemo(
                noRegistry(), Duration.ofSeconds(30), Duration.ofSeconds(30), "ANTHROPIC, NOT_A_PROVIDER"))
            .doesNotThrowAnyException();
    }

    @Test
    void emptyAndDefaultListsStillProduceAMemo() {
        ChatHotpathConfig config = new ChatHotpathConfig();
        GatewayClientMemo memo = config.gatewayClientMemo(noRegistry(), Duration.ZERO, Duration.ZERO, "");
        assertThat(memo).isNotNull();
    }

    @Test
    void ttftTimerPublishesNoCodeConfiguredHistogram() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ChatTtftRecorder(registry);
        // Buckets are opt-in via the latency-histograms profile, so the timer itself must not force them.
        registry.getMeters().forEach(m -> {
            if (m.getId().getName().equals("agentic.chat.ttft")) {
                var cfg = ((io.micrometer.core.instrument.Timer) m).takeSnapshot().histogramCounts();
                assertThat(cfg).isEmpty();
            }
        });
    }
}
