package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.PromptResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A stand-in {@link ToolCallback} for a tool the model asked for but that was
 * never offered to it.
 *
 * <h2>Incident this exists for (2026-07-29, agent MYSURU_MITRA on Sarvam)</h2>
 * Asking the agent for an image produced the raw text {@code
 * @@AGENT_CHAT_STREAM_ERROR_...} in the chat window. The chain was:
 * {@code sarvam-105b} emitted a tool call named {@code generate_image}; that
 * tool is only registered when the tenant has an ACTIVE OpenAI credential
 * (see {@code AgentToolProvider#resolveImageGenTools}) and this tenant has
 * none, so nothing by that name was in the request's tool list; Spring AI's
 * {@code DefaultToolCallingManager#executeToolCall} threw
 * {@code IllegalStateException: No ToolCallback found for tool name:
 * generate_image}; that killed the turn mid-stream, which
 * {@code AgentController#chat} could only report through its
 * stream-error sentinel. Recorded verbatim in {@code
 * bahuth.llm_call_log} (2026-07-29 11:11:52Z, and again at 11:11:19Z for a
 * wholly invented {@code search_web_exa} that exists nowhere in this
 * codebase).
 *
 * <p>Two separate things were wrong and this class fixes the first: a model
 * naming a tool that does not exist is <b>normal, expected model behaviour</b>
 * - smaller models hallucinate tool names, and the same models this codebase
 * already had to defend against for malformed tool-call arguments (see
 * {@link ArgumentSanitizingToolCallingManager}) hallucinate names too. It must
 * never be able to kill a chat turn. Answering the call with a plain,
 * actionable explanation lets the model recover inside the same turn and tell
 * the user something useful, which is strictly better than a dead stream.
 *
 * <p>The per-tool hints below are deliberately written <em>at the model</em>
 * (telling it what to say and not to retry) rather than at the user: the
 * string is a tool result, so the user only ever sees the model's own
 * rendering of it, in whatever language they are speaking.
 */
public final class UnavailableToolCallback implements ToolCallback {

    /**
     * Tools that genuinely exist in this platform but are gated behind a
     * tenant-level configuration step, so "it does not exist" would be a lie
     * and "add a key" is the actual next action. Keyed by the exact tool name
     * the corresponding {@code ToolCallback} publishes; the value is the key in
     * the prompt registry of the explanation handed back to the model
     * ({@code toolresult.unavailable.*}).
     *
     * <p>The two legacy aliases (renamed to "speak" 2026-08-02, unifying the previously separate ElevenLabs and
     * Sarvam speak tools) are kept so a model with stale context (or a resumed session from before the rename) that
     * still calls the old name gets a useful answer instead of the generic "you invented this name" hint, which would
     * be actively wrong here.
     */
    private static final Map<String, String> GATED_TOOL_KEYS = Map.of(
        "generate_image", "toolresult.unavailable.generate_image",
        "speak", "toolresult.unavailable.speak",
        "speak_with_cloned_voice", "toolresult.unavailable.renamed_speak",
        "sarvam_speak", "toolresult.unavailable.renamed_speak",
        "run_code", "toolresult.unavailable.run_code"
    );

    private static final String EMPTY_OBJECT_SCHEMA = """
        {"type": "object", "properties": {}}
        """;

    private static final ObjectMapper JSON_MAPPER = JsonMappers.plain();

    private final String toolName;
    private final String message;

    private UnavailableToolCallback(String toolName, String message) {
        this.toolName = toolName;
        this.message = message;
    }

    /**
     * @param toolName the exact name the model asked for - echoed back so the
     *     model can tell which of several parallel calls this answers.
     */
    public static UnavailableToolCallback forToolName(String toolName) {
        return forToolName(toolName, PromptResolver.defaultsOnly(), ChatPrompts.Run.NONE);
    }

    /**
     * As {@link #forToolName(String)}, with the explanation read from the prompt registry for {@code run}'s agent and
     * tenant, so an override of {@code toolresult.unavailable.*} applies.
     */
    static UnavailableToolCallback forToolName(String toolName, PromptResolver prompts, ChatPrompts.Run run) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        String gatedKey = GATED_TOOL_KEYS.get(normalized);
        return new UnavailableToolCallback(
            toolName == null ? "" : toolName,
            gatedKey != null
                ? ChatPrompts.text(prompts, gatedKey, run)
                : ChatPrompts.text(prompts, ChatPrompts.UNKNOWN_TOOL, run, "tool", toolName)
        );
    }

    /** The plain-language explanation handed back to the model as this call's result. */
    public String message() {
        return message;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // Only ever built for a name the model ALREADY called, and injected
        // into that one in-flight request's options - never advertised in a
        // tool list, so this definition costs no prompt tokens and cannot
        // tempt a model into calling something that does not work.
        return ToolDefinition.builder()
            .name(toolName)
            .description("Unavailable tool.")
            .inputSchema(EMPTY_OBJECT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        // Wrapped as {"result": "..."}, not returned as raw prose: Spring
        // AI's Gemini integration (GoogleGenAiChatModel#parseJsonToMap)
        // unconditionally parses every tool-role message as a JSON object
        // when replaying it in the next request, and throws - killing the
        // whole chat turn - on anything that isn't valid JSON (same failure
        // McpToolOutputSanitizer#ensureJsonObject exists to prevent for MCP
        // tool results; this callback's message is exactly the same shape of
        // problem and was missed by that fix since it isn't wired through
        // SanitizedMcpToolCallback). message is always a fixed prose string,
        // never already JSON, so it is wrapped unconditionally.
        try {
            return JSON_MAPPER.writeValueAsString(Map.of("result", message));
        } catch (Exception e) {
            return "{\"result\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"}";
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
