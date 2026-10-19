package com.agenticai.core.providers;

import java.util.Locale;

/**
 * How the constrained tool router ({@code core.chat.ConstrainedToolRouterChatModel}) makes a small
 * model choose a tool, decided by the server kind the save-time probe detected.
 */
public enum ToolRouterMode {
    /** The reply is constrained to a JSON schema ({@code response_format: json_schema}): Ollama, llama.cpp. */
    FORMAT,
    /** {@code tool_choice: required} with an extra {@code final_answer} tool: vLLM. */
    REQUIRED;

    public static ToolRouterMode fromStored(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The mode a probed server supports, or null for a server whose capabilities are unknown. */
    public static ToolRouterMode forServer(ToolCallCapabilityProbe.ServerKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case OLLAMA, LLAMA_CPP -> FORMAT;
            case VLLM -> REQUIRED;
            case OLLAMA_WITHOUT_FORMAT, OTHER -> null;
        };
    }
}
