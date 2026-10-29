package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatImportCollector} is the parse loop's state: it counts every conversation-shaped object
 * it sees, de-duplicates on the import key, honours a selection set, enforces the conversation and
 * per-import character caps, and caps the warnings list. The format dispatch itself is covered in
 * {@code ChatImportFormatDispatchTest}.
 */
class ChatImportCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode claude(String uuid, String text) throws Exception {
        return MAPPER.readTree(bytes(
            "{\"uuid\": \"" + uuid + "\", \"chat_messages\": [{\"sender\": \"human\", \"text\": \""
                + text + "\"}]}"));
    }

    private static Limits limits(int maxConversations, long maxCharsPerImport) {
        return new Limits(maxConversations, 2000, 50_000, 500_000, maxCharsPerImport, 20_000, 10_000,
            64L << 20, 100);
    }

    @Test
    void nonObjectsAndUnmatchedShapesAreSkipped() throws Exception {
        ChatImportCollector c = new ChatImportCollector(Limits.defaults(), true, null);

        c.accept(null);
        c.accept(MAPPER.readTree("5"));
        c.accept(MAPPER.readTree("{\"unrelated\": true}"));
        c.accept(claude("u-1", "hi"));

        ParseResult r = c.result();
        assertEquals(1, r.chats().size());
        assertEquals(3, r.skipped());
        assertEquals(1, r.totalConversations());
    }

    @Test
    void aDuplicateKeyIsSkippedAndNeverReachesTheChats() throws Exception {
        ChatImportCollector c = new ChatImportCollector(Limits.defaults(), true, null);

        c.accept(claude("same", "one"));
        c.accept(claude("same", "two"));

        ParseResult r = c.result();
        assertEquals(1, r.chats().size());
        assertEquals("one", r.chats().get(0).messages().get(0).content());
        assertEquals(1, r.skipped());
        assertEquals(1, r.totalConversations(), "total counts unique conversations that passed the seen-check");
    }

    @Test
    void aConversationWithNoImportableMessagesIsSkipped() throws Exception {
        ChatImportCollector c = new ChatImportCollector(Limits.defaults(), true, null);

        c.accept(MAPPER.readTree("{\"uuid\": \"empty\", \"chat_messages\": []}"));

        assertEquals(1, c.result().skipped());
        assertTrue(c.result().chats().isEmpty());
    }

    @Test
    void theConversationCapFlagsDone() throws Exception {
        ChatImportCollector c = new ChatImportCollector(limits(1, 20_000_000L), true, null);

        c.accept(claude("a", "x"));
        c.accept(claude("b", "y"));

        assertTrue(c.done());
        assertTrue(c.result().capReached());
        assertEquals(1, c.result().chats().size());
        // The second conversation was not even counted - the cap short-circuits before index++.
        assertEquals(1, c.result().totalConversations());
    }

    @Test
    void aSelectionSkipsUnchosenConversationsAndStopsWhenAllFound() throws Exception {
        ChatImportCollector c =
                new ChatImportCollector(Limits.defaults(), true, Set.of("wanted"));

        c.accept(claude("unwanted", "x"));
        c.accept(claude("wanted", "y"));

        assertTrue(c.done(), "all selected keys found: no need to read more of the file");
        assertEquals(1, c.result().chats().size());
        assertEquals("wanted", c.result().chats().get(0).key());
        // The unselected conversation was seen (counted) but is not "skipped" - it was never a candidate.
        assertEquals(2, c.result().totalConversations());
        assertEquals(0, c.result().skipped());
    }

    @Test
    void overTheImportCharBudgetTheChatIsSkippedWithAWarning() throws Exception {
        ChatImportCollector c = new ChatImportCollector(limits(5000, 5L), true, null);

        c.accept(claude("a", "more than five chars"));
        c.accept(claude("b", "also too much"));

        ParseResult r = c.result();
        assertTrue(r.chats().isEmpty());
        assertEquals(2, r.skipped());
        assertEquals(2, r.warnings().size(), "one warning per over-budget conversation");
        assertTrue(r.warnings().get(0).contains("too large"));
    }

    @Test
    void warningsAreCappedAtMaxWarnings() {
        ChatImportCollector c = new ChatImportCollector(Limits.defaults(), true, null);

        for (int i = 0; i < ChatImportParser.MAX_WARNINGS + 10; i++) {
            c.warn("w" + i);
        }

        assertEquals(ChatImportParser.MAX_WARNINGS, c.result().warnings().size());
    }

    @Test
    void previewModeDoesNotEnforceTheImportCharBudget() throws Exception {
        ChatImportCollector c = new ChatImportCollector(limits(5000, 1L), false, null);

        c.accept(claude("a", "anything"));

        assertFalse(c.result().capReached());
        assertEquals(1, c.result().chats().size());
        assertTrue(c.result().chats().get(0).messages().isEmpty());
    }
}
