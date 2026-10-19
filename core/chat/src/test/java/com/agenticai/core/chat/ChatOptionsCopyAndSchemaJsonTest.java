package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.genai.types.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;

class ChatOptionsCopyAndSchemaJsonTest {

    @Test
    void copiesAreDistinctInstancesOfTheSameTypeWithTheSameValues() {
        OpenAiChatOptions openAi = OpenAiChatOptions.builder().model("gpt-4o").maxTokens(10).temperature(0.2).build();
        OpenAiChatOptions copy = ChatOptionsCopy.of(openAi);
        assertNotSame(openAi, copy);
        assertEquals("gpt-4o", copy.getModel());
        assertEquals(10, copy.getMaxTokens());
        assertEquals(0.2, copy.getTemperature());

        GoogleGenAiChatOptions gemini = GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").topP(0.5).build();
        GoogleGenAiChatOptions geminiCopy = ChatOptionsCopy.of(gemini);
        assertNotSame(gemini, geminiCopy);
        assertEquals("gemini-2.5-flash", geminiCopy.getModel());
        assertEquals(0.5, geminiCopy.getTopP());
    }

    @Test
    void mutatingTheCopyDoesNotTouchTheOriginal() {
        OpenAiChatOptions openAi = OpenAiChatOptions.builder().model("gpt-4o").build();
        OpenAiChatOptions copy = ChatOptionsCopy.of(openAi);
        copy.setModel("other");
        assertEquals("gpt-4o", openAi.getModel());
    }

    @Test
    void genericOverloadKeepsTheConcreteType() {
        ChatOptions options = OpenAiChatOptions.builder().model("m").build();
        ChatOptions copy = ChatOptionsCopy.of(options);
        assertNotSame(options, copy);
        assertInstanceOf(OpenAiChatOptions.class, copy);
        assertEquals(options.getModel(), copy.getModel());
    }

    @Test
    void schemaJsonWritesLikeSpringAisOwnMapper() throws JsonProcessingException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put("a", java.util.List.of("x"));
        assertEquals(ModelOptionsUtils.OBJECT_MAPPER.writeValueAsString(map), SchemaJson.write(map));
    }

    @Test
    void schemaJsonReadsAValidGeminiSchemaAndRejectsAnInvalidOne() throws JsonProcessingException {
        Schema ok = SchemaJson.read("{\"type\":\"OBJECT\",\"properties\":{\"a\":{\"type\":\"STRING\"}}}", Schema.class);
        assertEquals(1, ok.properties().orElseThrow().size());
        assertThrows(JsonProcessingException.class, () -> SchemaJson.read("{\"properties\":5}", Schema.class));
        assertNull(SchemaJson.read("null", Schema.class));
    }
}
