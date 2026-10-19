package com.agenticai.core.workflow.stream;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live event stream for workflow runs - the backend of the canvas's
 * agent-native run view (phase 3): token-by-token agent output inside a node,
 * tool calls as child rows, a visible approval/input pause, and per-node plus
 * running cost. Served by {@code GET /api/workflows/{id}/runs/{runId}/events}
 * as Server-Sent Events.
 *
 * <h2>Event vocabulary (the {@code event:} name; {@code data:} is one JSON object)</h2>
 * Every payload carries {@code seq}, {@code type}, {@code runId}, {@code ts}
 * and, for node events, {@code nodeId}; the rest is in {@code data}.
 * <ul>
 *   <li>{@code snapshot} - the run as {@code GET .../runs/{runId}} returns it
 *       (status + full persisted trace). Sent first on every connect, and by
 *       the poll fallback whenever the stored run changes.</li>
 *   <li>{@code run.started} / {@code run.resumed} - traversal began / continued
 *       after an approval.</li>
 *   <li>{@code node.started} - {@code nodeType}, {@code label}, {@code attempt}
 *       (retries emit it again with a higher attempt).</li>
 *   <li>{@code node.token} - {@code delta} to append, or {@code reset:true}
 *       + {@code text} to replace, or {@code capped:true}. Already
 *       secret-redacted - see {@link NodeStreamSink}.</li>
 *   <li>{@code node.tool_call} ({@code id}, {@code tool}, {@code args}),
 *       {@code node.tool_result} ({@code id}, {@code tool}, {@code preview},
 *       {@code ms}), {@code node.tool_error} ({@code id}, {@code tool},
 *       {@code message}, {@code ms}), {@code node.agent_event} (plan /
 *       guardrail / handoff / ...: {@code kind} + fields) - the agent step
 *       events {@code AgentStepEventEmitter} already produces for the chat UI.</li>
 *   <li>{@code node.completed} - {@code entry}: the exact {@code
 *       NodeTraceEntry} the trace stores (status, durationMs, inputJson,
 *       outputJson, tokensIn/Out, costUsd, toolCalls), plus {@code runTotals}
 *       ({@code tokensIn}, {@code tokensOut}, {@code costUsd} summed over the
 *       trace so far; null parts = unknown).</li>
 *   <li>{@code run.awaiting} - the run halted for a person: {@code status}
 *       (AWAITING_APPROVAL / AWAITING_INPUT / PAUSED), {@code nodeId},
 *       {@code label}, {@code summary}, {@code requestId} when the summary
 *       names one. The stream stays open; approval resumes it.</li>
 *   <li>{@code run.finished} - {@code status}, {@code error}. Terminal: the
 *       server closes the stream after it.</li>
 * </ul>
 * Heartbeats are SSE comments ({@code :hb}) every {@link #HEARTBEAT_SECONDS}
 * s - under Cloudflare's 100 s idle cut and Azure's 4-minute NAT idle drop.
 * Each event's SSE {@code id} is its {@code seq}; reconnect with
 * {@code Last-Event-ID} to replay what was missed (bounded buffer).
 *
 * <h2>Scope: one JVM, with a DB fallback</h2>
 * Channels live in this process's memory, which matches today's deployment
 * (core runs as a single replica). A subscriber whose run is NOT executing in
 * this JVM - Temporal-engine runs, a second replica, a run that already
 * finished - is served by polling the stored run every
 * {@link #POLL_INTERVAL_SECONDS} s and emitting a {@code snapshot} on change,
 * so the endpoint never lies by going silent; it just degrades from live
 * tokens to per-node granularity.
 *
 * <h2>Isolation</h2>
 * Channels are keyed by run id but carry the tenant and workflow the ENGINE
 * attached; a subscriber only receives events when both match its own
 * (tenant from its token, workflow from the path it passed VIEWER on). A
 * client may subscribe BEFORE its run starts (sync runs with a
 * {@code clientRunId}); that pending subscription is bound to the tenant and
 * workflow it claimed, and is dropped with an error if the run that starts
 * under that id belongs to anyone else.
 */
@Component
public class WorkflowRunStreamHub {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunStreamHub.class);

    static final int MAX_BUFFERED_EVENTS = 2000;
    static final int MAX_CHANNELS = 1000;
    static final long HEARTBEAT_SECONDS = 15;
    static final long POLL_INTERVAL_SECONDS = 2;
    static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;
    static final long RETAIN_ENDED_MS = 5L * 60 * 1000;
    static final long PENDING_TTL_MS = 2L * 60 * 1000;

    static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    static final Set<String> AWAITING = Set.of("AWAITING_APPROVAL", "AWAITING_INPUT", "PAUSED");

    // Spring's own mapper, so a snapshot serializes exactly like GET .../runs/{runId}.
    private final ObjectMapper mapper;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public WorkflowRunStreamHub(ObjectMapper mapper) {
        this.mapper = mapper;
        this.scheduler = TenantAwareExecutors.newSingleThreadScheduledExecutor("workflow-run-stream", true);
        scheduler.scheduleWithFixedDelay(this::heartbeatAndEvict, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Engine side
    // ------------------------------------------------------------------

    /**
     * The engine is about to traverse run {@code runId} (fresh or resumed).
     * Attaches (or re-attaches) the live channel; a pending channel claimed by
     * a different tenant/workflow is torn down first.
     */
    public void runStarted(String runId, String tenantId, String workflowId, boolean resumed) {
        if (runId == null) {
            return;
        }
        Channel channel = channels.compute(runId, (id, existing) -> {
            if (existing != null && existing.engineAttached
                && Objects.equals(existing.tenantId, tenantId) && Objects.equals(existing.workflowId, workflowId)) {
                return existing;
            }
            if (existing != null && !(Objects.equals(existing.tenantId, tenantId)
                && Objects.equals(existing.workflowId, workflowId))) {
                existing.failAll("This run id belongs to a different workflow.");
                existing = null;
            }
            Channel c = existing != null ? existing : new Channel(id, tenantId, workflowId);
            return c;
        });
        synchronized (channel) {
            channel.engineAttached = true;
            channel.ended = false;
            channel.stopPolling();
        }
        publish(runId, resumed ? "run.resumed" : "run.started", null, Map.of("workflowId", String.valueOf(workflowId)));
        evictIfOverCapacity();
    }

    /** Per-node sink; {@link NodeStreamSink#NOOP} when no live channel exists for the run. */
    public NodeStreamSink nodeSink(String runId, String nodeId) {
        Channel channel = runId == null ? null : channels.get(runId);
        return channel == null || !channel.engineAttached
            ? NodeStreamSink.NOOP
            : new NodeStreamSink(this, runId, nodeId, System::currentTimeMillis);
    }

    /** Appends one event to the run's channel and fans it out. No-op without an engine-attached channel. */
    public void publish(String runId, String type, String nodeId, Map<String, ?> data) {
        Channel channel = runId == null ? null : channels.get(runId);
        if (channel == null || !channel.engineAttached) {
            return;
        }
        synchronized (channel) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seq", ++channel.seq);
            payload.put("type", type);
            payload.put("runId", runId);
            if (nodeId != null) {
                payload.put("nodeId", nodeId);
            }
            payload.put("ts", Instant.now());
            payload.put("data", data == null ? Map.of() : data);
            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                log.debug("Dropping unserializable {} event for run {}: {}", type, runId, e.getMessage());
                return;
            }
            Event event = new Event(channel.seq, type, json);
            channel.buffer.addLast(event);
            while (channel.buffer.size() > MAX_BUFFERED_EVENTS) {
                channel.buffer.removeFirst();
            }
            for (Subscriber sub : channel.subscribers) {
                sub.send(event);
            }
        }
    }

    /**
     * The traversal leg returned. Terminal statuses end the stream; awaiting
     * ones publish {@code run.awaiting} and keep it open for the resume.
     */
    public void runSettled(String runId, String status, Map<String, ?> data) {
        Channel channel = runId == null ? null : channels.get(runId);
        if (channel == null || !channel.engineAttached) {
            return;
        }
        boolean terminal = status != null && TERMINAL.contains(status);
        publish(runId, terminal ? "run.finished" : "run.awaiting", null, data);
        if (terminal) {
            synchronized (channel) {
                channel.ended = true;
                channel.endedAt = System.currentTimeMillis();
                for (Subscriber sub : channel.subscribers) {
                    sub.complete();
                }
                channel.subscribers.clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Subscriber side
    // ------------------------------------------------------------------

    /**
     * Opens an SSE stream for a caller who already passed VIEWER on
     * {@code workflowId}.
     *
     * @param snapshot the stored run, or null if no row exists (yet)
     * @param poller   reloads the stored run (null when gone) - used when the
     *                 run is not executing in this JVM
     */
    public SseEmitter subscribe(
        String tenantId, String workflowId, String runId, Long lastEventId,
        WorkflowRunResponse snapshot, Supplier<WorkflowRunResponse> poller
    ) {
        SseEmitter emitter = newEmitter();
        Subscriber sub = new Subscriber(emitter);
        if (snapshot != null) {
            sub.sendSnapshot(snapshot);
        }

        Channel channel = channels.get(runId);
        if (channel != null && channel.engineAttached) {
            if (!Objects.equals(channel.tenantId, tenantId) || !Objects.equals(channel.workflowId, workflowId)) {
                sub.fail("Run not found for this workflow.");
                return emitter;
            }
            synchronized (channel) {
                for (Event e : channel.buffer) {
                    if (lastEventId == null || e.seq > lastEventId) {
                        sub.send(e);
                    }
                }
                if (channel.ended) {
                    sub.complete();
                    return emitter;
                }
                channel.subscribers.add(sub);
            }
            emitter.onCompletion(() -> channel.subscribers.remove(sub));
            emitter.onTimeout(() -> channel.subscribers.remove(sub));
            emitter.onError(t -> channel.subscribers.remove(sub));
            return emitter;
        }

        // Not live here.
        if (snapshot != null && TERMINAL.contains(String.valueOf(snapshot.status()))) {
            sub.complete();
            return emitter;
        }
        if (channels.size() >= MAX_CHANNELS) {
            evictIfOverCapacity();
            if (channels.size() >= MAX_CHANNELS) {
                sub.fail("Live run streaming is at capacity - poll GET .../runs/{runId} instead.");
                return emitter;
            }
        }
        Channel pending = channels.computeIfAbsent(runId, id -> new Channel(id, tenantId, workflowId));
        if (!Objects.equals(pending.tenantId, tenantId) || !Objects.equals(pending.workflowId, workflowId)) {
            sub.fail("Run not found for this workflow.");
            return emitter;
        }
        synchronized (pending) {
            if (pending.engineAttached) {
                // Raced with runStarted: fall back to the live path.
                return subscribeAttached(pending, sub, lastEventId);
            }
            pending.subscribers.add(sub);
            pending.startPolling(snapshot, poller);
        }
        emitter.onCompletion(() -> pending.subscribers.remove(sub));
        emitter.onTimeout(() -> pending.subscribers.remove(sub));
        emitter.onError(t -> pending.subscribers.remove(sub));
        return emitter;
    }

    private SseEmitter subscribeAttached(Channel channel, Subscriber sub, Long lastEventId) {
        for (Event e : channel.buffer) {
            if (lastEventId == null || e.seq > lastEventId) {
                sub.send(e);
            }
        }
        channel.subscribers.add(sub);
        sub.emitter.onCompletion(() -> channel.subscribers.remove(sub));
        return sub.emitter;
    }

    /** Test seam: unit tests substitute a recording emitter. */
    protected SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    int channelCount() {
        return channels.size();
    }

    private void heartbeatAndEvict() {
        long now = System.currentTimeMillis();
        try {
            for (Channel channel : channels.values()) {
                for (Subscriber sub : channel.subscribers) {
                    sub.heartbeat();
                }
                channel.subscribers.removeIf(sub -> sub.dead);
                boolean endedLongAgo = channel.ended && now - channel.endedAt > RETAIN_ENDED_MS;
                boolean stalePending = !channel.engineAttached && channel.subscribers.isEmpty()
                    && now - channel.createdAt > PENDING_TTL_MS;
                if (endedLongAgo || stalePending) {
                    channel.stopPolling();
                    channels.remove(channel.runId, channel);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Workflow run stream housekeeping failed: {}", e.getMessage());
        }
    }

    private void evictIfOverCapacity() {
        if (channels.size() <= MAX_CHANNELS) {
            return;
        }
        List<Channel> ended = new ArrayList<>();
        for (Channel c : channels.values()) {
            if (c.ended || (!c.engineAttached && c.subscribers.isEmpty())) {
                ended.add(c);
            }
        }
        ended.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        for (Channel c : ended) {
            if (channels.size() <= MAX_CHANNELS) {
                break;
            }
            c.stopPolling();
            channels.remove(c.runId, c);
        }
    }

    record Event(long seq, String type, String json) {
    }

    private final class Channel {
        final String runId;
        final String tenantId;
        final String workflowId;
        final long createdAt = System.currentTimeMillis();
        final ArrayDeque<Event> buffer = new ArrayDeque<>();
        final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        volatile boolean engineAttached;
        volatile boolean ended;
        volatile long endedAt;
        long seq;
        private ScheduledFuture<?> pollTask;
        private String lastPolledFingerprint;

        Channel(String runId, String tenantId, String workflowId) {
            this.runId = runId;
            this.tenantId = tenantId;
            this.workflowId = workflowId;
        }

        void startPolling(WorkflowRunResponse initial, Supplier<WorkflowRunResponse> poller) {
            if (pollTask != null || poller == null) {
                return;
            }
            lastPolledFingerprint = fingerprint(initial);
            pollTask = scheduler.scheduleWithFixedDelay(() -> poll(poller),
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        synchronized void stopPolling() {
            if (pollTask != null) {
                pollTask.cancel(false);
                pollTask = null;
            }
        }

        private void poll(Supplier<WorkflowRunResponse> poller) {
            if (engineAttached || subscribers.isEmpty()) {
                if (!engineAttached && subscribers.isEmpty()) {
                    stopPolling();
                }
                return;
            }
            WorkflowRunResponse run;
            try {
                run = poller.get();
            } catch (RuntimeException e) {
                return;
            }
            if (run == null) {
                return;
            }
            String fp = fingerprint(run);
            synchronized (this) {
                if (engineAttached || fp.equals(lastPolledFingerprint)) {
                    return;
                }
                lastPolledFingerprint = fp;
                for (Subscriber sub : subscribers) {
                    sub.sendSnapshot(run);
                }
                if (TERMINAL.contains(String.valueOf(run.status()))) {
                    for (Subscriber sub : subscribers) {
                        sub.complete();
                    }
                    subscribers.clear();
                    stopPolling();
                }
            }
        }

        void failAll(String message) {
            for (Subscriber sub : subscribers) {
                sub.fail(message);
            }
            subscribers.clear();
            stopPolling();
        }
    }

    private static String fingerprint(WorkflowRunResponse run) {
        if (run == null) {
            return "none";
        }
        return run.status() + "|" + (run.trace() == null ? 0 : run.trace().size());
    }

    private final class Subscriber {
        final SseEmitter emitter;
        volatile boolean dead;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(Event event) {
            write(SseEmitter.event().id(Long.toString(event.seq())).name(event.type())
                .data(event.json(), MediaType.APPLICATION_JSON));
        }

        void sendSnapshot(WorkflowRunResponse run) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "snapshot");
                payload.put("runId", run.id() == null ? null : run.id().toString());
                payload.put("ts", Instant.now());
                payload.put("data", run);
                write(SseEmitter.event().name("snapshot").data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("Could not serialize run snapshot: {}", e.getMessage());
            }
        }

        void heartbeat() {
            write(SseEmitter.event().comment("hb"));
        }

        void fail(String message) {
            try {
                write(SseEmitter.event().name("error").data(
                    mapper.writeValueAsString(Map.of("type", "error", "message", message)), MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {
                // Fall through to complete.
            }
            complete();
        }

        void complete() {
            if (dead) {
                return;
            }
            dead = true;
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed by the container.
            }
        }

        private void write(SseEmitter.SseEventBuilder builder) {
            if (dead) {
                return;
            }
            try {
                emitter.send(builder);
            } catch (IOException | IllegalStateException e) {
                // Client went away; the channel drops us via onError/onCompletion.
                dead = true;
            }
        }
    }
}
