package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

/**
 * The A2A 1.0 HTTP+JSON/REST binding (spec section 11): success shapes, the section 5.4 HTTP status
 * mapping, the google.rpc.Status error body with an ErrorInfo detail, SSE framing without a
 * JSON-RPC envelope, and the operations this server answers as unsupported.
 */
class A2aRestControllerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID FORBIDDEN_AGENT_ID = UUID.randomUUID();
    private static final Principal ALICE = () -> "alice";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String V1 = "1.0";

    private AgentChatService chat;
    private A2aRestController rest;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        AgentService agentService = mock(AgentService.class);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER))
                .thenReturn(mock(Agent.class));
        when(agentService.requireAccess(eq(FORBIDDEN_AGENT_ID), any()))
                .thenThrow(new ResourceAccessDeniedException());
        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        A2aTaskService taskService =
                new A2aTaskService(
                        chat,
                        new A2aTaskStore(Duration.ofHours(1), 100),
                        mock(AuditLogService.class),
                        properties);
        A2aJsonRpcController shared = new A2aJsonRpcController(taskService, agentService, JSON);
        rest = new A2aRestController(taskService, shared, JSON);
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
                .thenReturn(Flux.just("Hello ", "world"));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static String message(String text, String extra) {
        return "{\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\""
                + text
                + "\"}]}"
                + (extra == null ? "" : "," + extra)
                + "}";
    }

    private static JsonNode body(MockHttpServletResponse r) throws IOException {
        return JSON.readTree(r.getContentAsString());
    }

    private static void assertA2aError(
            MockHttpServletResponse r, int http, String status, String reason) throws IOException {
        assertEquals(http, r.getStatus());
        assertEquals("application/a2a+json", r.getContentType().split(";")[0].trim());
        JsonNode error = body(r).get("error");
        assertEquals(http, error.get("code").asInt());
        assertEquals(status, error.get("status").asText());
        JsonNode info = error.get("details").get(0);
        assertEquals("type.googleapis.com/google.rpc.ErrorInfo", info.get("@type").asText());
        assertEquals(reason, info.get("reason").asText());
        assertEquals("a2a-protocol.org", info.get("domain").asText());
    }

    @Test
    void sendMessageReturnsA1TaskEnvelope() throws IOException {
        MockHttpServletResponse r = new MockHttpServletResponse();
        rest.sendMessage(AGENT_ID, message("hi", null), V1, ALICE, new MockHttpServletRequest(), r);

        assertEquals(200, r.getStatus());
        assertEquals("application/a2a+json", r.getContentType().split(";")[0].trim());
        assertEquals(V1, r.getHeader("A2A-Version"));
        JsonNode task = body(r).get("task");
        assertEquals("TASK_STATE_COMPLETED", task.get("status").get("state").asText());
        assertEquals(
                "Hello world",
                task.get("artifacts").get(0).get("parts").get(0).get("text").asText());
        assertTrue(!task.has("kind"), "1.0 drops the kind discriminator");
    }

    @Test
    void platformRouteNeedsAnAgentIdAndAbsentVersionIsAccepted() throws IOException {
        MockHttpServletResponse r = new MockHttpServletResponse();
        rest.sendMessage(null, message("hi", null), null, ALICE, new MockHttpServletRequest(), r);
        assertA2aError(r, 400, "INVALID_ARGUMENT", "INVALID_ARGUMENT");

        MockHttpServletResponse ok = new MockHttpServletResponse();
        rest.sendMessage(
                null,
                message("hi", "\"metadata\":{\"agentId\":\"" + AGENT_ID + "\"}"),
                null,
                ALICE,
                new MockHttpServletRequest(), ok);
        assertEquals(200, ok.getStatus());
    }

    @Test
    void getAndCancelUseTheSection54Statuses() throws IOException {
        MockHttpServletResponse sent = new MockHttpServletResponse();
        rest.sendMessage(AGENT_ID, message("hi", null), V1, ALICE, new MockHttpServletRequest(), sent);
        String taskId = body(sent).get("task").get("id").asText();

        MockHttpServletResponse got = new MockHttpServletResponse();
        rest.getTask(taskId, 1, V1, ALICE, new MockHttpServletRequest(), got);
        assertEquals(200, got.getStatus());
        assertEquals(1, body(got).get("history").size());

        MockHttpServletResponse missing = new MockHttpServletResponse();
        rest.getTask("nope", null, V1, ALICE, new MockHttpServletRequest(), missing);
        assertA2aError(missing, 404, "NOT_FOUND", "TASK_NOT_FOUND");
        assertEquals(
                "nope",
                body(missing)
                        .get("error")
                        .get("details")
                        .get(0)
                        .get("metadata")
                        .get("taskId")
                        .asText());

        MockHttpServletResponse cancel = new MockHttpServletResponse();
        rest.cancelTask(taskId, V1, ALICE, new MockHttpServletRequest(), cancel);
        assertA2aError(cancel, 400, "FAILED_PRECONDITION", "TASK_NOT_CANCELABLE");
    }

    @Test
    void unauthorisedAgentIsIndistinguishableFromUnknown() throws IOException {
        MockHttpServletResponse r = new MockHttpServletResponse();
        rest.sendMessage(FORBIDDEN_AGENT_ID, message("hi", null), V1, ALICE, new MockHttpServletRequest(), r);
        assertA2aError(r, 404, "NOT_FOUND", "AGENT_NOT_FOUND");
    }

    @Test
    void filePartsAndBadJsonAndOldVersionsAreRejected() throws IOException {
        MockHttpServletResponse file = new MockHttpServletResponse();
        rest.sendMessage(
                AGENT_ID,
                "{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"url\":\"http://x\"}]}}",
                V1,
                ALICE,
                new MockHttpServletRequest(), file);
        assertA2aError(file, 400, "INVALID_ARGUMENT", "CONTENT_TYPE_NOT_SUPPORTED");

        MockHttpServletResponse bad = new MockHttpServletResponse();
        rest.sendMessage(AGENT_ID, "{not json", V1, ALICE, new MockHttpServletRequest(), bad);
        assertA2aError(bad, 400, "INVALID_ARGUMENT", "INVALID_ARGUMENT");

        MockHttpServletResponse old = new MockHttpServletResponse();
        rest.sendMessage(AGENT_ID, message("hi", null), "0.3", ALICE, new MockHttpServletRequest(), old);
        assertA2aError(old, 400, "FAILED_PRECONDITION", "VERSION_NOT_SUPPORTED");

        MockHttpServletResponse junk = new MockHttpServletResponse();
        rest.sendMessage(AGENT_ID, message("hi", null), "9.9", ALICE, new MockHttpServletRequest(), junk);
        assertA2aError(junk, 400, "FAILED_PRECONDITION", "VERSION_NOT_SUPPORTED");
    }

    @Test
    void streamFramesAreBareStreamResponsesInSpecOrder() throws IOException {
        MockHttpServletResponse r = new MockHttpServletResponse();
        rest.streamMessage(AGENT_ID, message("hi", null), V1, ALICE, new MockHttpServletRequest(), r);

        assertEquals("text/event-stream", r.getContentType().split(";")[0].trim());
        String[] blocks = r.getContentAsString().split("\n\n");
        JsonNode first = JSON.readTree(blocks[0].substring("data: ".length()));
        assertTrue(first.has("task"), "first frame is the Task, with no JSON-RPC envelope");
        assertTrue(!first.has("jsonrpc") && !first.has("result"));
        JsonNode last = JSON.readTree(blocks[blocks.length - 1].substring("data: ".length()));
        assertEquals(
                "TASK_STATE_COMPLETED",
                last.get("statusUpdate").get("status").get("state").asText());
        assertTrue(r.getContentAsString().contains("\"artifactUpdate\""));
    }

    @Test
    void streamValidationErrorsAreAPlainHttpErrorNotAnEventStream() throws IOException {
        MockHttpServletResponse r = new MockHttpServletResponse();
        rest.streamMessage(FORBIDDEN_AGENT_ID, message("hi", null), V1, ALICE, new MockHttpServletRequest(), r);
        assertA2aError(r, 404, "NOT_FOUND", "AGENT_NOT_FOUND");
    }

    @Test
    void operationsThisServerDoesNotOfferAnswerWithTheSpecErrors() throws IOException {
        MockHttpServletResponse list = new MockHttpServletResponse();
        rest.listTasks(V1, list);
        assertA2aError(list, 400, "FAILED_PRECONDITION", "UNSUPPORTED_OPERATION");

        MockHttpServletResponse sub = new MockHttpServletResponse();
        rest.subscribeToTask(V1, sub);
        assertA2aError(sub, 400, "FAILED_PRECONDITION", "UNSUPPORTED_OPERATION");

        MockHttpServletResponse push = new MockHttpServletResponse();
        rest.createPushConfig(V1, push);
        assertA2aError(push, 400, "FAILED_PRECONDITION", "PUSH_NOTIFICATION_NOT_SUPPORTED");

        MockHttpServletResponse card = new MockHttpServletResponse();
        rest.extendedAgentCard(V1, card);
        assertA2aError(card, 400, "FAILED_PRECONDITION", "UNSUPPORTED_OPERATION");
    }

    @Test
    void springRoutesTheColonVerbsAndBothBasePaths() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(rest).build();

        mvc.perform(
                        MockMvcRequestBuilders.post(
                                        "/api/agents/" + AGENT_ID + "/a2a/rest/message:send")
                                .contentType("application/a2a+json")
                                .header("A2A-Version", V1)
                                .content(message("hi", null)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.task.status.state")
                                .value("TASK_STATE_COMPLETED"));

        mvc.perform(MockMvcRequestBuilders.post("/api/a2a/rest/tasks/abc:cancel"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.error.details[0].reason")
                                .value("TASK_NOT_FOUND"));

        mvc.perform(MockMvcRequestBuilders.get("/api/a2a/rest/tasks"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(MockMvcRequestBuilders.post("/api/a2a/rest/tasks/abc:subscribe"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(
                        MockMvcRequestBuilders.delete(
                                "/api/a2a/rest/tasks/abc/pushNotificationConfigs/c1"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
    }
}
