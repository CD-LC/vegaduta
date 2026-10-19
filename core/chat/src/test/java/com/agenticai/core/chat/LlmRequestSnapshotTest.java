package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class LlmRequestSnapshotTest {

    private static LlmRequestSnapshot snap(String session, String agent, Instant at) {
        return new LlmRequestSnapshot(
            java.util.UUID.randomUUID().toString(), at, agent, session, "ANTHROPIC", "m", true,
            Map.of(), List.of(), List.of(), Map.of(), 0, false
        );
    }

    @Test
    void redactsSecretShapesButKeepsOrdinaryText() {
        String out = LlmRequestSnapshotter.redact("use key sk-abcdefghijklmnopqrstuv and api_key=hunter2hunter2 ok");
        assertThat(out).doesNotContain("sk-abcdefghijklmnopqrstuv").doesNotContain("hunter2hunter2");
        assertThat(out).contains("use key").contains("ok");
        assertThat(LlmRequestSnapshotter.redact("plain system prompt")).isEqualTo("plain system prompt");
    }

    @Test
    void storeIsTenantScopedAndFiltersByAgentAndWindow() {
        LlmRequestSnapshotStore store = LlmRequestSnapshotStore.INSTANCE;
        Instant now = Instant.now();
        store.add("t1", snap("s-test", "a1", now.minusSeconds(10)));
        store.add("t1", snap("s-test", "a2", now));
        assertThat(store.list("t1", "s-test", null, null, null)).hasSize(2);
        assertThat(store.list("t2", "s-test", null, null, null)).isEmpty();
        assertThat(store.list("t1", "s-test", "a2", null, null)).hasSize(1);
        assertThat(store.list("t1", "s-test", null, now.minusSeconds(5), null)).hasSize(1);
    }

    private static ToolCallback tool(String name, String description, String schema) {
        ToolDefinition def = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    @Test
    void prefixSizeCountsSystemMessagesAndToolSchemasOnly() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(tool("ab", "cde", "{\"type\":\"object\"}")))
            .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage("12345678"), new UserMessage("not counted at all")), options);
        int[] prefix = LlmRequestSnapshotter.prefixSize(new ChatClientRequest(prompt, Map.of()));
        // 8 (system) + 2 (name) + 3 (description) + 17 (schema) = 30 chars -> 7 tokens
        assertThat(prefix[0]).isEqualTo(8 + 2 + 3 + "{\"type\":\"object\"}".length());
        assertThat(prefix[1]).isEqualTo(prefix[0] / 4);
    }

    @Test
    void prefixSizeIsZeroWithoutSystemMessagesOrTools() {
        int[] prefix = LlmRequestSnapshotter.prefixSize(new ChatClientRequest(new Prompt("hi"), Map.of()));
        assertThat(prefix).containsExactly(0, 0);
    }

    @Test
    void captureKeepsTotalCharsAsShownSizeAndAddsFullTotalAndToolFields() {
        String session = "s-capture-fields";
        String bigSchema = "x".repeat(5_000); // displayed copy is capped at 4000, real size must be kept
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(tool("t", "d", bigSchema)))
            .build();
        AssistantMessage assistant = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "t", "{\"a\":1}")))
            .build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "t", "result-data")))
            .build();
        Prompt prompt = new Prompt(
            List.of(new SystemMessage("sys"), new UserMessage("hello"), assistant, toolResult), options);
        String id = LlmRequestSnapshotter.capture(
            new ChatClientRequest(prompt, Map.of(LlmMetricsAdvisor.SESSION_ID_PARAM, session)), null, "m", false);
        assertThat(id).isNotNull();

        LlmRequestSnapshot snap = LlmRequestSnapshotStore.INSTANCE.list(null, session, null, null, null).get(0);
        assertThat(snap.id()).isEqualTo(id);
        // totalChars keeps its old meaning: the size of the message text shown
        int shownChars = snap.messages().stream().mapToInt(m -> m.text().length()).sum();
        assertThat(snap.totalChars()).isEqualTo(shownChars);
        // fullTotalChars additionally counts tool-call and tool-result payloads
        int callPayload = ("t".length() + "{\"a\":1}".length()) + ("t".length() + "result-data".length());
        int textChars = snap.messages().stream().mapToInt(LlmRequestSnapshot.Message::chars).sum();
        assertThat(snap.fullTotalChars()).isEqualTo(textChars + callPayload);
        assertThat(snap.fullTotalChars()).isGreaterThan(snap.totalChars());
        assertThat(snap.toolSchemaChars()).isEqualTo(5_000);
        assertThat(snap.tools().get(0).schemaChars()).isEqualTo(5_000);
        assertThat(snap.prefixChars()).isEqualTo(3 + 1 + 1 + 5_000);
        assertThat(snap.prefixTokensEstimate()).isEqualTo(snap.prefixChars() / 4);
        assertThat(snap.providerPromptTokens()).isNull();
    }

    @Test
    void legacyConstructorKeepsFullTotalEqualToTotal() {
        LlmRequestSnapshot s = snap("s-legacy", "a", Instant.now());
        assertThat(s.fullTotalChars()).isEqualTo(s.totalChars());
        assertThat(s.providerPromptTokens()).isNull();
    }

    @Test
    void attachPromptTokensMatchesByIdAttachesOnceAndKeepsOrder() {
        LlmRequestSnapshotStore store = LlmRequestSnapshotStore.INSTANCE;
        Instant now = Instant.now();
        LlmRequestSnapshot a = snap("s-attach", "a1", now.minusSeconds(3));
        LlmRequestSnapshot b = snap("s-attach", "a1", now.minusSeconds(2));
        LlmRequestSnapshot c = snap("s-attach", "a1", now.minusSeconds(1));
        store.add("t1", a);
        store.add("t1", b);
        store.add("t1", c);

        // attach to the MIDDLE one by id: neither neighbour is touched, order is preserved
        store.attachPromptTokens("t1", "s-attach", b.id(), 111L);
        List<LlmRequestSnapshot> out = store.list("t1", "s-attach", null, null, null);
        assertThat(out).extracting(LlmRequestSnapshot::id).containsExactly(a.id(), b.id(), c.id());
        assertThat(out).extracting(LlmRequestSnapshot::providerPromptTokens).containsExactly(null, 111L, null);

        // second attach for the same id never overwrites
        store.attachPromptTokens("t1", "s-attach", b.id(), 999L);
        assertThat(store.list("t1", "s-attach", null, null, null).get(1).providerPromptTokens()).isEqualTo(111L);

        // unknown id, wrong tenant, null args are no-ops
        store.attachPromptTokens("t1", "s-attach", "nope", 5L);
        store.attachPromptTokens("t2", "s-attach", a.id(), 5L);
        store.attachPromptTokens("t1", "s-attach", null, 5L);
        store.attachPromptTokens("t1", "s-attach", a.id(), null);
        assertThat(store.list("t1", "s-attach", null, null, null))
            .extracting(LlmRequestSnapshot::providerPromptTokens).containsExactly(null, 111L, null);

        // oldest and newest entries can be attached too
        store.attachPromptTokens("t1", "s-attach", c.id(), 333L);
        store.attachPromptTokens("t1", "s-attach", a.id(), 1L);
        assertThat(store.list("t1", "s-attach", null, null, null))
            .extracting(LlmRequestSnapshot::providerPromptTokens).containsExactly(1L, 111L, 333L);
    }
}
