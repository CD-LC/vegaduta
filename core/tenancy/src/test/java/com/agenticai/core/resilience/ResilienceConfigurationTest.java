package com.agenticai.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** Config binding for the registry, the per-dependency timeouts and the shutdown advisor. */
class ResilienceConfigurationTest {

    private final ManualClock clock = new ManualClock();

    @AfterEach
    void uninstall() {
        CircuitBreakerRegistry.install(null);
    }

    @Test
    void defaultsAreOffSoNothingChanges() {
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(new MockEnvironment(), null, clock);
        assertThat(registry.enabled()).isFalse();
        assertThat(registry.guard("brave")).isSameAs(ResilienceGuard.NOOP);
        assertThat(registry.guard("hosted-llm:row:1")).isSameAs(ResilienceGuard.NOOP);
    }

    @Test
    void enabledFlagHandsOutRealGuardsWithDocumentedDefaults() {
        MockEnvironment env = new MockEnvironment().withProperty("app.resilience.circuit-breaker.enabled", "true");
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(env, null, clock);
        assertThat(registry.enabled()).isTrue();
        ResilienceGuard guard = registry.guard("brave");
        assertThat(guard.active()).isTrue();
        assertThat(guard.breaker().settings()).isEqualTo(CircuitBreakerSettings.defaults());
        assertThat(registry.guard("brave")).isSameAs(guard); // cached
    }

    @Test
    void globalAndPerFamilyOverridesBind() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.resilience.circuit-breaker.enabled", "true")
            .withProperty("app.resilience.circuit-breaker.consecutive-failure-threshold", "7")
            .withProperty("app.resilience.circuit-breaker.open-duration", "45s")
            .withProperty("app.resilience.circuit-breaker.instances.e2b.consecutive-failure-threshold", "2")
            .withProperty("app.resilience.circuit-breaker.instances.hosted-llm.half-open-permitted-calls", "4")
            .withProperty("app.resilience.circuit-breaker.instances.hosted-llm.window", "2m");
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(env, null, clock);

        CircuitBreakerSettings brave = registry.breaker("brave").settings();
        assertThat(brave.consecutiveFailureThreshold()).isEqualTo(7);
        assertThat(brave.openDuration()).isEqualTo(Duration.ofSeconds(45));
        assertThat(brave.failureRatePercent()).isEqualTo(CircuitBreakerSettings.defaults().failureRatePercent());

        CircuitBreakerSettings e2b = registry.breaker("e2b").settings();
        assertThat(e2b.consecutiveFailureThreshold()).isEqualTo(2);
        assertThat(e2b.openDuration()).isEqualTo(Duration.ofSeconds(45)); // inherited from the global block

        // every hosted row shares the family's settings but trips independently
        CircuitBreaker row1 = registry.breaker("hosted-llm:row:1");
        CircuitBreaker row2 = registry.breaker("hosted-llm:row:2");
        assertThat(row1).isNotSameAs(row2);
        assertThat(row1.settings().halfOpenPermittedCalls()).isEqualTo(4);
        assertThat(row2.settings().window()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void bulkheadIsOffUnlessEnabledAndConfiguredPerFamily() {
        MockEnvironment on = new MockEnvironment()
            .withProperty("app.resilience.circuit-breaker.enabled", "true")
            .withProperty("app.resilience.bulkhead.enabled", "true")
            .withProperty("app.resilience.bulkhead.instances.e2b.max-concurrent", "3");
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(on, null, clock);
        assertThat(registry.guard("e2b").active()).isTrue();
        // brave has a breaker but no bulkhead configured: it must admit unlimited concurrency
        ResilienceGuard brave = registry.guard("brave");
        java.util.List<ResilienceGuard.Call> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            calls.add(brave.begin());
        }
        calls.forEach(ResilienceGuard.Call::close);

        MockEnvironment flagOff = new MockEnvironment()
            .withProperty("app.resilience.circuit-breaker.enabled", "true")
            .withProperty("app.resilience.bulkhead.instances.e2b.max-concurrent", "1");
        ResilienceGuard e2b = ResilienceConfiguration.build(flagOff, null, clock).guard("e2b");
        e2b.begin();
        e2b.begin().close(); // bulkhead.enabled is false: the per-family limit is ignored
    }

    @Test
    void sharedRegistryIsNeverNullAndFollowsInstallAndClose() {
        assertThat(CircuitBreakerRegistry.shared()).isNotNull();
        assertThat(CircuitBreakerRegistry.shared().enabled()).isFalse();
        MockEnvironment env = new MockEnvironment().withProperty("app.resilience.circuit-breaker.enabled", "true");
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(env, null, clock);
        CircuitBreakerRegistry.install(registry);
        assertThat(CircuitBreakerRegistry.shared()).isSameAs(registry);
        registry.close();
        assertThat(CircuitBreakerRegistry.shared().enabled()).isFalse();
    }

    @Test
    void statesListsEveryBreakerCreated() {
        MockEnvironment env = new MockEnvironment().withProperty("app.resilience.circuit-breaker.enabled", "true");
        CircuitBreakerRegistry registry = ResilienceConfiguration.build(env, null, clock);
        registry.guard("sidecar");
        registry.guard("meta-graph");
        assertThat(registry.states()).containsOnlyKeys("meta-graph", "sidecar");
    }

