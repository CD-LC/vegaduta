package com.agenticai.core.common.tracing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in wiring for in-process tail sampling ({@code app.observability.tail-sampling.enabled},
 * default {@code false}). With the flag off this class is not even registered: no bean of this
 * package exists, Spring Boot's own {@code otelSampler} stays in force, and tracing behaves exactly
 * as before.
 *
 * <p>With it on, the {@link Sampler} bean below replaces Boot's (its {@code otelSampler} is
 * {@code @ConditionalOnMissingBean}) and the {@link SpanProcessor} bean is picked up by Boot's
 * {@code SpanProcessors} collection next to the standard batch processor. Both need an OTLP
 * {@link SpanExporter} bean; without one (export disabled) the sampler is the plain baseline and
 * the processor is a no-op, so nothing is recorded for no reason.
 *
 * <p>Baseline probability is {@code management.tracing.sampling.probability}; note that a
 * probability of {@code 0.0} together with this flag means "export only errors and slow agent
 * runs", not "tracing off".
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SdkTracerProvider.class)
@ConditionalOnProperty(prefix = "app.observability.tail-sampling", name = "enabled", havingValue = "true")
public class TailSamplingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TailSamplingConfiguration.class);

    @Bean
    Sampler tailSamplingSampler(
        @Value("${management.tracing.sampling.probability:0.1}") double probability,
        ObjectProvider<SpanExporter> exporters
    ) {
        double p = Double.isNaN(probability) ? 0.1 : Math.max(0.0, Math.min(1.0, probability));
        Sampler baseline = Sampler.parentBased(Sampler.traceIdRatioBased(p));
        if (exporters.stream().findAny().isEmpty()) {
            log.info("Tail sampling enabled but no span exporter is configured; using the plain baseline sampler");
            return baseline;
        }
        return new TailSamplingSampler(baseline);
    }

    @Bean
    SpanProcessor tailSamplingSpanProcessor(
        ObjectProvider<SpanExporter> exporters,
        ObjectProvider<MeterRegistry> meterRegistry,
        @Value("${app.observability.tail-sampling.latency-ms:20000}") long latencyMs,
        @Value("${app.observability.tail-sampling.max-buffered-traces:500}") int maxBufferedTraces,
        @Value("${app.observability.tail-sampling.max-spans-per-trace:512}") int maxSpansPerTrace,
        @Value("${app.observability.tail-sampling.ttl-seconds:300}") long ttlSeconds
    ) {
        List<SpanExporter> all = exporters.orderedStream().toList();
        if (all.isEmpty()) {
            return SpanProcessor.composite(List.of());
        }
        log.info("In-process tail sampling ON: keep errored traces and agent runs over {} ms "
                + "(max {} buffered traces, {} spans each, ttl {}s)",
            latencyMs, maxBufferedTraces, maxSpansPerTrace, ttlSeconds);
        return new TailSamplingSpanProcessor(
            SpanExporter.composite(all), latencyMs, maxBufferedTraces, maxSpansPerTrace, ttlSeconds,
            meterRegistry.getIfAvailable());
    }
}
