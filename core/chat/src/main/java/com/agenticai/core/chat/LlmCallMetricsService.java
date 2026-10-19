package com.agenticai.core.chat;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Direct feedback on Platform Maintenance: "capture any metrics like
 * latency, how many LLM calls were made" - a real, in-process counter/timer
 * fed by {@link LlmMetricsAdvisor}, which every {@link ChatClient} this app
 * builds is wired up with (see {@code AnthropicAgentChatClientFactory}, the
 * single construction chokepoint for all 10 real call sites: agent chat,
 * quick task, guardrails, reflexion, graph memory, workflow graph
 * generation, Ask-AI assist).
 *
 * <p>In-memory, reset on restart - same posture this project already uses
 * for {@code JvmStatus.uptimeSeconds}/{@code heapUsedMb} in
 * {@code SystemStatusResponse}: a real, live number for "since this process
 * started," not a fabricated one and not worth a new database table for a
 * first cut. {@code totalCalls}/{@code errorCount} use {@link AtomicLong}
 * (simple increment, no read-modify-write races); the running latency sum
 * is a second {@link AtomicLong} of milliseconds, divided by the call count
 * only at read time in {@link #snapshot()} - avoids any floating-point
 * accumulation drift across a long-running process.
 */
@Component
public class LlmCallMetricsService {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    public void recordCall(long latencyMs, boolean success) {
        totalCalls.incrementAndGet();
        totalLatencyMs.addAndGet(Math.max(0, latencyMs));
        if (!success) {
            errorCount.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        long calls = totalCalls.get();
        Double avgLatencyMs = calls > 0 ? (double) totalLatencyMs.get() / calls : null;
        return new Snapshot(calls, errorCount.get(), avgLatencyMs);
    }

    public record Snapshot(long totalCalls, long errorCount, Double avgLatencyMs) {
    }
}
