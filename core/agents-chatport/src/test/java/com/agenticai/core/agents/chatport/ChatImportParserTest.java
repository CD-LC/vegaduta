package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParseResult;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ChatImportParserTest {

    private static final Limits DEFAULTS = Limits.defaults();

    private static Limits limits(
            int maxConversations,
            int maxMessages,
            int maxCharsPerMessage,
            int maxMapping,
            int maxZipEntries,
            long maxUncompressed) {
        return new Limits(
                maxConversations,
                maxMessages,
                maxCharsPerMessage,
                500_000,
                20_000_000L,
                maxMapping,
                maxZipEntries,
                maxUncompressed,
                100);
    }

    // ---- ChatGPT ----

    @Test
    void chatGptGoldenConversationFollowsTheLivePathAndSkipsNoise() {
        ParseResult r =
                ChatImportParser.parse(bytes(ChatPortFixtures.CHATGPT_ONE), DEFAULTS, true, null);

        assertEquals(1, r.chats().size());
        assertEquals(1, r.totalConversations());
        assertEquals(0, r.skipped());
        ParsedChat c = r.chats().get(0);
        assertEquals("chatgpt", c.source());
        assertEquals("conv-gpt-1", c.sourceId());
        assertEquals("conv-gpt-1", c.key());
        assertEquals("Trip planning", c.title());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_500L), c.createdAt());
        assertEquals(Instant.ofEpochMilli(1_700_000_100_000L), c.updatedAt());
        assertFalse(c.truncated());

        // hidden system, abandoned branch, python call and tool output are all gone: user /
        // assistant / user
        assertEquals(3, c.messageCount());
        assertEquals(3, c.messages().size());
        assertEquals("user", c.messages().get(0).role());
        assertEquals("Plan a 3-day trip to Kyoto.", c.messages().get(0).content());
        assertEquals(Instant.ofEpochMilli(1_700_000_001_000L), c.messages().get(0).timestamp());
        assertEquals("assistant", c.messages().get(1).role());
        assertEquals("Day 1: Fushimi Inari.\nDay 2: Arashiyama.", c.messages().get(1).content());
        assertEquals("user", c.messages().get(2).role());
        assertEquals("Thanks! Add food spots.", c.messages().get(2).content());
        assertEquals(List.of("image", "map.png"), c.messages().get(2).attachments());
        assertEquals("Plan a 3-day trip to Kyoto.", c.firstUserText());
        for (ChatImportParser.ParsedMessage m : c.messages()) {
            assertFalse(m.content().contains("Branch not taken"));
            assertFalse(m.content().contains("print(1)"));
        }
    }

    @Test
    void previewModeCountsButKeepsNoMessages() {
        ParseResult r =
                ChatImportParser.parse(bytes(ChatPortFixtures.CHATGPT_ONE), DEFAULTS, false, null);
        ParsedChat c = r.chats().get(0);
        assertTrue(c.messages().isEmpty());
        assertEquals(3, c.messageCount());
        assertEquals("Plan a 3-day trip to Kyoto.", c.firstUserText());
        assertTrue(c.chars() > 0);
    }

    @Test
    void aCyclicParentChainTerminatesWithAWarning() {
        ParseResult r =
                ChatImportParser.parse(bytes(ChatPortFixtures.CHATGPT_CYCLE), DEFAULTS, true, null);
        assertEquals(1, r.chats().size());
        assertEquals(2, r.chats().get(0).messageCount());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("cyclic")));
    }

    @Test
    void aMissingCurrentNodeFallsBackToTheNewestLeaf() {
        ParseResult r =
                ChatImportParser.parse(
                        bytes(ChatPortFixtures.CHATGPT_NO_CURRENT_NODE), DEFAULTS, true, null);
        ParsedChat c = r.chats().get(0);
        assertEquals(2, c.messages().size());
        assertEquals("question", c.messages().get(0).content());
        assertEquals("answer", c.messages().get(1).content());
        assertEquals("No pointer", c.title());
    }

    @Test
    void aMappingBiggerThanTheNodeCapIsSkippedNotParsed() {
        Limits tiny = limits(5000, 2000, 50_000, 2, 10_000, 200L * 1024 * 1024);
        ParseResult r =
                ChatImportParser.parse(bytes(ChatPortFixtures.CHATGPT_ONE), tiny, true, null);
        assertTrue(r.chats().isEmpty());
        assertEquals(1, r.skipped());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void selectionConvertsOnlyTheChosenConversations() {
        ParseResult r =
                ChatImportParser.parse(
                        bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS),
                        DEFAULTS,
                        true,
                        Set.of("conv-b"));
        assertEquals(1, r.chats().size());
        assertEquals("conv-b", r.chats().get(0).key());
        assertEquals("Second", r.chats().get(0).title());
        assertEquals("Hello B", r.chats().get(0).messages().get(0).content());
    }

    @Test
    void anUnknownSelectionKeyYieldsNothing() {
        ParseResult r =
                ChatImportParser.parse(
                        bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS),
                        DEFAULTS,
                        true,
                        Set.of("nope"));
        assertTrue(r.chats().isEmpty());
        assertEquals(2, r.totalConversations());
    }

    @Test
    void duplicateConversationIdsAreImportedOnce() {
        String twice =
                "["
                        + ChatPortFixtures.CHATGPT_ONE
                                .strip()
                                .substring(1, ChatPortFixtures.CHATGPT_ONE.strip().length() - 1)
                        + ","
                        + ChatPortFixtures.CHATGPT_ONE
                                .strip()
                                .substring(1, ChatPortFixtures.CHATGPT_ONE.strip().length() - 1)
                        + "]";
        ParseResult r = ChatImportParser.parse(bytes(twice), DEFAULTS, true, null);
        assertEquals(1, r.chats().size());
        assertEquals(1, r.skipped());
    }

    // ---- Claude ----

    @Test
    void claudeGoldenConversationKeepsTextAndNamesOnly() {
        ParseResult r =
                ChatImportParser.parse(bytes(ChatPortFixtures.CLAUDE_ONE), DEFAULTS, true, null);

        assertEquals(1, r.chats().size());
        ParsedChat c = r.chats().get(0);
        assertEquals("claude", c.source());
        assertEquals("c-claude-1", c.sourceId());
        assertEquals("Resume help", c.title());
        assertEquals(Instant.parse("2024-03-01T10:00:00Z"), c.createdAt());
        assertEquals(Instant.parse("2024-03-01T10:05:00Z"), c.updatedAt());
        assertEquals(2, c.messages().size()); // the system message is skipped
        assertEquals("user", c.messages().get(0).role());
        assertEquals("Can you review my resume?", c.messages().get(0).content());
        assertEquals(List.of("resume.pdf", "photo.png"), c.messages().get(0).attachments());
        assertEquals(Instant.parse("2024-03-01T10:00:01Z"), c.messages().get(0).timestamp());
        assertEquals("assistant", c.messages().get(1).role());
        assertEquals("Sure - happy to.", c.messages().get(1).content());
        for (ChatImportParser.ParsedMessage m : c.messages()) {
            assertFalse(m.content().contains("EXTRACTED-BODY"));
            assertFalse(m.content().contains("hidden reasoning"));
        }
    }

    @Test
    void claudeFallsBackToTheTextFieldWhenThereAreNoContentBlocks() {
        String json =
                """
                [{"uuid": "c2", "name": "", "created_at": "2024-01-01T00:00:00Z", "chat_messages": [
                  {"uuid": "a", "sender": "human", "text": "plain text only"},
                  {"uuid": "b", "sender": "assistant", "text": "reply"}]}]
                """;
        ParseResult r = ChatImportParser.parse(bytes(json), DEFAULTS, true, null);
        ParsedChat c = r.chats().get(0);
        assertEquals("plain text only", c.messages().get(0).content());
        assertEquals("plain text only", c.title()); // blank name -> first user message
    }

    // ---- caps and hostile input ----

    @Test
    void messageCharAndCountCapsTruncateAndFlag() {
        String json =
                """
[{"uuid": "big", "name": "Big", "chat_messages": [
  {"sender": "human", "text": "0123456789012345678901234567890123456789"},
  {"sender": "assistant", "text": "b"}, {"sender": "human", "text": "c"}, {"sender": "assistant", "text": "d"}]}]
""";
        ParseResult r =
                ChatImportParser.parse(
                        bytes(json), limits(5000, 3, 10, 20_000, 10_000, 1 << 28), true, null);
        ParsedChat c = r.chats().get(0);
        assertEquals(3, c.messages().size());
        assertTrue(c.truncated());
        assertTrue(c.messages().get(0).content().startsWith("0123456789"));
        assertTrue(c.messages().get(0).content().endsWith("[truncated]"));
    }

    @Test
    void theConversationScanCapStopsReadingAndSaysSo() {
        ParseResult r =
                ChatImportParser.parse(
                        bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS),
                        limits(1, 2000, 50_000, 20_000, 10_000, 1 << 28),
                        false,
                        null);
        assertEquals(1, r.chats().size());
        assertTrue(r.capReached());
    }

    @Test
    void deeplyNestedJsonIsRefusedNotRecursedInto() {
        String deep = "[{\"mapping\":{\"x\":" + "[".repeat(300) + "]".repeat(300) + "}}]";
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ChatImportParser.parse(bytes(deep), DEFAULTS, true, null));
        assertTrue(e.getMessage().contains("safe limits"));
    }

    @Test
    void nonExportShapesAreHandledGracefully() {
        // an object with neither mapping nor chat_messages is just an unknown conversation
        ParseResult r =
                ChatImportParser.parse(bytes("[{\"hello\": 1}, 5, \"x\"]"), DEFAULTS, true, null);
        assertTrue(r.chats().isEmpty());
        assertEquals(3, r.skipped());

        assertThrows(
                IllegalArgumentException.class,
                () -> ChatImportParser.parse(bytes("42"), DEFAULTS, true, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatImportParser.parse(bytes("not json at all"), DEFAULTS, true, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatImportParser.parse(new byte[0], DEFAULTS, true, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatImportParser.parse(null, DEFAULTS, true, null));
    }

    // ---- this platform's own export (prod signed-in pass 2026-10-05, bug 4) ----

    private static ChatExportRenderer.Conversation ownConversation(
            String id, String source, String title) {
        return new ChatExportRenderer.Conversation(
                id,
                source,
                title,
                "e2e-agent",
                Instant.parse("2026-10-05T09:00:00Z"),
                null,
                List.of(
                        new ChatExportRenderer.Message(
                                "user",
                                "Count from 1 to 3.",
                                Instant.parse("2026-10-05T09:00:01Z"),
                                List.of("numbers.txt")),
                        new ChatExportRenderer.Message(
                                "assistant",
                                "1\n2\n3",
                                Instant.parse("2026-10-05T09:00:04Z"),
                                List.of()),
                        new ChatExportRenderer.Message("user", "Thanks.", null, List.of()),
                        new ChatExportRenderer.Message(
                                "assistant", "You're welcome.", null, List.of())),
                false,
                List.of());
    }

    /**
     * Export -> import: the app's own JSON export comes back with the same messages, roles and
     * order.
     */
    @Test
    void theAppsOwnJsonExportRoundTripsThroughTheImporter() {
        ChatExportRenderer.Conversation exported =
                ownConversation("session-abc", "vegaduta", "Counting");
        String file =
                ChatExportRenderer.render(
                        ChatExportRenderer.Format.JSON,
                        List.of(exported),
                        Instant.parse("2026-10-05T10:00:00Z"));
        assertTrue(
                file.replaceAll("\\s", "").contains("\"schema\":\"vegaduta.chat-export\""), file);

        ParseResult r = ChatImportParser.parse(bytes(file), DEFAULTS, true, null);

        assertEquals(1, r.chats().size());
        assertEquals(1, r.totalConversations());
        assertEquals(0, r.skipped());
        ParsedChat c = r.chats().get(0);
        assertEquals("vegaduta", c.source());
        assertEquals("session-abc", c.sourceId());
        assertEquals("session-abc", c.key());
        assertEquals("Counting", c.title());
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), c.createdAt());
        assertEquals(exported.messages().size(), c.messages().size());
        for (int i = 0; i < exported.messages().size(); i++) {
            ChatExportRenderer.Message before = exported.messages().get(i);
            ChatImportParser.ParsedMessage after = c.messages().get(i);
            assertEquals(before.role(), after.role(), "role of message " + i);
            assertEquals(before.content(), after.content(), "content of message " + i);
            assertEquals(before.timestamp(), after.timestamp(), "timestamp of message " + i);
            assertEquals(before.attachments(), after.attachments(), "attachments of message " + i);
        }
        assertEquals("Count from 1 to 3.", c.firstUserText());
    }

    @Test
    void anExportOfSeveralChatsImportsEachAndAReExportedImportKeepsItsOriginalSource() {
        String file =
                ChatExportRenderer.render(
                        ChatExportRenderer.Format.JSON,
                        List.of(
                                ownConversation("session-1", "vegaduta", "First"),
                                ownConversation("conv-gpt-1", "chatgpt", "Trip planning"),
                                ownConversation("session-1", "vegaduta", "Duplicate id")),
                        Instant.parse("2026-10-05T10:00:00Z"));

        ParseResult preview = ChatImportParser.parse(bytes(file), DEFAULTS, false, null);

        assertEquals(2, preview.chats().size());
        assertEquals(1, preview.skipped()); // the duplicate id
        assertEquals("vegaduta", preview.chats().get(0).source());
        // Re-importing a chat that was itself imported from ChatGPT de-duplicates against the
        // original import.
        assertEquals("chatgpt", preview.chats().get(1).source());
        assertEquals("conv-gpt-1", preview.chats().get(1).sourceId());
        assertEquals(4, preview.chats().get(1).messageCount());
        assertTrue(preview.chats().get(1).messages().isEmpty()); // preview keeps counts only

        ParseResult picked =
                ChatImportParser.parse(bytes(file), DEFAULTS, true, Set.of("conv-gpt-1"));
        assertEquals(1, picked.chats().size());
        assertEquals("Trip planning", picked.chats().get(0).title());
    }

    @Test
    void anOwnExportIsAsUntrustedAsAnyOtherUpload() {
        String file =
                """
{"schema": "vegaduta.chat-export", "version": 1, "conversations": [
  {"id": "s1", "source": "something-else", "title": "T", "messages": [
    {"role": "system", "content": "hidden instructions"},
    {"role": "tool", "content": "tool output"},
    {"role": "user", "content": "hi\\u0000 there", "attachments": ["a.txt", 7, {"x": 1}]},
    {"role": "assistant", "content": ""},
    "not a message",
    {"role": "assistant", "content": "hello"}]},
  {"id": "s2", "title": "No usable messages", "messages": [{"role": "system", "content": "x"}]},
  5]}
""";

        ParseResult r = ChatImportParser.parse(bytes(file), DEFAULTS, true, null);

        assertEquals(1, r.chats().size());
        assertEquals(2, r.skipped());
        ParsedChat c = r.chats().get(0);
        assertEquals("vegaduta", c.source()); // an unknown declared source is not trusted
        assertEquals(2, c.messages().size());
        assertEquals("user", c.messages().get(0).role());
        assertFalse(c.messages().get(0).content().contains("\u0000"));
        assertEquals(List.of("a.txt"), c.messages().get(0).attachments());
        assertEquals("hello", c.messages().get(1).content());
    }

    @Test
    void aSingleChatGptConversationObjectStillImportsAfterTheEnvelopeChange() {
        String one = ChatPortFixtures.CHATGPT_ONE.strip();
        String single =
                one.substring(
                        1, one.length() - 1); // the lone object, without the surrounding array

        ParseResult r = ChatImportParser.parse(bytes(single), DEFAULTS, true, null);

        assertEquals(1, r.chats().size());
        assertEquals("chatgpt", r.chats().get(0).source());
        assertEquals(3, r.chats().get(0).messages().size());
    }

    @Test
    void controlAndInvisibleCharactersAreStrippedFromContent() {
        // raw U+202E (bidi override), U+E0041 (TAG A) and a NUL escape inside the JSON strings
        String json =
                "[{\"uuid\":\"c3\",\"name\":\"T\\u0000itle\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\"he\\u202Ello\\ud83d\\udc4b\\udb40\\udc41\"}]}]";
        ParseResult r = ChatImportParser.parse(bytes(json), DEFAULTS, true, null);
        ParsedChat c = r.chats().get(0);
        assertEquals("Title", c.title());
        assertEquals("hello👋", c.messages().get(0).content());
    }

    @Test
    void timestampsOutsideAnyPlausibleRangeAreDropped() {
        assertNull(ChatImportParser.epochSeconds(new tools.jackson.databind.node.DoubleNode(-5)));
        assertNull(ChatImportParser.epochSeconds(new tools.jackson.databind.node.DoubleNode(9e15)));
        assertNull(ChatImportParser.epochSeconds(new tools.jackson.databind.node.StringNode("12")));
        assertNull(ChatImportParser.parseIso("yesterday"));
        assertEquals(
                Instant.parse("2024-03-01T10:00:00Z"),
                ChatImportParser.parseIso("2024-03-01T11:00:00+01:00"));
    }

    // ---- per-conversation tree budget (a wide object must not be materialised) ----

    private static final String OK_CLAUDE_CONVERSATION =
            "{\"uuid\":\"ok\",\"name\":\"Fine\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\"hi\"},{\"sender\":\"assistant\",\"text\":\"hello\"}]}";

    private static Limits tinyTree(int maxNodes, long maxChars) {
        return new Limits(
                5000,
                2000,
                50_000,
                500_000,
                20_000_000L,
                20_000,
                10_000,
                64L * 1024 * 1024,
                10_000,
                maxNodes,
                maxChars);
    }

    @Test
    void aConversationOverTheNodeBudgetIsSkippedAndTheNextOneStillParses() {
        // the oversized one nests arrays and objects so the skip has to close several levels and
        // stay in step
        String wide =
                "{\"uuid\":\"big\",\"chat_messages\":[{\"a\":["
                        + "0,".repeat(5000)
                        + "0]},{\"b\":{\"c\":[1,2]}}],\"x\":{\"y\":[[],[3]]}}";
        String json = "[" + wide + "," + OK_CLAUDE_CONVERSATION + "]";

        ParseResult r = ChatImportParser.parse(bytes(json), tinyTree(1000, 1_000_000L), true, null);

        assertEquals(1, r.chats().size());
        assertEquals("ok", r.chats().get(0).key());
        assertEquals(1, r.skipped());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void aConversationOverTheCharacterBudgetIsSkipped() {
        String fat =
                "{\"uuid\":\"fat\",\"chat_messages\":[{\"sender\":\"human\",\"text\":\""
                        + "x".repeat(5000)
                        + "\"}]}";
        ParseResult r =
                ChatImportParser.parse(
                        bytes("[" + fat + "," + OK_CLAUDE_CONVERSATION + "]"),
                        tinyTree(1_000_000, 2000L),
                        true,
                        null);
        assertEquals(1, r.chats().size());
        assertEquals("ok", r.chats().get(0).key());
        assertEquals(1, r.skipped());
    }

    @Test
    void oneConversationWithMillionsOfArrayElementsIsSkippedUnderTheDefaultBudget() {
        String json =
                "[{\"uuid\":\"huge\",\"chat_messages\":["
                        + "0,".repeat(1_200_000)
                        + "0]},"
                        + OK_CLAUDE_CONVERSATION
                        + "]";
        ParseResult r = ChatImportParser.parse(bytes(json), DEFAULTS, true, null);
        assertEquals(1, r.chats().size());
        assertEquals("ok", r.chats().get(0).key());
        assertEquals(1, r.skipped());
    }

    @Test
    void aSmallZipPaddedToDefeatTheRatioBudgetStillCannotMaterialiseAHugeConversation() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "conversations.json",
                bytes("[{\"uuid\":\"huge\",\"chat_messages\":[" + "0,".repeat(1_500_000) + "0]}]"));
        entries.put("padding.bin", new byte[2_000_000]);
        byte[] zip = ChatPortFixtures.zip(entries);
        assertTrue(zip.length < 100_000, "the test zip must stay tiny, was " + zip.length);

        ParseResult r =
                ChatImportParser.parse(
                        zip,
                        tinyTree(ChatImportParser.Limits.DEFAULT_TREE_NODES, 16_000_000L),
                        true,
                        null);

        assertTrue(r.chats().isEmpty());
        assertEquals(1, r.skipped());
    }

    @Test
    void theDefaultInflatedByteBudgetIsSixtyFourMegabytes() {
        assertEquals(64L * 1024 * 1024, Limits.defaults().maxUncompressedBytes());
        assertEquals(Limits.DEFAULT_TREE_NODES, Limits.defaults().maxTreeNodes());
    }

    // ---- zip ----

    @Test
    void conversationsJsonInsideAZipIsRead() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("export/chat.html", bytes("<html></html>"));
        entries.put("export/conversations.json", bytes(ChatPortFixtures.CHATGPT_ONE));
        entries.put("export/user.json", bytes("{}"));
        ParseResult r = ChatImportParser.parse(ChatPortFixtures.zip(entries), DEFAULTS, true, null);
        assertEquals(1, r.chats().size());
        assertEquals("Trip planning", r.chats().get(0).title());
    }

    @Test
    void splitConversationFilesInsideAZipAreAllRead() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("conversations-000.json", bytes(ChatPortFixtures.CHATGPT_ONE));
        entries.put("conversations-001.json", bytes(ChatPortFixtures.CLAUDE_ONE));
        ParseResult r =
                ChatImportParser.parse(ChatPortFixtures.zip(entries), DEFAULTS, false, null);
        assertEquals(2, r.chats().size());
    }

    @Test
    void aZipWithNoConversationsFileYieldsNothing() {
        ParseResult r =
                ChatImportParser.parse(
                        ChatPortFixtures.zipOf("readme.txt", "hello"), DEFAULTS, true, null);
        assertTrue(r.chats().isEmpty());
        assertEquals(0, r.totalConversations());
    }

    @Test
    void aZipBombIsRefusedByTheInflatedByteBudget() {
        // two million spaces compress to a couple of KB; the budget below is 100 KB
        String spaces = " ".repeat(2_000_000);
        byte[] zip = ChatPortFixtures.zipOf("conversations.json", "[" + spaces + "]");
        Limits small = limits(5000, 2000, 50_000, 20_000, 10_000, 100_000L);
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ChatImportParser.parse(zip, small, true, null));
        assertTrue(e.getMessage().contains("expands"));
    }

    @Test
    void aBombHiddenInAnIgnoredEntryIsStillCounted() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("junk.bin", new byte[3_000_000]);
        entries.put("conversations.json", bytes(ChatPortFixtures.CHATGPT_ONE));
        Limits small = limits(5000, 2000, 50_000, 20_000, 10_000, 200_000L);
        assertThrows(
                IllegalArgumentException.class,
                () -> ChatImportParser.parse(ChatPortFixtures.zip(entries), small, true, null));
    }

    @Test
    void tooManyZipEntriesAreRefused() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            entries.put("f" + i + ".txt", bytes("x"));
        }
        Limits few = limits(5000, 2000, 50_000, 20_000, 3, 1 << 28);
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ChatImportParser.parse(
                                        ChatPortFixtures.zip(entries), few, true, null));
        assertTrue(e.getMessage().contains("too many files"));
    }

    @Test
    void aTruncatedZipIsReportedAsUnreadableNotAsACrash() {
        byte[] zip = ChatPortFixtures.zipOf("conversations.json", ChatPortFixtures.CHATGPT_ONE);
        byte[] cut = java.util.Arrays.copyOf(zip, zip.length / 2);
        // either "no conversations" (cut before the entry) or an IllegalArgumentException - never
        // another exception type
        try {
            ParseResult r = ChatImportParser.parse(cut, DEFAULTS, true, null);
            assertTrue(r.chats().isEmpty() || r.chats().size() == 1);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }
}
