package com.agenticai.core.common.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

/**
 * The normal head-sampling decision (Spring Boot's {@code ParentBased(TraceIdRatio(p))}), except that
 * a span the baseline would DROP is {@code RECORD_ONLY} instead.
 *
 * <p>That is the whole trick that makes in-process tail sampling possible: a dropped span is
 * normally a non-recording no-op, so by the time a trace turns out to have failed there is nothing
 * left to export. A {@code RECORD_ONLY} span keeps its data and still reaches every
 * {@code SpanProcessor#onEnd}, but carries the "not sampled" trace flag, so the standard
 * {@code BatchSpanProcessor} still ignores it and the baseline export volume is unchanged.
 * {@link TailSamplingSpanProcessor} is the only thing that looks at those spans.
 *
 * <p>Baseline-sampled spans are returned untouched.
 */
public final class TailSamplingSampler implements Sampler {

    private final Sampler baseline;

    public TailSamplingSampler(Sampler baseline) {
        this.baseline = baseline;
    }

    @Override
    public SamplingResult shouldSample(
        Context parentContext, String traceId, String name, SpanKind spanKind,
        Attributes attributes, List<LinkData> parentLinks
    ) {
        SamplingResult result = baseline.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        return result.getDecision() == SamplingDecision.DROP ? SamplingResult.recordOnly() : result;
    }

    @Override
    public String getDescription() {
        return "TailSamplingSampler{" + baseline.getDescription() + "}";
    }
}
