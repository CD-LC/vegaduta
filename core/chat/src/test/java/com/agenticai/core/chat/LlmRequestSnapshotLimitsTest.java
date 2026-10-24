package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The caps of the LLM request inspector ({@code app.llm.request-snapshot.*}) are settings, not constants. */
class LlmRequestSnapshotLimitsTest {

    @AfterEach
    void restoreDefaults() {
        LlmRequestSnapshotter.configure(new LlmRequestSnapshotProperties());
    }

    private static ToolCallback tool(String name, String schema) {
        ToolDefinition def = ToolDefinition.builder().name(name).description("d").inputSchema(schema).build();
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

    private static LlmRequestSnapshot capture(String session, int tools, String message, String schema) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (int i = 0; i < tools; i++) {
            callbacks.add(tool("t" + i, schema));
        }
        Prompt prompt = new Prompt(
            List.of(new SystemMessage("sys"), new UserMessage(message)),
            ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
        String id = LlmRequestSnapshotter.capture(
            new ChatClientRequest(prompt, Map.of(LlmMetricsAdvisor.SESSION_ID_PARAM, session)), null, "m", false);
        assertThat(id).isNotNull();
        return LlmRequestSnapshotStore.INSTANCE.list(null, session, null, null, null).get(0);
    }

    @Test
    void theShippedDefaultsAreTheCapsTheInspectorAlwaysHad() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        assertThat(p.getMaxMessageChars()).isEqualTo(40_000);
        assertThat(p.getMaxTotalChars()).isEqualTo(300_000);
        assertThat(p.getMaxSchemaChars()).isEqualTo(4_000);
        assertThat(p.getMaxTools()).isEqualTo(200);
        assertThat(p.getMaxCallChars()).isEqualTo(600);
    }

    @Test
    void theToolCapFollowsTheProperty() {
        String session = "snap-limits-" + UUID.randomUUID();
        LlmRequestSnapshot defaults = capture(session + "-d", 5, "hi", "{}");
        assertThat(defaults.tools()).hasSize(5);
        assertThat(defaults.truncated()).isFalse();

        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        p.setMaxTools(2);
        LlmRequestSnapshotter.configure(p);
        LlmRequestSnapshot capped = capture(session + "-c", 5, "hi", "{}");
        assertThat(capped.tools()).hasSize(2);
        assertThat(capped.truncated()).isTrue();
    }

    @Test
    void theMessageAndSchemaCapsFollowTheProperties() {
        LlmRequestSnapshotProperties p = new LlmRequestSnapshotProperties();
        p.setMaxMessageChars(10);
        p.setMaxSchemaChars(8);
        LlmRequestSnapshotter.configure(p);
        LlmRequestSnapshot snap = capture("snap-limits-" + UUID.randomUUID(), 1, "x".repeat(50), "s".repeat(30));
        LlmRequestSnapshot.Message user = snap.messages().get(1);
        assertThat(user.text()).startsWith("x".repeat(10)).contains(p.getTruncationMarker().strip());
        assertThat(user.text()).doesNotContain("x".repeat(11));
        assertThat(snap.tools().get(0).inputSchema().length()).isLessThan(30);
        assertThat(snap.tools().get(0).schemaChars()).isEqualTo(30);
        assertThat(snap.truncated()).isTrue();
    }
}
