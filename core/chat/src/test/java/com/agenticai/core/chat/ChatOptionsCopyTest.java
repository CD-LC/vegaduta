package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The one place options objects are copied before being mutated (ADR-0012 P1b). A copy is a
 * distinct instance of the same concrete type carrying the same values, so mutating it never
 * leaks back into the caller's options.
 */
class ChatOptionsCopyTest {

    @Test
    void theGenericOverloadReturnsADistinctInstanceOfTheSameType() {
        ChatOptions source = OpenAiChatOptions.builder().model("gpt-5").build();

        ChatOptions copy = ChatOptionsCopy.of(source);

        assertNotSame(source, copy);
        assertTrue(copy instanceof OpenAiChatOptions);
        assertEquals("gpt-5", copy.getModel());
    }

    @Test
    void theOpenAiOverloadCopiesEveryReadValue() {
        OpenAiChatOptions source = OpenAiChatOptions.builder()
            .model("gpt-5")
            .maxTokens(64)
            .temperature(0.3)
            .build();

        OpenAiChatOptions copy = ChatOptionsCopy.of(source);

        assertNotSame(source, copy);
        assertEquals("gpt-5", copy.getModel());
        assertEquals(64, copy.getMaxTokens());
        assertEquals(0.3, copy.getTemperature());
    }

    @Test
    void mutatingTheCopyLeavesTheSourceUntouched() {
        OpenAiChatOptions source = OpenAiChatOptions.builder().model("gpt-5").build();

        OpenAiChatOptions copy = ChatOptionsCopy.of(source);
        copy.setModel("gpt-4o");

        assertEquals("gpt-5", source.getModel());
        assertEquals("gpt-4o", copy.getModel());
    }
}
