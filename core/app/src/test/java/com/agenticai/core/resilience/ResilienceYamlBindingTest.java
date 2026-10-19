package com.agenticai.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the real application.yml into a plain Environment and builds the registry exactly as the context
 * would (same package: {@code build} is package-private), so a mistyped key or a default that does not
 * bind cannot hide behind a YAML string pin.
 */
class ResilienceYamlBindingTest {

    private static CircuitBreakerRegistry registryFromApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return ResilienceConfiguration.build(env, null, Clock.systemUTC());
    }

    @Test
    void breakersAreEnabledAndNoFamilyHasABulkheadByDefault() throws Exception {
        try (CircuitBreakerRegistry registry = registryFromApplicationYml()) {
            assertThat(registry.enabled()).isTrue();
            for (String family : List.of("hosted-llm:row:1", "sidecar", "e2b", "brave", "meta-graph", "web-search-tavily")) {
                ResilienceGuard guard = registry.guard(family);
                assertThat(guard.active()).as(family).isTrue();
                assertThat(guard.breaker()).as(family).isNotNull();
                // No bulkhead: holding far more concurrent calls than any plausible limit never sheds
                // (breaker stays closed because nothing is reported as a failure).
                for (int i = 0; i < 1_000; i++) {
                    guard.begin();
                }
            }
        }
    }
}
