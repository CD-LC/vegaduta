package com.agenticai.core.chat;

import java.util.UUID;

/**
 * Tool-call ids for providers that do not supply them. The call/result pairing every tool protocol
 * relies on (OpenAI {@code tool_call_id}, Anthropic {@code tool_use_id}) and the hosted agent-turn
 * endpoint's {@code toolCallId} both need a non-blank id; Gemini function calls carry none, so one is
 * made up when the response is read and travels with the call from then on.
 */
public final class ToolCallIds {

    /** Prefix of a generated id; OpenAI-style ids are {@code call_...}, so clients treat it alike. */
    static final String PREFIX = "call_";

    private ToolCallIds() {
    }

    /** {@code id} when it is usable, otherwise a fresh unique {@code call_<random>} id. */
    public static String ensure(String id) {
        return id == null || id.isBlank() ? generate() : id;
    }

    /** A fresh id, unique across calls and processes. */
    public static String generate() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
