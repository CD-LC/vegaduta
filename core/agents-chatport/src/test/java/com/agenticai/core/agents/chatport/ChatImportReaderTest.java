package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatImportReader#read} at the container layer the parser tests reach only indirectly: zip
 * sniffing, the {@code conversations(-N)?.json} entry-name rule, the shared inflated-byte and
 * entry-count budgets, non-array/non-object documents, and a file that ends mid-conversation.
 */
class ChatImportReaderTest {

    private static ChatImportCollector collector(Limits limits) {
        return new ChatImportCollector(limits, true, null);
    }

    private static Limits limits(int maxZipEntries, long maxUncompressedBytes) {
        return new Limits(
                5000,
                2000,
                50_000,
                500_000,
                20_000_000L,
                20_000,
                maxZipEntries,
                maxUncompressedBytes,
                100);
    }

    private static final String ONE_CLAUDE =
            "[{\"uuid\":\"z1\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\"hi\"}]}]";

    @Test
    void aBareJsonArrayReadsWithoutTheZipPath() throws Exception {
        ChatImportCollector c = collector(Limits.defaults());

        ChatImportReader.read(bytes(ONE_CLAUDE), c);

        assertEquals(1, c.result().chats().size());
        assertEquals("z1", c.result().chats().get(0).key());
    }

    @Test
    void zipEntriesArePickedByTheConversationsNameRule() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("README.txt", bytes("not json"));
        entries.put("dir/Conversations.JSON", bytes(ONE_CLAUDE));
        entries.put(
                "conversations-2.json",
                bytes(
                        "[{\"uuid\":\"z2\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\"two\"}]}]"));
        entries.put("conversations.json.bak", bytes(ONE_CLAUDE));

        ChatImportCollector c = collector(Limits.defaults());
        ChatImportReader.read(ChatPortFixtures.zip(entries), c);

        // README.txt and conversations.json.bak are drained but never parsed.
        assertEquals(2, c.result().chats().size());
        assertEquals("z1", c.result().chats().get(0).key());
        assertEquals("z2", c.result().chats().get(1).key());
    }

    @Test
    void theEntryCapCountsEveryEntryNotJustConversationOnes() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.bin", bytes("x"));
        entries.put("conversations.json", bytes(ONE_CLAUDE));

        ChatImportCollector c = collector(limits(1, 64L << 20));

        ChatImportReader.LimitExceededException e =
                assertThrows(
                        ChatImportReader.LimitExceededException.class,
                        () -> ChatImportReader.read(ChatPortFixtures.zip(entries), c));
        assertTrue(e.getMessage().contains("too many files"));
    }

    @Test
    void theSharedInflatedByteBudgetCoversEveryEntry() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // A non-conversation entry still burns the budget: zip bombs hide anywhere.
        entries.put("pad.bin", new byte[10_000]);
        entries.put("conversations.json", bytes(ONE_CLAUDE));

        ChatImportCollector c = collector(limits(10_000, 5_000));

        ChatImportReader.LimitExceededException e =
                assertThrows(
                        ChatImportReader.LimitExceededException.class,
                        () -> ChatImportReader.read(ChatPortFixtures.zip(entries), c));
        assertTrue(e.getMessage().contains("expands"));
    }

    @Test
    void nonContainerDocumentsAreRefusedAtTheFirstToken() {
        ChatImportCollector c = collector(Limits.defaults());

        assertThrows(
                ChatImportReader.LimitExceededException.class,
                () -> ChatImportReader.read(bytes("42"), c));
        assertThrows(
                ChatImportReader.LimitExceededException.class,
                () -> ChatImportReader.read(bytes("\"a string\""), c));
    }

    @Test
    void aFileEndingMidConversationIsAnIoFailure() {
        ChatImportCollector c = collector(Limits.defaults());

        assertThrows(
                IOException.class, () -> ChatImportReader.read(bytes("[{\"chat_messages\": ["), c));
    }

    @Test
    void aSingleTopLevelConversationObjectImports() throws Exception {
        ChatImportCollector c = collector(Limits.defaults());

        ChatImportReader.read(
                bytes(
                        "{\"uuid\":\"solo\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\"hi\"}]}"),
                c);

        assertEquals(1, c.result().chats().size());
        assertEquals("solo", c.result().chats().get(0).key());
    }

    @Test
    void nonObjectArrayElementsAreSkippedNotFatal() throws Exception {
        ChatImportCollector c = collector(Limits.defaults());

        ChatImportReader.read(
                bytes("[5, \"x\", null, " + "{\"uuid\":\"ok\",\"chat_messages\":[]}" + "]"), c);

        // Three junk elements plus one conversation with no importable messages.
        assertTrue(c.result().skipped() >= 3);
        assertTrue(c.result().chats().isEmpty());
    }

    @Test
    void anEnvelopeObjectStreamsItsConversationsAndIgnoresTheRest() throws Exception {
        String envelope =
                """
            {"schema": "vegaduta.chat-export", "version": 1, "exportedAt": "2026-10-05T10:00:00Z",
             "conversations": [
               {"id": "e1", "messages": [{"role": "user", "content": "hi"}]},
               {"id": "e2", "messages": [{"role": "assistant", "content": "only-assistant"}]}],
             "other": {"nested": [1, 2, 3]}}
            """;

        ChatImportCollector c = collector(Limits.defaults());
        ChatImportReader.read(bytes(envelope), c);

        // e2 imports: an assistant-only transcript is still importable.
        assertEquals(2, c.result().chats().size());
        assertEquals("e1", c.result().chats().get(0).key());
    }
}
