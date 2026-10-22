package com.agenticai.core.workflow.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkflowRunStreamHubTest {

    private static final ObjectMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .configureForJackson2()
                    .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    /** Records what the hub writes instead of needing a servlet response. */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> events = new ArrayList<>();
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder frame = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                frame.append(part.getData());
            }
            events.add(frame.toString());
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        List<String> named(String type) {
            return events.stream().filter(e -> e.contains("event:" + type + "\n")).toList();
        }

        JsonNode lastData(String type) throws Exception {
            List<String> frames = named(type);
            String frame = frames.get(frames.size() - 1);
            String data = frame.substring(frame.indexOf("data:") + 5).trim();
            return MAPPER.readTree(data);
        }
    }

    private final List<RecordingEmitter> emitters = new ArrayList<>();
    private WorkflowRunStreamHub hub;

    @BeforeEach
    void setUp() {
        hub =
                new WorkflowRunStreamHub(MAPPER) {
                    @Override
                    protected SseEmitter newEmitter() {
                        RecordingEmitter e = new RecordingEmitter();
                        emitters.add(e);
                        return e;
                    }
                };
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    private RecordingEmitter subscribe(
            String tenant, String wf, String run, Long last, WorkflowRunResponse snap) {
        return (RecordingEmitter) hub.subscribe(tenant, wf, run, last, snap, () -> snap);
    }

    private static WorkflowRunResponse run(String runId, String wf, WorkflowRunStatus status) {
        return new WorkflowRunResponse(
                UUID.fromString(runId),
                UUID.fromString(wf),
                "wf",
                status,
                null,
                null,
                List.of(),
                Instant.now(),
                null,
                null,
                null);
    }

    @Test
    void liveSubscriberGetsEventsInOrderAndStreamClosesOnFinish() throws Exception {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        hub.runStarted(runId, "acme", wf, false);
        RecordingEmitter sub =
                subscribe("acme", wf, runId, null, run(runId, wf, WorkflowRunStatus.RUNNING));

        hub.publish(runId, "node.started", "n1", Map.of("label", "Agent"));
        hub.runSettled(runId, "COMPLETED", Map.of("status", "COMPLETED"));

        assertEquals(1, sub.named("snapshot").size());
        assertEquals(
                1,
                sub.named("run.started").size(),
                "buffered run.started replays to a late subscriber");
        assertEquals("n1", sub.lastData("node.started").path("nodeId").asString());
        assertEquals(
                "COMPLETED", sub.lastData("run.finished").path("data").path("status").asString());
        assertTrue(sub.completed);
    }

    @Test
    void lastEventIdReplaysOnlyWhatWasMissed() throws Exception {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        hub.runStarted(runId, "acme", wf, false); // seq 1
        hub.publish(runId, "node.started", "a", Map.of()); // seq 2
        hub.publish(runId, "node.started", "b", Map.of()); // seq 3

        RecordingEmitter sub = subscribe("acme", wf, runId, 2L, null);

        assertEquals(0, sub.named("run.started").size());
        assertEquals(1, sub.named("node.started").size());
        assertEquals("b", sub.lastData("node.started").path("nodeId").asString());
    }

    @Test
    void anotherTenantOrWorkflowNeverSeesTheRun() {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        hub.runStarted(runId, "acme", wf, false);

        RecordingEmitter otherTenant = subscribe("evil", wf, runId, null, null);
        RecordingEmitter otherWorkflow =
                subscribe("acme", UUID.randomUUID().toString(), runId, null, null);
        hub.publish(runId, "node.token", "n1", Map.of("delta", "secret-ish"));

        for (RecordingEmitter e : List.of(otherTenant, otherWorkflow)) {
            assertEquals(1, e.named("error").size());
            assertTrue(e.named("node.token").isEmpty());
            assertTrue(e.completed);
        }
    }

    @Test
    void subscribingBeforeTheRunStartsReceivesItLive() {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        RecordingEmitter early = subscribe("acme", wf, runId, null, null);

        hub.runStarted(runId, "acme", wf, false);
        hub.publish(runId, "node.started", "n1", Map.of());

        assertEquals(1, early.named("run.started").size());
        assertEquals(1, early.named("node.started").size());
    }

    @Test
    void pendingSubscriptionIsDroppedWhenTheRunBelongsToSomeoneElse() {
        String runId = UUID.randomUUID().toString();
        RecordingEmitter squatter =
                subscribe("acme", UUID.randomUUID().toString(), runId, null, null);

        hub.runStarted(runId, "acme", UUID.randomUUID().toString(), false);
        hub.publish(runId, "node.token", "n1", Map.of("delta", "x"));

        assertEquals(1, squatter.named("error").size());
        assertTrue(squatter.named("node.token").isEmpty());
        assertTrue(squatter.completed);
    }

    @Test
    void awaitingApprovalKeepsTheStreamOpenAndResumeContinuesIt() throws Exception {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        hub.runStarted(runId, "acme", wf, false);
        RecordingEmitter sub = subscribe("acme", wf, runId, null, null);

        hub.runSettled(
                runId,
                "AWAITING_APPROVAL",
                Map.of("status", "AWAITING_APPROVAL", "nodeId", "gate"));
        assertFalse(sub.completed);
        assertEquals("gate", sub.lastData("run.awaiting").path("data").path("nodeId").asString());

        hub.runStarted(runId, "acme", wf, true);
        assertEquals(1, sub.named("run.resumed").size());
    }

    @Test
    void finishedRunNotLiveHereGetsSnapshotAndCloses() {
        String runId = UUID.randomUUID().toString();
        String wf = UUID.randomUUID().toString();
        RecordingEmitter sub =
                subscribe("acme", wf, runId, null, run(runId, wf, WorkflowRunStatus.COMPLETED));
        assertEquals(1, sub.named("snapshot").size());
        assertTrue(sub.completed);
    }

    @Test
    void noChannelMeansNoOpSinkAndNoEvents() {
        NodeStreamSink sink = hub.nodeSink(UUID.randomUUID().toString(), "n1");
        assertFalse(sink.isLive());
        hub.publish("nope", "node.token", "n1", Map.of());
        assertEquals(0, hub.channelCount());
    }

    // --- NodeStreamSink --------------------------------------------------

    private record Published(String type, String nodeId, Map<String, ?> data) {}

    private static final class CapturingHub extends WorkflowRunStreamHub {
        final List<Published> published = new ArrayList<>();

        CapturingHub() {
            super(MAPPER);
        }

        @Override
        public void publish(String runId, String type, String nodeId, Map<String, ?> data) {
            published.add(new Published(type, nodeId, data));
        }

        String streamedText() {
            StringBuilder text = new StringBuilder();
            for (Published p : published) {
                if (!"node.token".equals(p.type())) {
                    continue;
                }
                if (Boolean.TRUE.equals(p.data().get("reset"))) {
                    text.setLength(0);
                    text.append(p.data().get("text"));
                } else if (p.data().get("delta") != null) {
                    text.append(p.data().get("delta"));
                }
            }
            return text.toString();
        }
    }

    @Test
    void sinkStreamsTextAndReleasesTheHeldBackTailOnClose() {
        CapturingHub capturing = new CapturingHub();
        AtomicLong clock = new AtomicLong(0);
        NodeStreamSink sink = new NodeStreamSink(capturing, "r", "n1", clock::get);
        String reply = "The quarterly numbers look strong. ".repeat(10);
        for (String word : reply.split("(?<= )")) {
            clock.addAndGet(100);
            sink.token(word);
        }
        assertTrue(
                capturing.streamedText().length()
                        <= reply.length() - NodeStreamSink.HOLDBACK_CHARS + 40);
        sink.close();
        assertEquals(reply, capturing.streamedText());
        capturing.shutdown();
    }

    @Test
    void aSecretArrivingInPiecesIsNeverStreamed() {
        CapturingHub capturing = new CapturingHub();
        AtomicLong clock = new AtomicLong(0);
        NodeStreamSink sink = new NodeStreamSink(capturing, "r", "n1", clock::get);
        String key = "sk-" + "Z".repeat(40);
        List<String> chunks =
                List.of(
                        "Here is the key: ",
                        key.substring(0, 6),
                        key.substring(6, 20),
                        key.substring(20),
                        " - keep it safe. ",
                        "Lots more text follows here ".repeat(6));
        for (String chunk : chunks) {
            clock.addAndGet(100);
            sink.token(chunk);
        }
        sink.close();
        for (Published p : capturing.published) {
            assertFalse(String.valueOf(p.data()).contains("ZZZZZZZZZZ"), String.valueOf(p.data()));
        }
        assertTrue(capturing.streamedText().contains("[redacted]"));
        capturing.shutdown();
    }

    @Test
    void pemBlockIsWithheldUntilItCloses() {
        assertEquals(
                "before ",
                NodeStreamSink.stablePrefix(
                        "before -----BEGIN PRIVATE KEY-----\nabc" + "x".repeat(200)));
    }

    @Test
    void stepEventsBecomeToolRowEvents() {
        CapturingHub capturing = new CapturingHub();
        NodeStreamSink sink = new NodeStreamSink(capturing, "r", "n1", System::currentTimeMillis);
        sink.stepEvent(
                AgentStepEventEmitter.STEP_EVENT_MARKER
                        + "{\"type\":\"tool_call\",\"id\":1,\"tool\":\"web_search\",\"args\":\"{}\"}");
        sink.stepEvent(
                AgentStepEventEmitter.STEP_EVENT_MARKER
                        + "{\"type\":\"tool_result\",\"id\":1,\"tool\":\"web_search\",\"preview\":\"ok\",\"ms\":12}");
        sink.stepEvent(AgentStepEventEmitter.STEP_EVENT_MARKER + "{\"type\":\"heartbeat\"}");
        sink.stepEvent(
                AgentStepEventEmitter.STEP_EVENT_MARKER
                        + "{\"type\":\"plan\",\"text\":\"step 1\"}");
        List<String> types = capturing.published.stream().map(Published::type).toList();
        assertEquals(List.of("node.tool_call", "node.tool_result", "node.agent_event"), types);
        assertEquals("web_search", capturing.published.get(0).data().get("tool"));
        assertEquals("plan", capturing.published.get(2).data().get("kind"));
        capturing.shutdown();
    }

    @Test
    void noopSinkIgnoresEverything() {
        NodeStreamSink.NOOP.token("x");
        NodeStreamSink.NOOP.stepEvent(
                AgentStepEventEmitter.STEP_EVENT_MARKER + "{\"type\":\"tool_call\"}");
        NodeStreamSink.NOOP.close();
        assertFalse(NodeStreamSink.NOOP.isLive());
    }
}
