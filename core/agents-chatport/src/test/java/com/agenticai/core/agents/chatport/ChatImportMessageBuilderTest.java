package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatImportMessageBuilder} enforces the per-chat caps every format shares: a message longer
 * than {@code maxCharsPerMessage} is cut and marked, the chat's character room can cut it further,
 * the count cap refuses more messages, and {@code firstUserText} is the first non-blank user body
 * (needed for title fallback).
 */
class ChatImportMessageBuilderTest {

    private static Limits limits(int maxMessages, int perMessage, int perChat) {
        return new Limits(5000, maxMessages, perMessage, perChat, 20_000_000L, 20_000, 10_000,
            64L << 20, 100);
    }

    @Test
    void messagesAccumulateWithCountsCharsAndFirstUserText() {
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(10, 100, 1000), true);
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");

        assertTrue(b.add("assistant", "hi", ts, null));
        assertTrue(b.add("user", "hello there", ts, List.of("f.txt")));
        assertTrue(b.add("user", "second user turn", null, List.of()));

        ParsedChat chat = b.build("k", "claude", "id-1", "Title", ts, null, false);
        assertEquals(3, chat.messageCount());
        assertEquals("hi".length() + "hello there".length() + "second user turn".length(),
            chat.chars());
        assertEquals("hello there", chat.firstUserText());
        assertEquals(List.of("f.txt"), chat.messages().get(1).attachments());
        assertEquals(List.of(), chat.messages().get(0).attachments());
        assertFalse(chat.truncated());
    }

    @Test
    void anOverlongMessageIsCutAtTheCapAndMarked() {
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(10, 5, 1000), true);

        b.add("user", "0123456789ABCDEF", null, List.of());

        ParsedChat chat = b.build("k", "claude", null, "t", null, null, false);
        String body = chat.messages().get(0).content();
        assertTrue(body.startsWith("01234"));
        assertTrue(body.endsWith(ChatImportParser.TRUNCATED_MARK));
        assertTrue(chat.truncated());
    }

    @Test
    void theChatCharRoomCutsAMessageFurtherThanThePerMessageCap() {
        // Per-message cap 50, but only 6 chars of room left in the chat.
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(10, 50, 9), true);
        b.add("user", "abc", null, List.of());

        b.add("assistant", "0123456789", null, List.of());

        ParsedChat chat = b.build("k", "claude", null, "t", null, null, false);
        assertTrue(chat.truncated());
        String second = chat.messages().get(1).content();
        assertTrue(second.startsWith("012345"));
        assertTrue(second.endsWith("[truncated]"));
    }

    @Test
    void theMessageCountCapRefusesFurtherAdds() {
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(2, 100, 1000), true);
        assertTrue(b.add("user", "one", null, List.of()));
        assertTrue(b.add("assistant", "two", null, List.of()));

        assertFalse(b.add("user", "three", null, List.of()));

        ParsedChat chat = b.build("k", "claude", null, "t", null, null, false);
        assertEquals(2, chat.messageCount());
        assertTrue(chat.truncated());
    }

    @Test
    void previewModeCountsButKeepsNoMessagesAndABlankFirstUserDoesNotWin() {
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(10, 100, 1000), false);
        b.add("user", "   ", null, List.of()); // blank: firstUserText must not be this
        b.add("user", "real question", null, List.of());

        ParsedChat chat = b.build("k", "claude", null, "t", null, null, false);
        assertEquals(2, chat.messageCount());
        assertTrue(chat.messages().isEmpty());
        assertEquals("real question", chat.firstUserText());
        assertTrue(chat.chars() > 0);
    }

    @Test
    void buildHonoursTheExtraTruncatedFlagAndEmptyBuilderIsEmpty() {
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits(10, 100, 1000), true);
        assertTrue(b.isEmpty());

        b.add("user", "x", null, List.of());
        ParsedChat chat = b.build("k", "claude", null, "t", null, null, true);
        assertTrue(chat.truncated(), "extraTruncated marks even a cap-free chat");
        assertNull(chat.updatedAt());
    }
}
