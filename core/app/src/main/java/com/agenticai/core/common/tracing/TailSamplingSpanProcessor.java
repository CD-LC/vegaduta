package com.agenticai.core.common.tracing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-process "always keep errors and slow runs" tail sampling, with no OTel Collector.
 *
 * <h2>How it works</h2>
 * Used together with {@link TailSamplingSampler}, which turns every span the baseline sampler
 * would drop into a {@code RECORD_ONLY} span. The standard {@code BatchSpanProcessor} still skips
 * those (their trace flag says "not sampled"), so baseline export is exactly as before. THIS
 * processor receives only those unsampled spans and buffers each local trace's finished spans in
 * memory until the trace can be judged:
 * <ul>
 *   <li><b>keep</b> as soon as any span of the trace ended with status ERROR, any
 *       {@code invoke_agent} span ran at least {@code latencyMs}, or the local root span of a
 *       trace that contains an agent run did.
 *       Everything buffered so far is exported, and the trace is remembered as KEEP so spans that
 *       finish later (async hops, the root itself) are exported directly.</li>
 *   <li><b>drop</b> when the local root span ends (no parent, or a remote parent) without any keep
 *       signal. The buffer is discarded and the trace remembered as DROP so a straggler span is
 *       dropped too.</li>
 * </ul>
 * Kept spans are re-presented to a private {@link BatchSpanProcessor} with the "sampled" trace flag
 * set, so the exporter (the same OTLP exporter bean) sees an ordinary sampled span.
 *
 * <h2>Memory is bounded, and it fails open</h2>
 * At most {@code maxBufferedTraces} traces are buffered at once and at most
 * {@code maxSpansPerTrace} spans per trace; decided traces are remembered in an LRU capped at
 * {@code 4 * maxBufferedTraces} and for {@code decisionTtl}; a buffer older than the TTL is evicted
 * (its root never ended, so there is nothing to judge). When the buffer is full a NEW trace is not
 * buffered and simply gets the baseline outcome (dropped); a trace that outgrows
 * {@code maxSpansPerTrace} is dropped too (any error after that point is lost). Nothing here ever
 * throws into the tracing path, and nothing blocks: export is the batch processor's non-blocking
 * queue.
 *
 * <h2>Known limits</h2>
 * Spans of a trace that end after the trace was already decided DROP are lost, as are spans that
 * were buffered while the buffer was evicted. A trace is "slow" by its own spans' wall time, not
 * by anything the client saw. Judged per process: a distributed trace whose error happens in
 * another service is not visible here.
 */
public final class TailSamplingSpanProcessor implements SpanProcessor {

    static final String AGENT_RUN_SPAN_PREFIX = "invoke_agent";
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");

    private final SpanProcessor exporter;
    private final long latencyNanos;
    private final int maxBufferedTraces;
    private final int maxSpansPerTrace;
    private final long decisionTtlNanos;
    private final LongSupplier nanoClock;
    private final MeterRegistry meterRegistry;

    /** All mutable state below is guarded by {@code this}; export calls happen outside the lock. */
    private final Map<String, TraceBuffer> buffers = new HashMap<>();
    private final Map<String, Decision> decided;
    private long lastSweepNanos;

    private final AtomicLong kept = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();

    public TailSamplingSpanProcessor(
        SpanExporter exporter, long latencyMs, int maxBufferedTraces, int maxSpansPerTrace,
        long decisionTtlSeconds, MeterRegistry meterRegistry
    ) {
        this(BatchSpanProcessor.builder(exporter).build(), latencyMs, maxBufferedTraces, maxSpansPerTrace,
            decisionTtlSeconds, meterRegistry, System::nanoTime);
    }

