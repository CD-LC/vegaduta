package com.agenticai.core.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real OTel SDK (no mocks of the sampler/processor contract): a baseline of 0% so every
 * trace is "dropped" by head sampling, then checks what the tail logic rescues. A second, ordinary
 * {@link BatchSpanProcessor} on the same exporter plays the role of Spring Boot's standard processor.
 */
class TailSamplingSpanProcessorTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    static final class Capturing implements SpanExporter {
        final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
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

        List<String> names() {
            synchronized (spans) {
                return spans.stream().map(SpanData::getName).sorted().toList();
            }
        }
    }

    private final Capturing exporter = new Capturing();
    private SdkTracerProvider provider;
    private TailSamplingSpanProcessor tail;
    private Tracer tracer;

    private void start(double baseline, long latencyMs, int maxTraces, int maxSpans) {
        tail = new TailSamplingSpanProcessor(exporter, latencyMs, maxTraces, maxSpans, 300, new SimpleMeterRegistry());
        provider = SdkTracerProvider.builder()
            .setSampler(new TailSamplingSampler(Sampler.parentBased(Sampler.traceIdRatioBased(baseline))))
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .addSpanProcessor(tail)
            .build();
        tracer = provider.get("test");
    }

    @AfterEach
    void close() {
        if (provider != null) {
            provider.close();
        }
    }

    private List<String> flushed() {
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        return exporter.names();
    }

    private Span root(String name) {
        return tracer.spanBuilder(name).setNoParent().startSpan();
    }

    private Span child(Span parent, String name) {
        return tracer.spanBuilder(name).setParent(Context.root().with(parent)).startSpan();
    }

    private Span slowChild(Span parent, String name, long seconds) {
        return tracer.spanBuilder(name).setParent(Context.root().with(parent))
            .setStartTimestamp(Instant.now().minusSeconds(seconds)).startSpan();
    }

    @Test
    void anOrdinaryDroppedTraceIsNotExported() {
        start(0.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span a = child(root, "chat");
        a.end();
        root.end();

        assertThat(flushed()).isEmpty();
        assertThat(tail.droppedTraces()).isEqualTo(1);
        assertThat(tail.bufferedTraces()).isZero();
    }

    @Test
    void anErroredSpanKeepsTheWholeTraceIncludingSpansThatEndedBeforeAndAfterIt() {
        start(0.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span before = child(root, "retrieval");
        before.end();
        Span failing = child(root, "chat");
        failing.setStatus(StatusCode.ERROR, "boom");
        failing.end();
        Span after = child(root, "execute_tool lookup");
        after.end();
        root.end();

        assertThat(flushed()).containsExactly("chat", "execute_tool lookup", "http POST /chat", "retrieval");
        assertThat(exporter.spans).allSatisfy(s -> assertThat(s.getSpanContext().isSampled())
            .as("re-presented as sampled for the exporter").isTrue());
        assertThat(tail.keptTraces()).isEqualTo(1);
    }

    @Test
    void aSlowAgentRunIsKeptEvenThoughNothingFailed() {
        start(0.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span sibling = child(root, "retrieval");
        sibling.end();
        Span agent = slowChild(root, "invoke_agent 1234", 30);
        agent.end();
        root.end();

        assertThat(flushed()).containsExactly("http POST /chat", "invoke_agent 1234", "retrieval");
    }

    @Test
    void aFastAgentRunIsNotKept() {
        start(0.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span agent = slowChild(root, "invoke_agent 1234", 2);
        agent.end();
        root.end();

        assertThat(flushed()).isEmpty();
    }

    @Test
    void aSlowRootCountsOnlyWhenTheTraceContainsAnAgentRun() {
        start(0.0, 20_000, 500, 512);
        // long-lived stream with no agent run: not kept
        Span stream = tracer.spanBuilder("http GET /events").setNoParent()
            .setStartTimestamp(Instant.now().minusSeconds(120)).startSpan();
        stream.end();
        // end-to-end slower than the threshold although the agent span alone was not
        Span slowRoot = tracer.spanBuilder("http POST /chat").setNoParent()
            .setStartTimestamp(Instant.now().minusSeconds(25)).startSpan();
        Span agent = child(slowRoot, "invoke_agent 1234");
        agent.end();
        slowRoot.end();

        assertThat(flushed()).containsExactly("http POST /chat", "invoke_agent 1234");
    }

    @Test
    void baselineSampledTracesAreExportedOnceByTheStandardProcessorAndIgnoredHere() {
        start(1.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span a = child(root, "chat");
        a.setStatus(StatusCode.ERROR);
        a.end();
        root.end();

        assertThat(flushed()).containsExactly("chat", "http POST /chat");
        assertThat(tail.keptTraces()).isZero();
    }

    @Test
    void spansEndingAfterAKeepDecisionAreExportedDirectly() {
        start(0.0, 20_000, 500, 512);
        Span root = root("http POST /chat");
        Span failing = child(root, "chat");
        Span late = child(root, "background-audit");
        failing.setStatus(StatusCode.ERROR);
        failing.end();
        root.end();
        late.end();

        assertThat(flushed()).containsExactly("background-audit", "chat", "http POST /chat");
    }

    @Test
    void whenTheBufferIsFullANewTraceFailsOpenToTheBaselineDropAndNothingThrows() {
        start(0.0, 20_000, 2, 512);
        Span r1 = root("t1");
        Span r2 = root("t2");
        Span r3 = root("t3");
        child(r1, "t1-child").end();
        child(r2, "t2-child").end();
        child(r3, "t3-child").end(); // no room: dropped, baseline outcome

        assertThat(tail.bufferedTraces()).isEqualTo(2);
        assertThat(tail.overflowedTraces()).isEqualTo(1);

        r1.setStatus(StatusCode.ERROR);
        r1.end();
        r3.setStatus(StatusCode.ERROR);
        r3.end();
        r2.end();

        // t1 complete (child + errored root); t3 only its errored root (its child was never buffered); t2 dropped.
        assertThat(flushed()).containsExactly("t1", "t1-child", "t3");
    }

    @Test
    void aTraceThatOutgrowsTheSpanCapIsDroppedNotBufferedWithoutBound() {
        start(0.0, 20_000, 500, 2);
        Span root = root("http POST /chat");
        for (int i = 0; i < 5; i++) {
            child(root, "chat-" + i).end();
        }
        assertThat(tail.bufferedTraces()).isZero();
        assertThat(tail.overflowedTraces()).isEqualTo(1);
        root.setStatus(StatusCode.ERROR);
        root.end();

        // the trace was already given up on; the late errored root is a decided-DROP straggler
        assertThat(flushed()).isEmpty();
    }

    @Test
    void abandonedBuffersAreEvictedAfterTheTtl() {
        AtomicLong clock = new AtomicLong(0);
        List<ReadableSpan> forwarded = new ArrayList<>();
        SpanProcessor sink = new SpanProcessor() {
            @Override
            public void onStart(Context c, ReadWriteSpan s) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan s) {
                forwarded.add(s);
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        };
        TailSamplingSpanProcessor processor = new TailSamplingSpanProcessor(
            sink, 20_000, 10, 10, 60, null, clock::get);
        provider = SdkTracerProvider.builder()
            .setSampler(new TailSamplingSampler(Sampler.alwaysOff()))
            .addSpanProcessor(processor).build();
        tracer = provider.get("test");

        Span orphanRoot = root("never-ends");
        child(orphanRoot, "orphan-child").end();
        assertThat(processor.bufferedTraces()).isEqualTo(1);

        clock.addAndGet(61 * NANOS_PER_SECOND);
        child(root("other"), "other-child").end(); // triggers the due sweep

        assertThat(processor.evictedTraces()).isEqualTo(1);
        assertThat(processor.bufferedTraces()).isEqualTo(1);
        assertThat(forwarded).isEmpty();
    }

    @Test
    void forwardedSpansAreSampledViewsThatKeepTheirIdentityAndAttributes() {
        AtomicLong clock = new AtomicLong(0);
        List<ReadableSpan> forwarded = new ArrayList<>();
        SpanProcessor sink = new SpanProcessor() {
            @Override
            public void onStart(Context c, ReadWriteSpan s) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan s) {
                forwarded.add(s);
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        };
        TailSamplingSpanProcessor processor = new TailSamplingSpanProcessor(sink, 20_000, 10, 10, 60, null, clock::get);
        provider = SdkTracerProvider.builder()
            .setSampler(new TailSamplingSampler(Sampler.alwaysOff()))
            .addSpanProcessor(processor).build();
        tracer = provider.get("test");

        Span root = root("http POST /chat");
        Span failing = child(root, "chat");
        failing.setAttribute("gen_ai.request.model", "m1");
        failing.setStatus(StatusCode.ERROR);
        String traceId = failing.getSpanContext().getTraceId();
        String spanId = failing.getSpanContext().getSpanId();
        failing.end();

        assertThat(forwarded).hasSize(1);
        ReadableSpan view = forwarded.get(0);
        assertThat(view.getSpanContext().isSampled()).isTrue();
        assertThat(view.getSpanContext().getTraceId()).isEqualTo(traceId);
        assertThat(view.toSpanData().getSpanContext().getSpanId()).isEqualTo(spanId);
        assertThat(view.toSpanData().getSpanContext().isSampled()).isTrue();
        assertThat(view.toSpanData().getAttributes().asMap()).hasSize(1);
        assertThat(view.toSpanData().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(view.getParentSpanContext().getSpanId()).isEqualTo(root.getSpanContext().getSpanId());
        assertThat(view.getName()).isEqualTo("chat");
        assertThat(view.toSpanData().getTraceId()).isEqualTo(traceId);
    }
}
