package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatImportFormat#forConversation} checks {@link ChatImportFormat#REGISTERED} in order and
 * the order is the contract: a ChatGPT {@code mapping} wins over a Claude {@code chat_messages},
 * which wins over this platform's own {@code messages}. {@code matches} / {@code source} /
 * {@code sourceId} are the other two seams every format implements.
 */
class ChatImportFormatDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(bytes(json));
    }

    @Test
    void eachFormatMatchesItsOwnShapeAndNoOther() throws Exception {
        ChatGptImportFormat gpt = new ChatGptImportFormat();
        ClaudeImportFormat claude = new ClaudeImportFormat();
        VegaDutaImportFormat own = new VegaDutaImportFormat();

        JsonNode g = tree("{\"mapping\": {}}");
        JsonNode c = tree("{\"chat_messages\": []}");
        JsonNode v = tree("{\"messages\": []}");

        assertTrue(gpt.matches(g));
        assertTrue(claude.matches(c));
        assertTrue(own.matches(v));
        assertTrue(!gpt.matches(c) && !claude.matches(g) && !own.matches(g));
    }

    @Test
    void dispatchHonoursTheRegisteredOrderWhenShapesOverlap() throws Exception {
        assertInstanceOf(ChatGptImportFormat.class,
            ChatImportFormat.forConversation(tree("{\"mapping\": {}, \"chat_messages\": []}")));
        assertInstanceOf(ClaudeImportFormat.class,
            ChatImportFormat.forConversation(tree("{\"chat_messages\": [], \"messages\": []}")));
        assertInstanceOf(VegaDutaImportFormat.class,
            ChatImportFormat.forConversation(tree("{\"messages\": []}")));
        assertNull(ChatImportFormat.forConversation(tree("{\"unrelated\": true}")));
    }

    @Test
    void sourcesAndIdsComeFromTheRightFieldsPerFormat() throws Exception {
        ChatGptImportFormat gpt = new ChatGptImportFormat();
        ClaudeImportFormat claude = new ClaudeImportFormat();
        VegaDutaImportFormat own = new VegaDutaImportFormat();

        JsonNode g = tree("{\"conversation_id\": \" c-1 \", \"id\": \"fallback\"}");
        assertEquals(ChatImportParser.SOURCE_CHATGPT, gpt.source(g));
        assertEquals("c-1", gpt.sourceId(g));
        assertEquals("fallback", gpt.sourceId(tree("{\"id\": \"fallback\"}")));

        JsonNode c = tree("{\"uuid\": \"u-1\"}");
        assertEquals(ChatImportParser.SOURCE_CLAUDE, claude.source(c));
        assertEquals("u-1", claude.sourceId(c));

        // A re-exported ChatGPT/Claude import keeps its original source; anything else is "vegaduta".
        assertEquals(ChatImportParser.SOURCE_CHATGPT,
            own.source(tree("{\"source\": \"chatgpt\"}")));
        assertEquals(ChatImportParser.SOURCE_CLAUDE,
            own.source(tree("{\"source\": \"claude\"}")));
        assertEquals(ChatImportParser.SOURCE_VEGADUTA,
            own.source(tree("{\"source\": \"untrusted\"}")));
        assertEquals(ChatImportParser.SOURCE_VEGADUTA, own.source(tree("{}")));
        assertEquals("s-9", own.sourceId(tree("{\"id\": \"s-9\"}")));
        assertNull(own.sourceId(tree("{}")));
    }
}
