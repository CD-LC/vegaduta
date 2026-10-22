package com.agenticai.core.workflow.stream;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.agenticai.core.workflow.NodeTracePayloads;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One node execution's live output channel: token deltas and the agent's tool-call steps, published
 * to {@link WorkflowRunStreamHub} as {@code node.token} / {@code node.tool_call} / {@code
 * node.tool_result} / {@code node.tool_error} / {@code node.agent_event} events.
 *
 * <h2>Tokens are redacted before they stream, not after</h2>
 *
 * The persisted trace redacts a node's output ({@link NodeTracePayloads}), and a live stream that
 * skipped that would hand a VIEWER exactly what the trace hides. Chunk-by-chunk redaction cannot
 * work (a key arrives split across chunks), so this keeps the raw text, re-redacts the WHOLE
 * accumulated text on each flush, and only releases what is stable:
 *
 * <ul>
 *   <li>the last {@link #HOLDBACK_CHARS} chars are withheld until the node ends - long enough to
 *       cover every credential shape the redactor knows (an {@code sk-} key, a GitHub token) before
 *       it is complete;
 *   <li>everything from an unclosed {@code -----BEGIN} (a PEM block still streaming) is withheld
 *       until its END line or the node ends.
 * </ul>
 *
 * When the redacted text still extends what was already sent, a plain {@code delta} goes out. If
 * redaction rewrote something already sent (rare - e.g. a value that only became recognisable
 * later) the event carries {@code reset: true} and the full {@code text} so the client replaces its
 * buffer. The secret itself was never sent in that case either: it sat in the held-back tail.
 *
 * <p>Flushes are throttled to one per {@link #FLUSH_INTERVAL_MS} so a fast model does not produce
 * hundreds of events a second, and the stream stops (with {@code capped: true}) past {@link
 * #MAX_STREAM_CHARS}; the node's full output still lands in the trace when it completes.
 */
public class NodeStreamSink {

    public static final NodeStreamSink NOOP =
            new NodeStreamSink(null, null, null, System::currentTimeMillis);

    static final int HOLDBACK_CHARS = 96;
    static final long FLUSH_INTERVAL_MS = 80;
    static final int MAX_STREAM_CHARS = 64 * 1024;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkflowRunStreamHub hub;
    private final String runId;
    private final String nodeId;
    private final LongSupplier clock;

    private final StringBuilder raw = new StringBuilder();
    private String sent = "";
    private long lastFlushAt;
    private boolean capped;
    private boolean closed;

    NodeStreamSink(WorkflowRunStreamHub hub, String runId, String nodeId, LongSupplier clock) {
        this.hub = hub;
        this.runId = runId;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    /** False for {@link #NOOP}: callers skip stream-only work (e.g. activating step events). */
    public boolean isLive() {
        return hub != null;
    }

    /** One chunk of model output as it arrives. Safe from any thread. */
    public synchronized void token(String chunk) {
        if (hub == null || closed || capped || chunk == null || chunk.isEmpty()) {
            return;
        }
        // Step-event chunks never belong in the text; they have their own path.
        if (chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)) {
            stepEvent(chunk);
            return;
        }
        raw.append(chunk);
        if (raw.length() > MAX_STREAM_CHARS) {
            capped = true;
            flush(true);
            hub.publish(runId, "node.token", nodeId, Map.of("capped", true));
            return;
        }
        long now = clock.getAsLong();
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            lastFlushAt = now;
            flush(false);
        }
    }

    /** Releases the held-back tail. Called once by the engine when the node's execution ends. */
    public synchronized void close() {
        if (hub == null || closed) {
            return;
        }
        if (!capped) {
            flush(true);
        }
        closed = true;
    }

    /**
     * A chunk from {@link AgentStepEventEmitter#asFlux()}: the marker plus a single-line JSON
     * event. The emitter already redacted arguments and previews, so fields are forwarded as they
     * are.
     */
    public void stepEvent(String markerChunk) {
        if (hub == null
                || markerChunk == null
                || !markerChunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)) {
            return;
        }
        JsonNode event;
        try {
            event =
                    MAPPER.readTree(
                            markerChunk.substring(
                                    AgentStepEventEmitter.STEP_EVENT_MARKER.length()));
        } catch (Exception e) {
            return;
        }
        String type = event.path("type").asString("");
        if ("heartbeat".equals(type)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        JsonNodes.fields(event)
                .forEachRemaining(
                        f -> {
                            if (!"type".equals(f.getKey())) {
                                data.put(
                                        f.getKey(),
                                        MAPPER.convertValue(f.getValue(), Object.class));
                            }
                        });
        switch (type) {
            case "tool_call" -> hub.publish(runId, "node.tool_call", nodeId, data);
            case "tool_result" -> hub.publish(runId, "node.tool_result", nodeId, data);
            case "error" -> hub.publish(runId, "node.tool_error", nodeId, data);
            default -> {
                data.put("kind", type);
                hub.publish(runId, "node.agent_event", nodeId, data);
            }
        }
    }

    private void flush(boolean finalFlush) {
        String redacted = NodeTracePayloads.redact(raw.toString());
        String releasable = finalFlush ? redacted : stablePrefix(redacted);
        if (releasable.equals(sent)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (releasable.startsWith(sent)) {
            data.put("delta", releasable.substring(sent.length()));
        } else {
            data.put("reset", true);
            data.put("text", releasable);
        }
        sent = releasable;
        hub.publish(runId, "node.token", nodeId, data);
    }

    static String stablePrefix(String redacted) {
        int end = Math.max(0, redacted.length() - HOLDBACK_CHARS);
        int pem = redacted.lastIndexOf("-----BEGIN");
        if (pem >= 0 && redacted.indexOf("-----END", pem) < 0) {
            end = Math.min(end, pem);
        }
        return redacted.substring(0, end);
    }
}
