package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeImportFormat#convert} beyond the golden conversation: the {@code content[]} text
 * blocks win over the flat {@code text} field (and only {@code type:"text"} blocks count), sender
 * mapping, attachment names from both {@code attachments} and {@code files}, and the early nulls.
 */
class ClaudeImportFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ClaudeImportFormat format = new ClaudeImportFormat();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(bytes(json));
    }

    private static ChatImportCollector collector() {
        return new ChatImportCollector(Limits.defaults(), true, null);
    }

    @Test
    void aMissingOrNonArrayChatMessagesConvertsToNull() throws Exception {
        assertNull(format.convert(tree("{\"name\": \"x\"}"), "k", "claude", null, collector()));
        assertNull(
                format.convert(tree("{\"chat_messages\": {}}"), "k", "claude", null, collector()));
    }

    @Test
    void contentTextBlocksWinOverTheFlatTextField() throws Exception {
        String conv =
                """
            {"uuid": "c1", "name": "N", "chat_messages": [
              {"sender": "human", "text": "flat is a fallback",
               "content": [{"type": "text", "text": "block one"}, {"type": "text", "text": "block two"}]}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "claude", "c1", collector());

        assertEquals("block one\nblock two", chat.messages().get(0).content());
    }

    @Test
    void senderAliasesAndEmptyMessagesAreHandled() throws Exception {
        String conv =
                """
            {"uuid": "c2", "chat_messages": [
              {"sender": "user", "text": "user-alias works"},
              {"sender": "tool", "text": "never imported"},
              {"sender": "human", "text": "   "},
              {"sender": "assistant", "text": "answer"}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "claude", "c2", collector());

        // "user" maps to ROLE_USER, "tool" is skipped, the whitespace-only human text is empty.
        assertEquals(2, chat.messageCount());
        assertEquals("user-alias works", chat.messages().get(0).content());
        assertEquals("assistant", chat.messages().get(1).role());
    }

    @Test
    void attachmentsAndFilesContributeNamesOnlyAndBlankMessagesAreSkipped() throws Exception {
        String conv =
                """
            {"uuid": "c3", "name": "Files", "created_at": "2024-01-02T03:04:05Z",
             "chat_messages": [
               {"sender": "human", "text": "", "created_at": "2024-01-02T03:04:06Z",
                "attachments": [{"file_name": "a.pdf"}], "files": [{"file_name": "b.png"}, {}]}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "claude", "c3", collector());

        // A body-less message still imports when it carries attachment names.
        assertEquals(1, chat.messageCount());
        assertEquals(List.of("a.pdf", "b.png"), chat.messages().get(0).attachments());
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), chat.createdAt());
        assertEquals(Instant.parse("2024-01-02T03:04:06Z"), chat.messages().get(0).timestamp());
    }

    @Test
    void aConversationOfOnlyUnusableMessagesConvertsToNull() throws Exception {
        String conv =
                """
            {"uuid": "c4", "chat_messages": [
              {"sender": "system", "text": "x"}, "not-an-object", {"sender": "human"}]}
            """;
        assertNull(format.convert(tree(conv), "k", "claude", null, collector()));
    }

    @Test
    void contentBlocksThatAreAllNonTextFallBackToTheFlatText() throws Exception {
        String conv =
                """
            {"uuid": "c5", "chat_messages": [
              {"sender": "assistant", "text": "the answer",
               "content": [{"type": "thinking", "thinking": "..."}]}]}
            """;
        ParsedChat chat = format.convert(tree(conv), "k", "claude", null, collector());
        assertEquals("the answer", chat.messages().get(0).content());
    }
}
