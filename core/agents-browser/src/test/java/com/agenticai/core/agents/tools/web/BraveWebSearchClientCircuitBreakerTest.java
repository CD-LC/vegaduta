package com.agenticai.core.agents.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.resilience.CircuitBreaker;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.CircuitBreakerSettings;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An open {@code brave} circuit answers with the tool's existing person-readable {@link
 * WebSearchException} and never touches the network (so this test needs none).
 */
class BraveWebSearchClientCircuitBreakerTest {

    @AfterEach
    void uninstall() {
        CircuitBreakerRegistry.install(null);
    }

    @Test
    void anOpenCircuitShedsWithAWebSearchExceptionNotAnUnexpectedOne() {
        CircuitBreakerSettings settings =
                new CircuitBreakerSettings(
                        1,
                        0,
                        10,
                        10,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        1,
                        Duration.ofSeconds(30));
        CircuitBreakerRegistry registry =
                new CircuitBreakerRegistry(
                        true, f -> settings, f -> 0, Duration.ZERO, Clock.systemUTC(), null);
        CircuitBreakerRegistry.install(registry);
        registry.breaker("brave")
                .tryAcquire()
                .failure(new IOException("down")); // opens (threshold 1)
        assertThat(registry.breaker("brave").state()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> new BraveWebSearchClient().search("vegaduta", 3, "key"))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void inputValidationIsUnchangedWithTheBreakerOff() {
        // shared() is the disabled registry here: behaviour is exactly the pre-breaker one.
        assertThatThrownBy(() -> new BraveWebSearchClient().search("  ", 3, "key"))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("no search query");
        assertThatThrownBy(() -> new BraveWebSearchClient().search("q", 3, ""))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("not configured");
    }
}
