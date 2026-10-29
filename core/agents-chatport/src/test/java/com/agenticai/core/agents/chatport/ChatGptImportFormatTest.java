package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatGptImportFormat#convert} beyond the golden path (the end-to-end shapes live in {@code
 * ChatImportParserTest}): content-type dispatch (code fences, asset pointers become attachment
 * names, unknown types yield nothing), the recipient/visibility filters, and the early nulls when
 * the {@code mapping} is missing, oversized or messageless.
 */
class ChatGptImportFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatGptImportFormat format = new ChatGptImportFormat();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(bytes(json));
    }

    private static ChatImportCollector collector() {
        return new ChatImportCollector(Limits.defaults(), true, null);
    }

    private static String userNode(String id, String parent, String text) {
        return "\""
                + id
                + "\": {\"id\": \""
                + id
                + "\", \"parent\": "
                + parent
                + ", \"children\": [],"
                + " \"message\": {\"author\": {\"role\": \"user\"},"
                + " \"content\": {\"content_type\": \"text\", \"parts\": [\""
                + text
                + "\"]}}}";
    }

    @Test
    void aMissingOrNonObjectMappingConvertsToNull() throws Exception {
        assertNull(format.convert(tree("{\"title\": \"x\"}"), "k", "chatgpt", null, collector()));
        assertNull(format.convert(tree("{\"mapping\": 5}"), "k", "chatgpt", null, collector()));
    }

    @Test
    void aMappingOverTheNodeCapWarnsAndConvertsToNull() throws Exception {
        ChatImportCollector c =
                new ChatImportCollector(
                        new Limits(
                                5000,
                                2000,
                                50_000,
                                500_000,
                                20_000_000L,
                                1,
                                10_000,
                                64L << 20,
                                100),
                        true,
                        null);
        assertNull(
                format.convert(
                        tree("{\"mapping\": {\"a\": {}, \"b\": {}}}"), "k", "chatgpt", null, c));
        assertEquals(1, c.result().warnings().size());
    }

    @Test
    void codeContentBecomesAFencedBlockWithASanitisedLanguage() throws Exception {
        String conv =
                """
            [{"title": "Code", "conversation_id": "cc", "current_node": "n",
              "mapping": {
                "n": {"id": "n", "parent": null, "children": [],
                  "message": {"author": {"role": "assistant"}, "recipient": "all",
                    "content": {"content_type": "code", "language": "python", "text": "print(1)"}}}}}]
            """;
        JsonNode node = tree(conv).get(0);
        ParsedChat chat = format.convert(node, "k", "chatgpt", "cc", collector());

        assertEquals("```python\nprint(1)\n```", chat.messages().get(0).content());

        String weirdLang = conv.replace("\"python\"", "\"unknown\"");
        ParsedChat noLang =
                format.convert(tree(weirdLang).get(0), "k", "chatgpt", "cc", collector());
        assertEquals("```\nprint(1)\n```", noLang.messages().get(0).content());
    }

    @Test
    void assetPointerPartsBecomeAttachmentNamesAndToolRecipientsAreDropped() throws Exception {
        String conv =
                """
            [{"title": "Media", "conversation_id": "mm", "current_node": "n3",
              "mapping": {
                "n1": {"id": "n1", "parent": null, "children": ["n2"],
                  "message": {"author": {"role": "user"}, "create_time": 1,
                    "content": {"content_type": "multimodal_text", "parts": [
                      {"content_type": "audio_asset_pointer"},
                      "listen to this"]}}},
                "n2": {"id": "n2", "parent": "n1", "children": ["n3"],
                  "message": {"author": {"role": "assistant"}, "recipient": "python",
                    "content": {"content_type": "text", "parts": ["tool call, not chat"]}}},
                "n3": {"id": "n3", "parent": "n2", "children": [],
                  "message": {"author": {"role": "assistant"}, "recipient": "all",
                    "content": {"content_type": "text", "parts": ["sure"]}}}}]
            """;
        ParsedChat chat = format.convert(tree(conv).get(0), "k", "chatgpt", "mm", collector());

        // The python-recipient assistant turn is a tool call, not conversation text.
        assertEquals(2, chat.messageCount());
        assertEquals("listen to this", chat.messages().get(0).content());
        assertEquals(List.of("audio"), chat.messages().get(0).attachments());
        assertEquals("sure", chat.messages().get(1).content());
    }

    @Test
    void aConversationWithNoImportableMessageConvertsToNull() throws Exception {
        String conv =
                """
            {"title": "Empty", "current_node": "n",
             "mapping": {"n": {"id": "n", "parent": null, "children": [],
               "message": {"author": {"role": "system"},
                 "content": {"content_type": "text", "parts": ["hidden"]}}}}}
            """;
        assertNull(format.convert(tree(conv), "k", "chatgpt", null, collector()));
    }

    @Test
    void anUntitledConversationFallsBackToTheFirstUserText() throws Exception {
        String conv =
                """
            {"conversation_id": "untitled", "current_node": "n",
             "mapping": {"""
                        + userNode("n", "null", "what is the capital of France?")
                        + "}}";
        ParsedChat chat = format.convert(tree(conv), "k", "chatgpt", null, collector());

        assertEquals("what is the capital of France?", chat.title());
        assertEquals("what is the capital of France?", chat.firstUserText());
    }

    @Test
    void hiddenAndBlankNodesAreSkippedButStillWalkTheChain() throws Exception {
        String conv =
                """
            {"title": "T", "conversation_id": "h", "current_node": "n3",
             "mapping": {
               "n1": {"id": "n1", "parent": null, "children": ["n2"],
                 "message": {"author": {"role": "user"},
                   "content": {"content_type": "text", "parts": ["first"]},
                   "metadata": {"is_visually_hidden_from_conversation": true}}},
               "n2": {"id": "n2", "parent": "n1", "children": ["n3"], "message": null},
               "n3": {"id": "n3", "parent": "n2", "children": [],
                 "message": {"author": {"role": "assistant"}, "recipient": "all",
                   "content": {"content_type": "reasoning_recap", "text": "chain of thought"},
                   "metadata": {}}}}}
            """;
        // Every node on the live path is filtered or empty: nothing importable -> null.
        assertNull(format.convert(tree(conv), "k", "chatgpt", null, collector()));
    }

    @Test
    void textPartsJoinWithNewlinesAndEmptyPartsAreIgnored() throws Exception {
        String conv =
                """
            {"conversation_id": "join", "current_node": "n",
             "mapping": {"n": {"id": "n", "parent": null, "children": [],
               "message": {"author": {"role": "user"},
                 "content": {"content_type": "text", "parts": ["", "one", {"content_type": "x", "text": "two"}, ""]}}}}}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "chatgpt", null, collector());
        assertEquals("one\ntwo", chat.messages().get(0).content());
    }
}