    // ------------------------------------------------------- outbound timeouts

    @Test
    void outboundTimeoutsBindFromAppHttpName() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.http.hcaptcha.connect-timeout", "3s")
            .withProperty("app.http.hcaptcha.read-timeout", "20s");
        OutboundHttpTimeouts t = OutboundHttpTimeouts.resolve(env, "hcaptcha", Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(t.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(t.readTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void outboundTimeoutsFallBackToTheCallersDefaults() {
        OutboundHttpTimeouts t = OutboundHttpTimeouts.resolve(
            new MockEnvironment(), "hcaptcha", Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(t.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(t.readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void aZeroOrNegativeConfiguredTimeoutCanNeverDisableTheBound() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.http.hcaptcha.connect-timeout", "0s")
            .withProperty("app.http.hcaptcha.read-timeout", "-5s");
        OutboundHttpTimeouts t = OutboundHttpTimeouts.resolve(env, "hcaptcha", Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(t.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(t.readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void onlyOneOfTheTwoMayBeOverridden() {
        MockEnvironment env = new MockEnvironment().withProperty("app.http.smtp.read-timeout", "90s");
        OutboundHttpTimeouts t = OutboundHttpTimeouts.resolve(env, "smtp", Duration.ofSeconds(15), Duration.ofSeconds(60));
        assertThat(t.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(t.readTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void requestFactoryIsBuildable() {
        assertThat(new OutboundHttpTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2)).requestFactory()).isNotNull();
    }

    // ------------------------------------------------------ shutdown advisor

    @Test
    void shutdownAdvisorFlagsADrainLongerThanTheContainerGrace() {
        assertThat(ShutdownDrainAdvisor.assess(30, 0, "graceful")).isNull(); // grace not stated: no comparison
        assertThat(ShutdownDrainAdvisor.assess(30, 60, "graceful")).isNull();
        assertThat(ShutdownDrainAdvisor.assess(50, 60, "graceful")).isNull(); // 50 + 10 margin == 60: fits
        assertThat(ShutdownDrainAdvisor.assess(51, 60, "graceful")).contains("terminationGracePeriodSeconds");
        assertThat(ShutdownDrainAdvisor.assess(120, 30, "graceful")).contains("130s");
    }

    @Test
    void shutdownAdvisorFlagsNonGracefulMode() {
        assertThat(ShutdownDrainAdvisor.assess(30, 0, "immediate")).contains("not 'graceful'");
        assertThat(ShutdownDrainAdvisor.assess(30, 0, "GRACEFUL")).isNull();
        assertThat(ShutdownDrainAdvisor.assess(30, 0, null)).contains("not 'graceful'");
    }

    @Test
    void shutdownAdvisorBeanBuildsWithoutAnAvailabilityBean() {
        MockEnvironment env = new MockEnvironment().withProperty("app.shutdown.drain-seconds", "120");
        org.springframework.beans.factory.ObjectProvider<org.springframework.boot.availability.ApplicationAvailability> none =
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override
                public org.springframework.boot.availability.ApplicationAvailability getObject() {
                    throw new IllegalStateException();
                }

                @Override
                public org.springframework.boot.availability.ApplicationAvailability getObject(Object... args) {
                    throw new IllegalStateException();
                }

                @Override
                public org.springframework.boot.availability.ApplicationAvailability getIfAvailable() {
                    return null;
                }

                @Override
                public org.springframework.boot.availability.ApplicationAvailability getIfUnique() {
                    return null;
                }
            };
        ShutdownDrainAdvisor advisor = new ResilienceConfiguration().shutdownDrainAdvisor(env, none);
        advisor.onReady();
        advisor.onClosing(); // both must tolerate a missing ApplicationAvailability
    }

    // ----------------------------------------------------------- rate limited log

    @Test
    void rateLimitedLogWritesOncePerIntervalPerKey() {
        Logger log = LoggerFactory.getLogger(ResilienceConfigurationTest.class);
        long[] now = {0L};
        RateLimitedLog limited = new RateLimitedLog(log, Duration.ofSeconds(60), () -> now[0]);
        assertThat(limited.warn("redis", "Redis down")).isTrue();
        assertThat(limited.warn("redis", "Redis down")).isFalse();
        assertThat(limited.warn("other", "Other down")).isTrue(); // independent key
        now[0] = 59_999L;
        assertThat(limited.warn("redis", "Redis down")).isFalse();
        now[0] = 60_000L;
        assertThat(limited.warn("redis", "Redis down")).isTrue();
        assertThat(limited.warn("redis", "Redis down")).isFalse();
    }

    @Test
    void rateLimitedLogIsBoundedInDistinctKeys() {
        long[] now = {0L};
        RateLimitedLog limited = new RateLimitedLog(
            LoggerFactory.getLogger(ResilienceConfigurationTest.class), Duration.ofSeconds(60), () -> now[0]);
        int logged = 0;
        for (int i = 0; i < RateLimitedLog.MAX_KEYS + 50; i++) {
            if (limited.warn("k" + i, "x")) {
                logged++;
            }
        }
        assertThat(logged).isEqualTo(RateLimitedLog.MAX_KEYS); // the table never grows past its cap
        now[0] = 61_000L;
        assertThat(limited.warn("fresh", "x")).isTrue(); // expired entries are reclaimed
    }
}
