package com.agenticai.core.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the resilience primitives. The CODE defaults are inert (both flags false, so a slim context
 * gets {@link ResilienceGuard#NOOP} from {@link CircuitBreakerRegistry#guard(String)}), but
 * application.yml turns {@code app.resilience.circuit-breaker.enabled} ON and leaves {@code
 * app.resilience.bulkhead.enabled} OFF.
 *
 * <p>Settings are read with a {@link Binder} (not {@code @Value}) because per-dependency overrides
 * live under {@code app.resilience.circuit-breaker.instances.<family>.*} and are optional; every
 * key repeats its default in code so a slim test context behaves identically to application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfiguration {

    static final String CB_PREFIX = "app.resilience.circuit-breaker";
    static final String BULKHEAD_PREFIX = "app.resilience.bulkhead";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            Environment env, ObjectProvider<MeterRegistry> meters) {
        CircuitBreakerRegistry registry =
                build(env, meters.getIfAvailable(), MonotonicClock.INSTANCE);
        // Self-constructed clients (e.g. BraveWebSearchClient) cannot be injected; they read this.
        CircuitBreakerRegistry.install(registry);
        return registry;
    }

    /**
     * Package-private so a test can build the exact registry the context would, from a plain
     * Environment.
     */
    static CircuitBreakerRegistry build(Environment env, MeterRegistry meters, Clock clock) {
        Binder binder = Binder.get(env);
        boolean enabled =
                binder.bind(CB_PREFIX + ".enabled", Bindable.of(Boolean.class)).orElse(false);
        CircuitBreakerSettings globalSettings =
                CircuitBreakerSettings.bind(binder, CB_PREFIX, CircuitBreakerSettings.defaults());
        boolean bulkheadEnabled =
                binder.bind(BULKHEAD_PREFIX + ".enabled", Bindable.of(Boolean.class)).orElse(false);
        Duration bulkheadWait =
                binder.bind(BULKHEAD_PREFIX + ".max-wait", Bindable.of(Duration.class))
                        .orElse(Duration.ZERO);
        return new CircuitBreakerRegistry(
                enabled,
                family ->
                        CircuitBreakerSettings.bind(
                                binder, CB_PREFIX + ".instances." + family, globalSettings),
                family ->
                        bulkheadEnabled
                                ? binder.bind(
                                                BULKHEAD_PREFIX
                                                        + ".instances."
                                                        + family
                                                        + ".max-concurrent",
                                                Bindable.of(Integer.class))
                                        .orElse(0)
                                : 0,
                bulkheadWait,
                clock,
                meters);
    }

    @Bean
    public ShutdownDrainAdvisor shutdownDrainAdvisor(
            Environment env, ObjectProvider<ApplicationAvailability> availability) {
        Binder binder = Binder.get(env);
        int drain =
                binder.bind("app.shutdown.drain-seconds", Bindable.of(Integer.class)).orElse(30);
        int grace =
                binder.bind("app.shutdown.container-grace-seconds", Bindable.of(Integer.class))
                        .orElse(0);
        String mode = binder.bind("server.shutdown", Bindable.of(String.class)).orElse("graceful");
        return new ShutdownDrainAdvisor(availability.getIfAvailable(), drain, grace, mode);
    }
}
