package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON-RPC binding end to end against a mocked {@link AgentChatService}: method-name aliasing
 * across the 0.3/1.0 generations, the error-code matrix, the task lifecycle (blocking, non-blocking
 * + poll, cancel, failure) and the exact SSE frame order the 1.0 spec enforces. Drives {@link
 * A2aJsonRpcController#handle} directly with a {@code MockHttpServletResponse}, the same
 * plain-unit-test convention as {@code EdgeTurnTelemetryControllerTest}.
 */
class A2aJsonRpcControllerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID FORBIDDEN_AGENT_ID = UUID.randomUUID();
    private static final Principal ALICE = () -> "alice";
    private static final ObjectMapper JSON = com.agenticai.core.json.JsonMappers.plain();

    private AgentChatService chat;
    private AuditLogService audit;
    private A2aTaskStore store;
    private A2aJsonRpcController controller;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        audit = mock(AuditLogService.class);
        AgentService agentService = mock(AgentService.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER))
                .thenReturn(mock(Agent.class));
        when(agentService.requireAccess(eq(FORBIDDEN_AGENT_ID), any()))
                .thenThrow(new ResourceAccessDeniedException());

        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTaskService taskService = new A2aTaskService(chat, store, audit, properties);
        controller = new A2aJsonRpcController(taskService, agentService, JSON);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void operationsTheServerDoesNotOfferAnswerWithTheSpecErrorsNotMethodNotFound() {
        for (String method : List.of("ListTasks", "SubscribeToTask", "GetExtendedAgentCard")) {
            assertError(rpc(null, "1.0", taskBody(method, "t-1")), -32004);
        }
        for (String method :
                List.of("CreateTaskPushNotificationConfig", "tasks/pushNotificationConfig/set")) {
            assertError(rpc(null, "1.0", taskBody(method, "t-1")), -32003);
        }
        assertError(rpc(null, "1.0", taskBody("NoSuchMethod", "t-1")), -32601);
    }

    // ------------------------------------------------------------ helpers

    private void replyWith(Flux<String> tokens) {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(tokens);
    }

    private MockHttpServletResponse call(UUID routeAgentId, String version, String body) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            controller.handle(routeAgentId, body, version, ALICE, response);
            return response;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode rpc(UUID routeAgentId, String version, String body) {
        MockHttpServletResponse response = call(routeAgentId, version, body);
        assertEquals(
                200,
                response.getStatus(),
                "JSON-RPC errors travel in the envelope, never the HTTP status");
        try {
            return JSON.readTree(response.getContentAsString());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String sendBody(String method, UUID agentId, String text, String extraParams) {
        String metadata = agentId == null ? "" : ",\"metadata\":{\"agentId\":\"" + agentId + "\"}";
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\""
                + method
                + "\",\"params\":{"
                + "\"message\":{\"messageId\":\"m-1\",\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\""
                + text
                + "\"}]}"
                + metadata
                + (extraParams == null ? "" : "," + extraParams)
                + "}}";
    }

    private static String taskBody(String method, String taskId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"req-9\",\"method\":\""
                + method
                + "\",\"params\":{\"id\":\""
                + taskId
                + "\"}}";
    }

    private static void assertError(JsonNode envelope, int code) {
        assertTrue(envelope.has("error"), "expected an error envelope, got " + envelope);
        assertEquals(code, envelope.get("error").get("code").asInt(), envelope.toString());
        assertFalse(envelope.has("result"));
    }

    private static List<JsonNode> sseFrames(MockHttpServletResponse response) {
        assertEquals("text/event-stream", response.getContentType().split(";")[0].trim());
        List<JsonNode> frames = new ArrayList<>();
        try {
            for (String block : response.getContentAsString().split("\n\n")) {
                if (block.isBlank()) {
                    continue;
                }
                assertTrue(block.startsWith("data: "), "every frame is data-only: " + block);
                frames.add(JSON.readTree(block.substring("data: ".length())));
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return frames;
    }

    private JsonNode pollUntilTerminal(String taskId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode task = rpc(null, "1.0", taskBody("GetTask", taskId)).get("result");
            String state = task.get("status").get("state").asString();
            if (!state.equals("TASK_STATE_SUBMITTED") && !state.equals("TASK_STATE_WORKING")) {
                return task;
            }
            Thread.sleep(20);
        }
        return fail("task " + taskId + " never reached a terminal state");
    }

    // ----------------------------------------------------- happy path, 1.0

    @Test
    void blockingSendMessageReturnsACompletedTaskInThe10Dialect() {
        replyWith(Flux.just("Hel", "lo"));

        MockHttpServletResponse response =
                call(null, "1.0", sendBody("SendMessage", AGENT_ID, "hi there", null));
        assertEquals("1.0", response.getHeader("A2A-Version"));
        JsonNode envelope = readJson(response);

        assertEquals(7, envelope.get("id").asInt());
        JsonNode task = envelope.get("result").get("task");
        assertNotNull(task, "1.0 wraps the result in {task: ...}");
        assertFalse(task.has("kind"), "no 0.3 discriminators in the 1.0 dialect");
        assertEquals("TASK_STATE_COMPLETED", task.get("status").get("state").asString());
        assertNotNull(task.get("status").get("timestamp"));
        assertEquals(
                "Hello", task.get("artifacts").get(0).get("parts").get(0).get("text").asString());
        assertEquals("response", task.get("artifacts").get(0).get("name").asString());
        assertEquals("ROLE_USER", task.get("history").get(0).get("role").asString());
        assertEquals("m-1", task.get("history").get(0).get("messageId").asString());
        assertEquals("ROLE_AGENT", task.get("history").get(1).get("role").asString());
        assertEquals(
                "Hello", task.get("history").get(1).get("parts").get(0).get("text").asString());
        assertEquals(AGENT_ID.toString(), task.get("metadata").get("agentId").asString());
        // contextId minted server-side when the client sent none, and it is the chat sessionId.
        String contextId = task.get("contextId").asString();
        verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), contextId)
                                                && java.util.Objects.equals(r.subjectKey(), "alice")
                                                && java.util.Objects.equals(
                                                        r.userMessage(), "hi there")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
    }

    @Test
    void absentVersionHeaderMeansThe03Dialect() {
        replyWith(Flux.just("ok"));

        MockHttpServletResponse response =
                call(null, null, sendBody("message/send", AGENT_ID, "hi", null));
        assertEquals("0.3", response.getHeader("A2A-Version"));
        JsonNode task = readJson(response).get("result");

        assertEquals(
                "task",
                task.get("kind").asString(),
                "0.3 result is the bare Task with its kind discriminator");
        assertEquals("completed", task.get("status").get("state").asString());
        assertEquals(
                "text", task.get("artifacts").get(0).get("parts").get(0).get("kind").asString());
        assertEquals("user", task.get("history").get(0).get("role").asString());
        assertEquals("message", task.get("history").get(0).get("kind").asString());
        assertEquals("agent", task.get("history").get(1).get("role").asString());
    }

    @Test
    void clientSuppliedContextIdIsTheChatSessionIdAndHistoryLengthIsHonored() {
        replyWith(Flux.just("ok"));
        String body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"contextId\":\"conv-42\",\"parts\":[{\"text\":\"again\"}]},"
                        + "\"configuration\":{\"historyLength\":1},\"metadata\":{\"agentId\":\""
                        + AGENT_ID
                        + "\"}}}";

        JsonNode task = rpc(null, "1.0", body).get("result").get("task");

        assertEquals("conv-42", task.get("contextId").asString());
        verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "conv-42")
                                                && java.util.Objects.equals(r.subjectKey(), "alice")
                                                && java.util.Objects.equals(
                                                        r.userMessage(), "again")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertEquals(1, task.get("history").size(), "historyLength keeps the most recent N");
        assertEquals("ROLE_AGENT", task.get("history").get(0).get("role").asString());
    }

    // ------------------------------------------------------- method aliases

    @Test
    void bothMethodNameGenerationsReachTheSameHandlersRegardlessOfHeader() {
        replyWith(Flux.just("ok"));

        // 1.0 header + 0.3 names, and 0.3 header + 1.0 names: both must work.
        String v1Task =
                rpc(null, "1.0", sendBody("message/send", AGENT_ID, "a", null))
                        .get("result")
                        .get("task")
                        .get("id")
                        .asString();
        String v03Task =
                rpc(null, null, sendBody("SendMessage", AGENT_ID, "b", null))
                        .get("result")
                        .get("id")
                        .asString();
        assertEquals(
                "TASK_STATE_COMPLETED",
                rpc(null, "1.0", taskBody("tasks/get", v1Task))
                        .get("result")
                        .get("status")
                        .get("state")
                        .asString());
        assertEquals(
                "completed",
                rpc(null, null, taskBody("GetTask", v03Task))
                        .get("result")
                        .get("status")
                        .get("state")
                        .asString());
        // Cancel on a finished task is TaskNotCancelable under either name.
        assertError(
                rpc(null, "1.0", taskBody("tasks/cancel", v1Task)), A2aError.TASK_NOT_CANCELABLE);
        assertError(rpc(null, null, taskBody("CancelTask", v03Task)), A2aError.TASK_NOT_CANCELABLE);
    }

    // ---------------------------------------------------------- error codes

    @Test
    void malformedJsonIsAParseError() {
        JsonNode envelope = rpc(null, "1.0", "{not json");
        assertError(envelope, A2aError.PARSE_ERROR);
        assertTrue(envelope.get("id").isNull());
    }

    @Test
    void batchesAndNonObjectsAndWrongJsonrpcAreInvalidRequests() {
        assertError(rpc(null, "1.0", "[]"), A2aError.INVALID_REQUEST);
        assertError(rpc(null, "1.0", "\"hello\""), A2aError.INVALID_REQUEST);
        assertError(
                rpc(null, "1.0", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"GetTask\"}"),
                A2aError.INVALID_REQUEST);
        assertError(rpc(null, "1.0", "{\"jsonrpc\":\"2.0\",\"id\":1}"), A2aError.INVALID_REQUEST);
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        JsonNode envelope =
                rpc(
                        null,
                        "1.0",
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/resubscribe\",\"params\":{}}");
        assertError(envelope, A2aError.METHOD_NOT_FOUND);
        assertEquals(3, envelope.get("id").asInt(), "the request id is echoed even on errors");
    }

    @Test
    void missingMessageOrAgentIdOrTextIsInvalidParams() {
        assertError(
                rpc(
                        null,
                        "1.0",
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{}}"),
                A2aError.INVALID_PARAMS);
        // No route agent, no metadata.agentId.
        assertError(
                rpc(null, "1.0", sendBody("SendMessage", null, "hi", null)),
                A2aError.INVALID_PARAMS);
        assertError(
                rpc(null, "1.0", sendBody("SendMessage", AGENT_ID, "   ", null)),
                A2aError.INVALID_PARAMS);
        assertError(rpc(null, "1.0", taskBody("GetTask", "")), A2aError.INVALID_PARAMS);
        verify(chat, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void filePartsAreContentTypeNotSupported() {
        String body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"messageId\":\"m\",\"role\":\"user\",\"parts\":[{\"kind\":\"file\",\"file\":{\"uri\":\"https://x/y.pdf\"}}]},"
                        + "\"metadata\":{\"agentId\":\""
                        + AGENT_ID
                        + "\"}}}";
        assertError(rpc(null, "1.0", body), A2aError.CONTENT_TYPE_NOT_SUPPORTED);
    }

    @Test
    void unknownTaskIsTaskNotFound() {
        JsonNode envelope = rpc(null, "1.0", taskBody("GetTask", "does-not-exist"));
        assertError(envelope, A2aError.TASK_NOT_FOUND);
        assertEquals("does-not-exist", envelope.get("error").get("data").get("taskId").asString());
        assertError(
                rpc(null, "1.0", taskBody("CancelTask", "does-not-exist")),
                A2aError.TASK_NOT_FOUND);
    }

    @Test
    void unsupportedVersionHeaderIsVersionNotSupported() {
        assertError(
                rpc(null, "2.7", sendBody("SendMessage", AGENT_ID, "hi", null)),
                A2aError.VERSION_NOT_SUPPORTED);
        verify(chat, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void agentTheCallerCannotUseLooksIdenticalToANonexistentOne() {
        JsonNode envelope =
                rpc(null, "1.0", sendBody("SendMessage", FORBIDDEN_AGENT_ID, "hi", null));
        assertError(envelope, A2aError.INVALID_PARAMS);
        assertEquals(
                "Invalid params: unknown agentId", envelope.get("error").get("message").asString());
        verify(chat, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void multiTurnTaskContinuationIsRejectedWithGuidance() {
        String body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"messageId\":\"m\",\"role\":\"user\",\"taskId\":\"t-1\",\"parts\":[{\"text\":\"more\"}]},"
                        + "\"metadata\":{\"agentId\":\""
                        + AGENT_ID
                        + "\"}}}";
        JsonNode envelope = rpc(null, "1.0", body);
        assertError(envelope, A2aError.INVALID_PARAMS);
        assertTrue(envelope.get("error").get("message").asString().contains("contextId"));
    }

    // ------------------------------------------------------ agent-scoped route

    @Test
    void agentScopedRouteFixesTheAgentAndRejectsAMismatchedMetadataAgentId() {
        replyWith(Flux.just("ok"));

        JsonNode task =
                rpc(AGENT_ID, "1.0", sendBody("SendMessage", null, "hi", null))
                        .get("result")
                        .get("task");
        assertEquals(AGENT_ID.toString(), task.get("metadata").get("agentId").asString());
        assertError(
                rpc(AGENT_ID, "1.0", sendBody("SendMessage", UUID.randomUUID(), "hi", null)),
                A2aError.INVALID_PARAMS);
    }

    // ------------------------------------------------------- task lifecycle

    @Test
    void nonBlockingSendReturnsAtOnceAndGetTaskEventuallyCompletes() throws Exception {
        replyWith(Flux.just("slow", "ly").delayElements(Duration.ofMillis(40)));

        JsonNode submitted =
                rpc(
                                null,
                                "1.0",
                                sendBody(
                                        "SendMessage",
                                        AGENT_ID,
                                        "hi",
                                        "\"configuration\":{\"returnImmediately\":true}"))
                        .get("result")
                        .get("task");
        String state = submitted.get("status").get("state").asString();
        assertTrue(
                state.equals("TASK_STATE_SUBMITTED") || state.equals("TASK_STATE_WORKING"), state);
        JsonNode done = pollUntilTerminal(submitted.get("id").asString());
        assertEquals("TASK_STATE_COMPLETED", done.get("status").get("state").asString());
        assertEquals(
                "slowly", done.get("artifacts").get(0).get("parts").get(0).get("text").asString());
    }

    @Test
    void zeroThreeBlockingFalseIsTheSameOptOut() throws Exception {
        replyWith(Flux.just("x").delayElements(Duration.ofMillis(40)));

        JsonNode submitted =
                rpc(
                                null,
                                null,
                                sendBody(
                                        "message/send",
                                        AGENT_ID,
                                        "hi",
                                        "\"configuration\":{\"blocking\":false}"))
                        .get("result");
        String state = submitted.get("status").get("state").asString();
        assertTrue(state.equals("submitted") || state.equals("working"), state);
        assertEquals(
                "TASK_STATE_COMPLETED",
                pollUntilTerminal(submitted.get("id").asString())
                        .get("status")
                        .get("state")
                        .asString());
    }

    @Test
    void cancelCutsARunningTurnAndASecondCancelIsNotCancelable() throws Exception {
        // Emits one token, then hangs forever - only a cancel can end it.
        replyWith(Flux.concat(Flux.just("partial"), Flux.never()));

        JsonNode submitted =
                rpc(
                                null,
                                "1.0",
                                sendBody(
                                        "SendMessage",
                                        AGENT_ID,
                                        "hi",
                                        "\"configuration\":{\"returnImmediately\":true}"))
                        .get("result")
                        .get("task");
        String taskId = submitted.get("id").asString();
        // Let the async subscription start and deliver the first token.
        for (int i = 0;
                i < 100
                        && store.find(taskId, null, "alice")
                                .orElseThrow()
                                .snapshot()
                                .reply()
                                .isEmpty();
                i++) {
            Thread.sleep(10);
        }

        JsonNode canceled = rpc(null, "1.0", taskBody("CancelTask", taskId)).get("result");
        assertEquals("TASK_STATE_CANCELED", canceled.get("status").get("state").asString());
        assertEquals(
                "partial",
                canceled.get("artifacts").get(0).get("parts").get(0).get("text").asString());
        assertEquals(
                1, canceled.get("history").size(), "no agent reply in history for a canceled task");

        assertError(rpc(null, "1.0", taskBody("CancelTask", taskId)), A2aError.TASK_NOT_CANCELABLE);
        assertEquals(
                "TASK_STATE_CANCELED",
                rpc(null, "1.0", taskBody("GetTask", taskId))
                        .get("result")
                        .get("status")
                        .get("state")
                        .asString());
    }

    @Test
    void upstreamFailureBecomesAFailedTaskWithAConsumerSafeMessage() {
        replyWith(
                Flux.concat(
                        Flux.just("part"),
                        Flux.error(new IllegalStateException("provider 500: internal detail"))));

        JsonNode task =
                rpc(null, "1.0", sendBody("SendMessage", AGENT_ID, "hi", null))
                        .get("result")
                        .get("task");
        assertEquals("TASK_STATE_FAILED", task.get("status").get("state").asString());
        String reason =
                task.get("status").get("message").get("parts").get(0).get("text").asString();
        assertEquals("Something went wrong finishing this reply. Please try again.", reason);
        assertFalse(
                reason.contains("internal detail"),
                "raw exception text never reaches a peer agent");
        assertEquals("ROLE_AGENT", task.get("status").get("message").get("role").asString());
        assertEquals(1, task.get("history").size());
        verify(audit)
                .record(
                        eq("alice"),
                        eq(A2aTaskService.AUDIT_ACTION),
                        eq("Agent"),
                        eq(AGENT_ID.toString()),
                        eq(com.agenticai.core.audit.AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void synchronousChatFailureIsAFailedTaskToo() {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenThrow(new IllegalStateException("tenant disabled"));

        JsonNode task =
                rpc(null, "1.0", sendBody("SendMessage", AGENT_ID, "hi", null))
                        .get("result")
                        .get("task");
        assertEquals("TASK_STATE_FAILED", task.get("status").get("state").asString());
    }

    @Test
    void tasksAreScopedToTheTenantThatCreatedThem() {
        replyWith(Flux.just("secret reply"));

        TenantContext.set(TenantContext.of("tenant_a", "alice", Set.of()));
        String taskId =
                rpc(null, "1.0", sendBody("SendMessage", AGENT_ID, "hi", null))
                        .get("result")
                        .get("task")
                        .get("id")
                        .asString();
        TenantContext.set(TenantContext.of("tenant_b", "alice", Set.of()));
        assertError(rpc(null, "1.0", taskBody("GetTask", taskId)), A2aError.TASK_NOT_FOUND);
        assertError(rpc(null, "1.0", taskBody("CancelTask", taskId)), A2aError.TASK_NOT_FOUND);

        TenantContext.set(TenantContext.of("tenant_a", "alice", Set.of()));
        assertEquals(
                "secret reply",
                rpc(null, "1.0", taskBody("GetTask", taskId))
                        .get("result")
                        .get("artifacts")
                        .get(0)
                        .get("parts")
                        .get(0)
                        .get("text")
                        .asString());
    }

    // ------------------------------------------------------------ streaming

    @Test
    void streamingEmitsFramesInTheOrderThe10SpecEnforces() {
        replyWith(Flux.just("Hel", "lo"));

        MockHttpServletResponse response =
                call(null, "1.0", sendBody("message/stream", AGENT_ID, "hi", null));
        assertEquals("1.0", response.getHeader("A2A-Version"));
        List<JsonNode> frames = sseFrames(response);

        assertEquals(6, frames.size(), frames.toString());
        frames.forEach(frame -> assertEquals(7, frame.get("id").asInt()));

        JsonNode task = frames.get(0).get("result").get("task");
        assertEquals("TASK_STATE_SUBMITTED", task.get("status").get("state").asString());
        String taskId = task.get("id").asString();

        JsonNode working = frames.get(1).get("result").get("statusUpdate");
        assertEquals("TASK_STATE_WORKING", working.get("status").get("state").asString());
        assertEquals(taskId, working.get("taskId").asString());
        assertFalse(working.get("final").asBoolean());

        JsonNode first = frames.get(2).get("result").get("artifactUpdate");
        assertEquals("Hel", first.get("artifact").get("parts").get(0).get("text").asString());
        assertFalse(first.get("append").asBoolean(), "first chunk starts the artifact");
        assertFalse(first.get("lastChunk").asBoolean());
        String artifactId = first.get("artifact").get("artifactId").asString();

        JsonNode second = frames.get(3).get("result").get("artifactUpdate");
        assertEquals("lo", second.get("artifact").get("parts").get(0).get("text").asString());
        assertTrue(second.get("append").asBoolean());
        assertEquals(
                artifactId,
                second.get("artifact").get("artifactId").asString(),
                "one artifact across all chunks");

        JsonNode last = frames.get(4).get("result").get("artifactUpdate");
        assertEquals("", last.get("artifact").get("parts").get(0).get("text").asString());
        assertTrue(last.get("lastChunk").asBoolean());

        JsonNode completed = frames.get(5).get("result").get("statusUpdate");
        assertEquals("TASK_STATE_COMPLETED", completed.get("status").get("state").asString());
        assertTrue(completed.get("final").asBoolean());

        // And the finished task is retrievable afterwards with the full text.
        assertEquals(
                "Hello",
                rpc(null, "1.0", taskBody("GetTask", taskId))
                        .get("result")
                        .get("artifacts")
                        .get(0)
                        .get("parts")
                        .get(0)
                        .get("text")
                        .asString());
    }

    @Test
    void streamingInThe03DialectUsesKindDiscriminatorsAndBareEvents() {
        replyWith(Flux.just("ok"));

        List<JsonNode> frames =
                sseFrames(call(null, null, sendBody("SendStreamingMessage", AGENT_ID, "hi", null)));
        assertEquals("task", frames.get(0).get("result").get("kind").asString());
        assertEquals(
                "submitted", frames.get(0).get("result").get("status").get("state").asString());
        assertEquals("status-update", frames.get(1).get("result").get("kind").asString());
        assertEquals("working", frames.get(1).get("result").get("status").get("state").asString());
        assertEquals("artifact-update", frames.get(2).get("result").get("kind").asString());
        assertEquals(
                "text",
                frames.get(2)
                        .get("result")
                        .get("artifact")
                        .get("parts")
                        .get(0)
                        .get("kind")
                        .asString());
        JsonNode terminal = frames.get(frames.size() - 1).get("result");
        assertEquals("status-update", terminal.get("kind").asString());
        assertEquals("completed", terminal.get("status").get("state").asString());
        assertTrue(terminal.get("final").asBoolean());
    }

    @Test
    void streamingFailureEndsWithASingleFinalFailedStatus() {
        replyWith(Flux.error(new IllegalStateException("boom")));

        List<JsonNode> frames =
                sseFrames(call(null, "1.0", sendBody("message/stream", AGENT_ID, "hi", null)));

        // SUBMITTED task, then exactly one terminal status - no WORKING frame
        // for a turn that never got going, no lastChunk artifact for no artifact.
        assertEquals(2, frames.size(), frames.toString());
        JsonNode failed = frames.get(1).get("result").get("statusUpdate");
        assertEquals("TASK_STATE_FAILED", failed.get("status").get("state").asString());
        assertTrue(failed.get("final").asBoolean());
        assertNotNull(failed.get("status").get("message"));
    }

    @Test
    void streamingParamErrorsAreAJsonErrorNotAStream() {
        MockHttpServletResponse response =
                call(
                        null,
                        "1.0",
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendStreamingMessage\",\"params\":{}}");
        assertEquals("application/json", response.getContentType().split(";")[0].trim());
        assertError(readJson(response), A2aError.INVALID_PARAMS);
    }

    private static JsonNode readJson(MockHttpServletResponse response) {
        try {
            return JSON.readTree(response.getContentAsString());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