    /** Test seam: any processor to forward kept spans to, and an injectable clock. */
    TailSamplingSpanProcessor(
        SpanProcessor exporter, long latencyMs, int maxBufferedTraces, int maxSpansPerTrace,
        long decisionTtlSeconds, MeterRegistry meterRegistry, LongSupplier nanoClock
    ) {
        this.exporter = exporter;
        this.latencyNanos = Math.max(0, latencyMs) * 1_000_000L;
        this.maxBufferedTraces = Math.max(1, maxBufferedTraces);
        this.maxSpansPerTrace = Math.max(1, maxSpansPerTrace);
        this.decisionTtlNanos = Math.max(1, decisionTtlSeconds) * 1_000_000_000L;
        this.nanoClock = nanoClock;
        this.meterRegistry = meterRegistry;
        this.lastSweepNanos = nanoClock.getAsLong();
        int cap = this.maxBufferedTraces * 4;
        this.decided = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Decision> eldest) {
                return size() > cap;
            }
        };
    }

    private record Decision(boolean keep, long atNanos) {
    }

    private static final class TraceBuffer {
        final List<SpanData> spans = new ArrayList<>();
        final long createdNanos;
        boolean agentRun;

        TraceBuffer(long createdNanos) {
            this.createdNanos = createdNanos;
        }
    }

    // ------------------------------------------------------------------ SpanProcessor

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Nothing to do at start; judged on end.
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        try {
            if (span == null || span.getSpanContext().isSampled()) {
                return; // baseline-sampled: the standard processor exports it
            }
            handle(span.toSpanData());
        } catch (RuntimeException e) {
            // Tracing must never affect the request: fail open (the span is simply not exported).
            count("failed-open", null);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode forceFlush() {
        return exporter.forceFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return exporter.shutdown();
    }

    // ------------------------------------------------------------------ decision logic

    private void handle(SpanData data) {
        List<SpanData> toExport = null;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            sweepIfDue(now);
            String traceId = data.getTraceId();

            Decision prior = decided.get(traceId);
            if (prior != null) {
                if (prior.keep()) {
                    toExport = List.of(data);
                } else {
                    count("dropped-late-span", null);
                }
            } else {
                boolean root = isLocalRoot(data);
                TraceBuffer buffer = buffers.get(traceId);
                // A slow ROOT only counts when the trace contains an agent run: a bare long root is
                // usually a legitimate long-lived stream (SSE, long poll), not something to keep.
                boolean agentTrace = isAgentRun(data) || (buffer != null && buffer.agentRun);
                boolean keep = isErrored(data) || isSlowAgentRun(data)
                    || (root && agentTrace && durationNanos(data) >= latencyNanos);
                if (keep) {
                    toExport = new ArrayList<>();
                    if (buffer != null) {
                        toExport.addAll(buffer.spans);
                        buffers.remove(traceId);
                    }
                    toExport.add(data);
                    decided.put(traceId, new Decision(true, now));
                    kept.incrementAndGet();
                    count("traces", "kept");
                } else if (root) {
                    if (buffer != null) {
                        buffers.remove(traceId);
                    }
                    decided.put(traceId, new Decision(false, now));
                    dropped.incrementAndGet();
                    count("traces", "dropped");
                } else {
                    if (buffer == null) {
                        if (buffers.size() >= maxBufferedTraces) {
                            sweep(now);
                        }
                        if (buffers.size() >= maxBufferedTraces) {
                            // Fail open: no room to judge this trace, so it gets the baseline outcome (dropped).
                            overflowed.incrementAndGet();
                            count("traces", "overflow");
                            return;
                        }
                        buffer = new TraceBuffer(now);
                        buffers.put(traceId, buffer);
                    }
                    if (buffer.spans.size() >= maxSpansPerTrace) {
                        buffers.remove(traceId);
                        decided.put(traceId, new Decision(false, now));
                        overflowed.incrementAndGet();
                        count("traces", "overflow");
                    } else {
                        buffer.spans.add(data);
                        buffer.agentRun |= isAgentRun(data);
                    }
                }
            }
        }
        if (toExport != null) {
            for (SpanData span : toExport) {
                exporter.onEnd(asSampled(span));
            }
        }
    }

    private void sweepIfDue(long now) {
        if (now - lastSweepNanos >= 1_000_000_000L) {
            sweep(now);
        }
    }

    /** Evicts buffers whose root never ended and decisions past their TTL. Caller holds the lock. */
    private void sweep(long now) {
        lastSweepNanos = now;
        for (Iterator<TraceBuffer> it = buffers.values().iterator(); it.hasNext();) {
            if (now - it.next().createdNanos > decisionTtlNanos) {
                it.remove();
                evicted.incrementAndGet();
                count("traces", "evicted");
            }
        }
        decided.values().removeIf(d -> now - d.atNanos() > decisionTtlNanos);
    }

    private static boolean isLocalRoot(SpanData data) {
        SpanContext parent = data.getParentSpanContext();
        return !parent.isValid() || parent.isRemote();
    }

    private static boolean isErrored(SpanData data) {
        return data.getStatus().getStatusCode() == StatusCode.ERROR;
    }

    private static boolean isAgentRun(SpanData data) {
        return data.getName() != null && data.getName().startsWith(AGENT_RUN_SPAN_PREFIX)
            || AGENT_RUN_SPAN_PREFIX.equals(data.getAttributes().get(OPERATION));
    }

    private boolean isSlowAgentRun(SpanData data) {
        return isAgentRun(data) && durationNanos(data) >= latencyNanos;
    }

    private static long durationNanos(SpanData data) {
        return Math.max(0, data.getEndEpochNanos() - data.getStartEpochNanos());
    }

    private void count(String name, String decision) {
        if (meterRegistry == null) {
            return;
        }
        try {
            if (decision == null) {
                meterRegistry.counter("tracing.tail." + name).increment();
            } else {
                meterRegistry.counter("tracing.tail." + name, "decision", decision).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics are best-effort
        }
    }

    // ------------------------------------------------------------------ introspection (tests, ops)

    synchronized int bufferedTraces() {
        return buffers.size();
    }

    long keptTraces() {
        return kept.get();
    }

    long droppedTraces() {
        return dropped.get();
    }

    long overflowedTraces() {
        return overflowed.get();
    }

    long evictedTraces() {
        return evicted.get();
    }

    // ------------------------------------------------------------------ "sampled" re-presentation

    /**
     * A view of an unsampled finished span whose trace flags say sampled, so the batch processor
     * accepts it and the exporter marshals it as an ordinary sampled span. Done with dynamic proxies
     * so it does not depend on the (deprecated, version-varying) members of the SDK interfaces.
     */
    static ReadableSpan asSampled(SpanData data) {
        SpanContext original = data.getSpanContext();
        SpanContext sampled = SpanContext.create(
            original.getTraceId(), original.getSpanId(), TraceFlags.getSampled(), original.getTraceState());
        SpanData sampledData = (SpanData) Proxy.newProxyInstance(
            SpanData.class.getClassLoader(), new Class<?>[] {SpanData.class},
            (proxy, method, args) -> "getSpanContext".equals(method.getName()) && method.getParameterCount() == 0
                ? sampled : invoke(data, method, args));
        InvocationHandler readable = (proxy, method, args) -> switch (method.getName()) {
            case "getSpanContext" -> sampled;
            case "toSpanData" -> sampledData;
            case "hasEnded" -> true;
            case "getLatencyNanos" -> durationNanos(data);
            case "getAttribute" -> data.getAttributes().get((AttributeKey<?>) args[0]);
            case "getAttributes" -> data.getAttributes();
            case "getParentSpanContext" -> data.getParentSpanContext();
            case "getName" -> data.getName();
            case "getKind" -> data.getKind();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "TailSampledSpan[" + data.getName() + "]";
            default -> invoke(data, method, args); // instrumentation scope/library info share names with SpanData
        };
        return (ReadableSpan) Proxy.newProxyInstance(
            ReadableSpan.class.getClassLoader(), new Class<?>[] {ReadableSpan.class}, readable);
    }

    private static Object invoke(SpanData target, Method method, Object[] args) throws Throwable {
        try {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            Method onData = SpanData.class.getMethod(method.getName(), method.getParameterTypes());
            return onData.invoke(target, args);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(method.getName());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
