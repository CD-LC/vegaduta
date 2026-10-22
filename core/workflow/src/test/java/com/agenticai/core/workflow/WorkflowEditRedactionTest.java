package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Edit mode hands the canvas graph to the LLM provider: literal credentials must be swapped out
 * before it leaves and put back into whatever comes home.
 */
class WorkflowEditRedactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(
                id, type, Map.of("x", 1, "y", 2), Map.of("label", id, "config", config));
    }

    private static String json(WorkflowGraphDto graph) throws Exception {
        return MAPPER.writeValueAsString(graph);
    }

    @Test
    void vaultReferencesAndOrdinaryTextAreLeftAlone() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of("channel", "WEBHOOK")),
                                node(
                                        "h",
                                        "http",
                                        Map.of(
                                                "method",
                                                "POST",
                                                "url",
                                                "https://api.example.com/orders?page=2",
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "Authorization",
                                                                "value",
                                                                "Bearer {{secret.BILLING_API_KEY}}"),
                                                        Map.of(
                                                                "name",
                                                                "Content-Type",
                                                                "value",
                                                                "application/json")),
                                                "body",
                                                "{\"note\": \"{{ $json.note }}\"}")),
                                node(
                                        "tool",
                                        "tool",
                                        Map.of(
                                                "toolName",
                                                "send_email",
                                                "argsJson",
                                                "{\"to\": \"a@b.co\"}"))),
                        List.of(new WorkflowEdgeDto("e1", "t", "h", null, null)));
        String in = json(graph);

        WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.redact(MAPPER, in);

        assertTrue(redacted.secrets().isEmpty(), redacted.secrets().toString());
        assertSame(in, redacted.json(), "nothing to hide: the JSON is handed on untouched");
    }

    @Test
    void literalCredentialsAreReplacedWhereverTheyHide() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node(
                                        "h",
                                        "http",
                                        Map.of(
                                                "method",
                                                "GET",
                                                "url",
                                                "https://api.example.com/v1/items?api_key=abc123def456&page=2",
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "Authorization",
                                                                "value",
                                                                "Bearer live-token-0123456789abcdef"),
                                                        Map.of(
                                                                "name",
                                                                "X-Api-Key",
                                                                "value",
                                                                "plainkey-123"),
                                                        Map.of(
                                                                "name",
                                                                "Accept",
                                                                "value",
                                                                "application/json")),
                                                "body",
                                                "{\"password\": \"hunter2hunter2\", \"q\": \"shoes\"}")),
                                node(
                                        "tool",
                                        "tool",
                                        Map.of(
                                                "toolName",
                                                "post_message",
                                                "argsJson",
                                                "{\"token\": \"xoxb-1234567890-abcdefghij\", \"text\": \"hello\"}")),
                                node(
                                        "code",
                                        "code",
                                        Map.of(
                                                "operation",
                                                "template",
                                                "template",
                                                "key sk-proj-abcdefghijklmnop1234 end"))),
                        List.of());

        WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.redact(MAPPER, json(graph));
        String out = redacted.json();

        for (String secret :
                List.of(
                        "live-token-0123456789abcdef",
                        "plainkey-123",
                        "abc123def456",
                        "hunter2hunter2",
                        "xoxb-1234567890-abcdefghij",
                        "sk-proj-abcdefghijklmnop1234")) {
            assertFalse(out.contains(secret), secret + " leaked: " + out);
            assertTrue(
                    redacted.secrets().containsValue(secret),
                    secret + " not recorded: " + redacted.secrets());
        }
        // What is not a credential survives, so the model can still reason about the graph.
        assertTrue(out.contains("Bearer [[REDACTED_"), out);
        assertTrue(out.contains("page=2"), out);
        assertTrue(out.contains("shoes"), out);
        assertTrue(out.contains("post_message"), out);
        assertTrue(out.contains("application/json"), out);
        assertTrue(out.contains("hello"), out);
    }

    @Test
    void restorePutsEveryOriginalBackIncludingCharactersThatNeedEscaping() throws Exception {
        String awkward = "pa\"ss\\word\nwith-everything";
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node(
                                        "h",
                                        "http",
                                        Map.of(
                                                "method",
                                                "GET",
                                                "url",
                                                "https://api.example.com",
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "Authorization",
                                                                "value",
                                                                "Bearer live-token-0123456789abcdef"),
                                                        Map.of(
                                                                "name",
                                                                "X-Secret",
                                                                "value",
                                                                awkward))))),
                        List.of());
        WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.redact(MAPPER, json(graph));
        assertEquals(2, redacted.secrets().size());

        // The model "returns" the redacted graph as it saw it.
        WorkflowGraphDto fromModel = MAPPER.readValue(redacted.json(), WorkflowGraphDto.class);
        WorkflowGraphDto restored = WorkflowEditRedaction.restore(MAPPER, fromModel, redacted);

        assertEquals(MAPPER.readTree(json(graph)), MAPPER.readTree(json(restored)));
    }

    @Test
    void theSameCredentialGetsOnePlaceholderAndRestoreIsANoOpWithoutSecrets() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node(
                                        "a",
                                        "http",
                                        Map.of(
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "X-Api-Key",
                                                                "value",
                                                                "same-key-123")))),
                                node(
                                        "b",
                                        "http",
                                        Map.of(
                                                "headers",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "X-Api-Key",
                                                                "value",
                                                                "same-key-123"))))),
                        List.of());
        WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.redact(MAPPER, json(graph));
        assertEquals(Map.of("[[REDACTED_1]]", "same-key-123"), redacted.secrets());

        assertSame(
                graph,
                WorkflowEditRedaction.restore(MAPPER, graph, WorkflowEditRedaction.Redacted.NONE));
        assertEquals("", WorkflowEditRedaction.redact(MAPPER, "").json());
        assertEquals("not json", WorkflowEditRedaction.redact(MAPPER, "not json").json());
    }

    @Test
    void configurationKeysThatOnlySoundSensitiveAreNotRedacted() {
        assertTrue(WorkflowEditRedaction.sensitiveKey("Authorization"));
        assertTrue(WorkflowEditRedaction.sensitiveKey("x-api-key"));
        assertTrue(WorkflowEditRedaction.sensitiveKey("clientSecret"));
        assertFalse(WorkflowEditRedaction.sensitiveKey("toolName"));
        assertFalse(WorkflowEditRedaction.sensitiveKey("maxTokens"));
        assertFalse(WorkflowEditRedaction.sensitiveKey("label"));
        assertFalse(WorkflowEditRedaction.sensitiveKey(null));
    }
}
