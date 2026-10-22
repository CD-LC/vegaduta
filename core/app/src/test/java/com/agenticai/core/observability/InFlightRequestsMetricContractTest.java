package com.agenticai.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Contract for the autoscaling signal documented in {@code
 * docs/architecture/ADR-0014-horizontal-scaling.md} and used by {@code
 * deploy/helm/examples/values-autoscaling.yaml}: the number of HTTP requests a pod is serving right
 * now, which for this API is dominated by open SSE chat streams. It comes from Spring Boot's own
 * observation wiring, not from any code of ours, so this test pins the exact Prometheus series name
 * against a Micrometer or Boot upgrade renaming it.
 */
class InFlightRequestsMetricContractTest {

    @Test
    void anOpenHttpServerObservationShowsUpAsTheActiveRequestsSeriesAndLeavesWhenItStops() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                MetricsAutoConfiguration.class,
                                CompositeMeterRegistryAutoConfiguration.class,
                                PrometheusMetricsExportAutoConfiguration.class,
                                ObservationAutoConfiguration.class))
                .run(
                        context -> {
                            ObservationRegistry observations =
                                    context.getBean(ObservationRegistry.class);
                            PrometheusMeterRegistry prometheus =
                                    context.getBean(PrometheusMeterRegistry.class);

                            Observation stream =
                                    Observation.start("http.server.requests", observations);
                            assertThat(activeCount(prometheus)).isEqualTo(1.0);

                            stream.stop();
                            assertThat(activeCount(prometheus)).isEqualTo(0.0);
                        });
    }

    /**
     * The sample line is {@code http_server_requests_active_seconds_count <n>}: a long-task timer's
     * count is its open tasks.
     */
    private static double activeCount(PrometheusMeterRegistry registry) {
        return registry.scrape()
                .lines()
                .filter(l -> l.startsWith("http_server_requests_active_seconds_count "))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1)))
                .sum();
    }
}
