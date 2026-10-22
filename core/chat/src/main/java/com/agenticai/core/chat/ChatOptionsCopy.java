package com.agenticai.core.chat;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The one place options objects are re-derived before being changed (ADR-0012 P1b, P3). Spring AI
 * 1.x exposed {@code ChatOptions#copy()}; Spring AI 2 options are immutable and offer {@code
 * mutate()} (a builder seeded with every value) instead. {@code of(...)} is {@code
 * options.mutate().build()}: a distinct instance of the same concrete type carrying the same
 * values; callers that need a change use the typed {@code mutate()} builder directly.
 */
public final class ChatOptionsCopy {

    private ChatOptionsCopy() {}

    /** A mutable copy of arbitrary chat options, as {@code ChatOptions#copy()} returns it. */
    public static ChatOptions of(ChatOptions options) {
        return options.mutate().build();
    }

    public static OpenAiChatOptions of(OpenAiChatOptions options) {
        return options.mutate().build();
    }

    public static GoogleGenAiChatOptions of(GoogleGenAiChatOptions options) {
        return options.mutate().build();
    }
}
