package com.agenticai.core.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TailSamplingConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class ExporterConfig {
        @Bean
        SpanExporter fakeExporter() {
            return new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ExporterConfig.class, TailSamplingConfiguration.class);

    @Test
    void flagOffRegistersNothingSoTracingIsUntouched() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(Sampler.class);
            assertThat(ctx).doesNotHaveBean(SpanProcessor.class);
            assertThat(ctx).doesNotHaveBean(TailSamplingConfiguration.class);
        });
    }

    @Test
    void flagFalseExplicitlyIsAlsoOff() {
        runner.withPropertyValues("app.observability.tail-sampling.enabled=false")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(Sampler.class));
    }

    @Test
    void flagOnRegistersTheSamplerAndProcessorAndDropsBecomeRecordOnly() {
        runner.withPropertyValues(
                "app.observability.tail-sampling.enabled=true",
                "management.tracing.sampling.probability=0.0")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(Sampler.class);
                assertThat(ctx).hasSingleBean(SpanProcessor.class);
                assertThat(ctx.getBean(SpanProcessor.class)).isInstanceOf(TailSamplingSpanProcessor.class);
                Sampler sampler = ctx.getBean(Sampler.class);
                assertThat(sampler).isInstanceOf(TailSamplingSampler.class);
                assertThat(sampler.shouldSample(
                    Context.root(), "0af7651916cd43dd8448eb211c80319c", "x", SpanKind.SERVER,
                    Attributes.empty(), List.of()).getDecision()).isEqualTo(SamplingDecision.RECORD_ONLY);
                ctx.getBean(SpanProcessor.class).close();
            });
    }

    @Test
    void withoutAnExporterTheSamplerIsTheUntouchedBaselineAndNothingIsRecordedForNoReason() {
        new ApplicationContextRunner()
            .withUserConfiguration(TailSamplingConfiguration.class)
            .withPropertyValues(
                "app.observability.tail-sampling.enabled=true",
                "management.tracing.sampling.probability=0.0")
            .run(ctx -> {
                Sampler sampler = ctx.getBean(Sampler.class);
                assertThat(sampler).isNotInstanceOf(TailSamplingSampler.class);
                assertThat(sampler.shouldSample(
                    Context.root(), "0af7651916cd43dd8448eb211c80319c", "x", SpanKind.SERVER,
                    Attributes.empty(), List.of()).getDecision()).isEqualTo(SamplingDecision.DROP);
            });
    }

    @Test
    void theSamplerKeepsBaselineSampledSpansAsTheyAre() {
        runner.withPropertyValues(
                "app.observability.tail-sampling.enabled=true",
                "management.tracing.sampling.probability=1.0")
            .run(ctx -> {
                assertThat(ctx.getBean(Sampler.class).shouldSample(
                    Context.root(), "0af7651916cd43dd8448eb211c80319c", "x", SpanKind.SERVER,
                    Attributes.empty(), List.of()).getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
                ctx.getBean(SpanProcessor.class).close();
            });
    }
}
