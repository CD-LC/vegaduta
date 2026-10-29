package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link VegaDutaImportFormat#convert}: the platform's own export is as untrusted as any upload -
 * only user/assistant text survives, attachment entries must be strings, {@code truncated} carries
 * through, and the declared source is only honoured when it is a real one (that half lives in
 * {@code ChatImportFormatDispatchTest}).
 */
class VegaDutaImportFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VegaDutaImportFormat format = new VegaDutaImportFormat();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(bytes(json));
    }

    private static ChatImportCollector collector() {
        return new ChatImportCollector(Limits.defaults(), true, null);
    }

    @Test
    void aMissingOrNonArrayMessagesConvertsToNull() throws Exception {
        assertNull(format.convert(tree("{\"title\": \"x\"}"), "k", "vegaduta", null, collector()));
        assertNull(format.convert(tree("{\"messages\": {}}"), "k", "vegaduta", null, collector()));
    }

    @Test
    void truncatedOnTheExportMarksTheImportedChat() throws Exception {
        String conv = """
            {"id": "s1", "title": "Cut", "truncated": true, "createdAt": "2024-05-06T07:08:09Z",
             "messages": [{"role": "user", "content": "hi"}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "vegaduta", "s1", collector());

        assertTrue(chat.truncated());
        assertEquals(Instant.parse("2024-05-06T07:08:09Z"), chat.createdAt());
        assertNull(chat.updatedAt(), "the own-export shape carries no updatedAt");
    }

    @Test
    void nonTextualAttachmentEntriesAndNonObjectMessagesAreDropped() throws Exception {
        String conv = """
            {"id": "s2", "messages": [
              {"role": "user", "content": "see files", "attachments": ["a.txt", 7, {"x": 1}, "  ", "b.png"]},
              "not a message",
              {"role": "system", "content": "hidden"},
              {"role": "assistant", "content": null}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "vegaduta", "s2", collector());

        assertEquals(1, chat.messageCount());
        assertEquals(List.of("a.txt", "b.png"), chat.messages().get(0).attachments());
    }

    @Test
    void aMessageTimestampParsesAndGarbageIsDropped() throws Exception {
        String conv = """
            {"id": "s3", "messages": [
              {"role": "user", "content": "q", "timestamp": "2024-01-01T00:00:01Z"},
              {"role": "assistant", "content": "a", "timestamp": "not a date"}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "vegaduta", "s3", collector());

        assertEquals(Instant.parse("2024-01-01T00:00:01Z"), chat.messages().get(0).timestamp());
        assertNull(chat.messages().get(1).timestamp());
    }

    @Test
    void aConversationWithNoUsableMessageConvertsToNull() throws Exception {
        String conv = """
            {"id": "s4", "messages": [{"role": "tool", "content": "x"}, {"role": "user", "content": " "}]}
            """;
        assertNull(format.convert(tree(conv), "k", "vegaduta", null, collector()));
    }
}
