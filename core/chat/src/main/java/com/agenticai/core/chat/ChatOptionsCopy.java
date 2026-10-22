package com.agenticai.core.chat;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The one place options objects are copied before being mutated (ADR-0012 P1b). Spring AI 1.x
 * exposes {@code ChatOptions#copy()}; Spring AI 2 replaces it with {@code mutate()}/builders, so
 * the callers (tool-loop wrapping, structured-output JSON mode) go through here and P3 changes only
 * this class. Behaviour is exactly {@code options.copy()}: a distinct instance of the same concrete
 * type.
 */
public final class ChatOptionsCopy {

    private ChatOptionsCopy() {}

    /** A mutable copy of arbitrary chat options, as {@code ChatOptions#copy()} returns it. */
    public static ChatOptions of(ChatOptions options) {
        return options.copy();
    }

    public static OpenAiChatOptions of(OpenAiChatOptions options) {
        return options.copy();
    }

    public static GoogleGenAiChatOptions of(GoogleGenAiChatOptions options) {
        return options.copy();
    }
}
