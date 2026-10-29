package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatImportFields} is where every untrusted field is read or cleaned: ids and titles are
 * one-lined and capped, timestamps outside any plausible range are dropped, attachment names are
 * sanitised and counted. The caps come from {@link ChatImportParser}'s constants, so the tests pin
 * the behaviour, not the numbers.
 */
class ChatImportFieldsTest {

    @Test
    void textReturnsOnlyTextualValues() throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"s\": \"v\", \"n\": 1, \"o\": {}, \"z\": null}");
        assertEquals("v", ChatImportFields.text(node, "s"));
        assertNull(ChatImportFields.text(node, "n"));
        assertNull(ChatImportFields.text(node, "o"));
        assertNull(ChatImportFields.text(node, "z"));
        assertNull(ChatImportFields.text(node, "missing"));
        assertNull(ChatImportFields.text(null, "s"));
        assertNull(ChatImportFields.text(NullNode.getInstance(), "s"));
    }

    @Test
    void cleanIdPrefersThePrimaryFallsBackAndRejectsEmpties() {
        assertEquals("primary", ChatImportFields.cleanId(" primary ", "fallback"));
        assertEquals("fallback", ChatImportFields.cleanId("  ", "fallback"));
        assertEquals("fallback", ChatImportFields.cleanId(null, "fallback"));
        assertNull(ChatImportFields.cleanId(null, null));
        assertNull(ChatImportFields.cleanId(" ", " "));
        // Newlines and control characters cannot survive an id.
        assertEquals("a b", ChatImportFields.cleanId("a\nb", null));
        assertEquals(ChatImportParser.MAX_ID_CHARS,
            ChatImportFields.cleanId("x".repeat(500), null).length());
    }

    @Test
    void cleanTitleFallsBackToFirstUserTextThenUntitled() {
        assertEquals("My chat", ChatImportFields.cleanTitle(" My\nchat ", "ignored"));
        assertEquals("first thing asked", ChatImportFields.cleanTitle(" ", "first thing asked"));
        assertEquals("Untitled chat", ChatImportFields.cleanTitle(null, null));
        assertEquals("Untitled chat", ChatImportFields.cleanTitle("", "  "));
        assertEquals(ChatImportParser.MAX_TITLE_CHARS,
            ChatImportFields.cleanTitle("t".repeat(500), null).length());
    }

    @Test
    void epochSecondsAcceptsOnlyPlausibleNumbers() {
        assertNull(ChatImportFields.epochSeconds(null));
        assertNull(ChatImportFields.epochSeconds(TextNode.valueOf("1700000000")));
        assertNull(ChatImportFields.epochSeconds(new DoubleNode(0)));
        assertNull(ChatImportFields.epochSeconds(new DoubleNode(-10)));
        assertNull(ChatImportFields.epochSeconds(new DoubleNode(Double.NaN)));
        assertNull(ChatImportFields.epochSeconds(new DoubleNode(9e15)));
        assertNull(ChatImportFields.epochSeconds(new DoubleNode(4_102_444_801d))); // past 2100
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L),
            ChatImportFields.epochSeconds(new DoubleNode(1_700_000_000d)));
        assertEquals(Instant.ofEpochMilli(1_000L),
            ChatImportFields.epochSeconds(new IntNode(1)));
    }

    @Test
    void parseIsoHandlesInstantsOffsetsAndGarbage() {
        assertNull(ChatImportFields.parseIso(null));
        assertNull(ChatImportFields.parseIso(" "));
        assertNull(ChatImportFields.parseIso("yesterday"));
        assertNull(ChatImportFields.parseIso("x".repeat(60)));
        assertEquals(Instant.parse("2024-03-01T10:00:00Z"),
            ChatImportFields.parseIso("2024-03-01T10:00:00Z"));
        // An offset (not Z) timestamp goes through the OffsetDateTime fallback.
        assertEquals(Instant.parse("2024-03-01T10:00:00Z"),
            ChatImportFields.parseIso("2024-03-01T11:00:00+01:00"));
    }

    @Test
    void addAttachmentSanitisesCapsAndDedupesNothing() {
        List<String> out = new ArrayList<>();
        ChatImportFields.addAttachment(out, null);
        ChatImportFields.addAttachment(out, "   ");
        assertTrue(out.isEmpty());

        ChatImportFields.addAttachment(out, " report\nfinal.pdf ");
        assertEquals(List.of("report final.pdf"), out);

        String longName = "n".repeat(ChatImportParser.MAX_ATTACHMENT_NAME_CHARS + 50);
        ChatImportFields.addAttachment(out, longName);
        assertEquals(ChatImportParser.MAX_ATTACHMENT_NAME_CHARS, out.get(1).length());

        for (int i = 0; i < ChatImportParser.MAX_ATTACHMENTS_PER_MESSAGE + 5; i++) {
            ChatImportFields.addAttachment(out, "f" + i);
        }
        assertEquals(
                ChatImportParser.MAX_ATTACHMENTS_PER_MESSAGE,
                out.size(),
                "the list cap is on the collected attachments, so the two earlier names plus the loop still stop at the cap");
    }
}
