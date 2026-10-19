package com.agenticai.core.mcpserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.McpCallerContextTestAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * {@code call_agent} is the one MCP tool that triggers real, governed agent behavior for an
 * EXTERNAL caller - and because the advertised tool list is JVM-wide (see {@code
 * McpServerToolConfig}'s javadoc), the per-grant exposed-agent check INSIDE this callback is the
 * only thing standing between a valid-but-narrow API key and every agent in the tenant. The suite
 * pins that check, the MCP-protocol-friendly error shape (errors are returned as JSON strings,
 * never thrown - a thrown exception would surface as an opaque protocol fault to the MCP client),
 * the grant-traceable subjectKey/session-id conventions the data-governance audit relies on, and
 * the "audit logging must never change the real outcome" posture.
 */
class McpCallAgentToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentChatService agentChatService;
    private AuditLogService auditLogService;
    private McpCallAgentToolCallback callback;

    private final UUID grantId = UUID.randomUUID();
    private final UUID exposedAgentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentChatService = mock(AgentChatService.class);
        auditLogService = mock(AuditLogService.class);
        callback = new McpCallAgentToolCallback(agentChatService, auditLogService);
        McpCallerContextTestAccess.set(grantId, Set.of(exposedAgentId), Set.of(), Set.of());
    }

    @AfterEach
    void tearDown() {
        McpCallerContextTestAccess.clear();
    }

    private JsonNode call(String toolInput) throws Exception {
        return MAPPER.readTree(callback.call(toolInput));
    }

    @Test
    void missingCallerContextIsAnIllegalStateNotASilentFullAccessCall() {
        // If the filter never ran, there is no grant to scope by - failing
        // loud is the only safe option; any fallback would be an
        // unauthenticated code path into AgentChatService.
        McpCallerContextTestAccess.clear();
        assertThrows(IllegalStateException.class, () -> callback.call("{}"));
    }

    @Test
    void missingAgentIdOrMessageReturnsAnErrorWithoutCallingTheAgent() throws Exception {
        assertTrue(call("{\"message\": \"hi\"}").has("error"));
        assertTrue(call("{\"agent_id\": \"" + exposedAgentId + "\"}").has("error"));
        verify(agentChatService, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void malformedAgentIdReturnsAGuidingErrorNotAStackTrace() throws Exception {
        JsonNode result = call("{\"agent_id\": \"not-a-uuid\", \"message\": \"hi\"}");
        assertTrue(result.get("error").asText().contains("list_my_agents"));
        verify(agentChatService, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
    }

    @Test
    void anAgentOutsideTheGrantsExposedSetIsRejectedBeforeChatIsEverInvoked() throws Exception {
        // The cross-grant (and therefore cross-tenant) authorization line:
        // a syntactically valid id that the admin never exposed - including
        // another tenant's agent id - must die here, not in AgentChatService.
        UUID unexposedAgentId = UUID.randomUUID();

        JsonNode result = call("{\"agent_id\": \"" + unexposedAgentId + "\", \"message\": \"hi\"}");

        assertTrue(result.get("error").asText().contains("not authorized"));
        verify(agentChatService, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
        // A rejected over-reach attempt is itself compliance-relevant.
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.call_agent"),
                        eq("Agent"),
                        eq(unexposedAgentId.toString()),
                        eq(AuditOutcome.FAILURE),
                        anyString());
    }

    @Test
    void anExposedAgentIsCalledWithTheGrantTraceableIdentity() throws Exception {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "mcp-external:" + grantId)
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Hello ", "world"));

        JsonNode result = call("{\"agent_id\": \"" + exposedAgentId + "\", \"message\": \"hi\"}");

        // Chunks are joined into MCP's single synchronous reply.
        assertEquals("Hello world", result.get("reply").asText());
        // The minted session id carries the grant id - the schema-free
        // traceability convention tool_call_log rows lean on.
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertTrue(turns.getValue().sessionId().startsWith("mcp-" + grantId + "-"));
        assertEquals(
                turns.getValue().sessionId(),
                result.get("session_id").asText(),
                "the caller must get back the session id to echo on follow-ups");
        verify(auditLogService)
                .record(
                        eq("mcp-external:" + grantId),
                        eq("mcp.grant.call_agent"),
                        eq("Agent"),
                        eq(exposedAgentId.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void aCallerSuppliedSessionIdIsReusedForConversationContinuity() throws Exception {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "my-session")
                                                && r.subjectKey() != null
                                                && java.util.Objects.equals(r.userMessage(), "hi")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("ok"));

        JsonNode result =
                call(
                        "{\"agent_id\": \""
                                + exposedAgentId
                                + "\", \"message\": \"hi\", \"session_id\": \"my-session\"}");

        assertEquals("my-session", result.get("session_id").asText());
        verify(agentChatService)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), exposedAgentId)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "my-session")
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
    }

    @Test
    void aChatFailureBecomesAnErrorPayloadNotAThrownException() throws Exception {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.error(new RuntimeException("provider down")));

        JsonNode result = call("{\"agent_id\": \"" + exposedAgentId + "\", \"message\": \"hi\"}");

        assertTrue(result.get("error").asText().contains("Agent call failed"));
    }

    @Test
    void anEmptyReplyFluxSerializesAsAnEmptyStringNotNull() throws Exception {
        // collectList().map(join).block() on an empty Flux yields "" via the
        // explicit null guard - Map.of would NPE on a null reply otherwise.
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.empty());

        JsonNode result = call("{\"agent_id\": \"" + exposedAgentId + "\", \"message\": \"hi\"}");

        assertEquals("", result.get("reply").asText());
        assertNull(result.get("error"));
    }

    @Test
    void anAuditLoggingFailureNeverChangesTheSuccessfulReply() throws Exception {
        // "Never let logging break the real call" (recordAudit javadoc) - an
        // audit_log_entry insert failure must not turn a delivered agent
        // reply into a reported error.
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("delivered"));
        doThrow(new RuntimeException("audit table unavailable"))
                .when(auditLogService)
                .record(anyString(), anyString(), anyString(), any(), any(), anyString());

        JsonNode result = call("{\"agent_id\": \"" + exposedAgentId + "\", \"message\": \"hi\"}");

        assertEquals("delivered", result.get("reply").asText());
        assertNull(result.get("error"));
    }
}
