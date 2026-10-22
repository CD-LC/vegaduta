package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatExportRenderer.Conversation;
import com.agenticai.core.agents.chatport.ChatExportRenderer.Format;
import com.agenticai.core.agents.chatport.ChatExportRenderer.Message;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ChatExportRendererTest {

    private static final Instant T0 =
            Instant.ofEpochSecond(1_700_000_000L); // 2023-11-14 22:13:20 UTC
    private static final Instant EXPORTED = Instant.parse("2026-10-04T08:00:00Z");

    private static Conversation sample() {
        return new Conversation(
                "conv-1",
                "chatgpt",
                "Trip planning",
                null,
                T0,
                Instant.parse("2026-10-01T00:00:00Z"),
                List.of(
                        new Message(
                                "user",
                                "Plan a trip to Kyoto.",
                                T0,
                                List.of("map.png", "notes.pdf")),
                        new Message(
                                "assistant",
                                "Day 1: Fushimi Inari.\n\n```\ncode\n```",
                                T0.plusSeconds(60),
                                List.of())),
                false,
                List.of("Older turns may be missing."));
    }

    @Test
    void markdownHasHeadingsTimestampsAttachmentNamesAndNotes() {
        String md = ChatExportRenderer.render(Format.MARKDOWN, List.of(sample()), EXPORTED);

        assertTrue(md.startsWith("# Trip planning\n\n"), md);
        assertTrue(
                md.contains(
                        "*Source: ChatGPT · Started 2023-11-14 22:13 UTC · Imported 2026-10-01 00:00 UTC · Exported 2026-10-04 08:00 UTC*"),
                md);
        assertTrue(md.contains("> Older turns may be missing."), md);
        assertTrue(
                md.contains(
                        "### You · 2023-11-14 22:13 UTC\n\nPlan a trip to Kyoto.\n\n> Attachments: map.png, notes.pdf\n\n"),
                md);
        assertTrue(
                md.contains("### Assistant · 2023-11-14 22:14 UTC\n\nDay 1: Fushimi Inari."), md);
        assertTrue(md.indexOf("### You") < md.indexOf("### Assistant"));
    }

    @Test
    void plainTextIsReadableWithoutMarkdown() {
        String txt = ChatExportRenderer.render(Format.TEXT, List.of(sample()), EXPORTED);
        assertTrue(txt.startsWith("Trip planning\n=============\n"), txt);
        assertTrue(
                txt.contains(
                        "You [2023-11-14 22:13 UTC]:\nPlan a trip to Kyoto.\n(Attachments: map.png, notes.pdf)\n"),
                txt);
        assertTrue(txt.contains("Assistant [2023-11-14 22:14 UTC]:\n"), txt);
        assertFalse(txt.contains("###"));
    }

    @Test
    void jsonIsVersionedAndCarriesEveryField() throws Exception {
        String json = ChatExportRenderer.render(Format.JSON, List.of(sample()), EXPORTED);
        JsonNode root = com.agenticai.core.json.JsonMappers.plain().readTree(json);

        assertEquals("vegaduta.chat-export", root.get("schema").asString());
        assertEquals(1, root.get("version").asInt());
        assertEquals("2026-10-04T08:00:00Z", root.get("exportedAt").asString());
        JsonNode c = root.get("conversations").get(0);
        assertEquals("conv-1", c.get("id").asString());
        assertEquals("chatgpt", c.get("source").asString());
        assertEquals("Trip planning", c.get("title").asString());
        assertEquals("2023-11-14T22:13:20Z", c.get("createdAt").asString());
        assertEquals("Older turns may be missing.", c.get("notes").get(0).asString());
        JsonNode m0 = c.get("messages").get(0);
        assertEquals("user", m0.get("role").asString());
        assertEquals("Plan a trip to Kyoto.", m0.get("content").asString());
        assertEquals("notes.pdf", m0.get("attachments").get(1).asString());
        assertEquals(2, c.get("messages").size());
        assertTrue(c.get("messages").get(1).get("attachments").isEmpty());
    }

    @Test
    void severalConversationsAreSeparatedAndKeptInOrder() {
        Conversation second =
                new Conversation(
                        "c2",
                        "claude",
                        "Second",
                        "Helper",
                        null,
                        null,
                        List.of(new Message("user", "hi", null, List.of())),
                        true,
                        List.of());
        String md = ChatExportRenderer.render(Format.MARKDOWN, List.of(sample(), second), EXPORTED);
        assertTrue(md.indexOf("# Trip planning") < md.indexOf("# Second"));
        assertTrue(md.contains("\n---\n\n# Second"));
        assertTrue(md.contains("Agent: Helper"));
        assertTrue(md.contains("shortened on import"));
        // a message without a timestamp has a bare heading
        assertTrue(md.contains("### You\n\nhi"));
    }

    @Test
    void emptyAndNullInputsRenderWithoutFailing() {
        assertEquals("", ChatExportRenderer.render(Format.MARKDOWN, List.of(), EXPORTED));
        assertEquals("", ChatExportRenderer.render(Format.TEXT, null, EXPORTED));
        String json = ChatExportRenderer.render(Format.JSON, List.of(), EXPORTED);
        assertTrue(json.contains("\"conversations\" : [ ]"), json);
    }

    @Test
    void aMultiLineTitleCannotInjectHeadings() {
        Conversation c =
                new Conversation(
                        "x",
                        "claude",
                        "Line one\n## Injected",
                        null,
                        null,
                        null,
                        List.of(),
                        false,
                        List.of());
        String md = ChatExportRenderer.render(Format.MARKDOWN, List.of(c), EXPORTED);
        assertTrue(md.startsWith("# Line one ## Injected\n"), md);
    }

    @Test
    void formatsParseAndRejectUnknownOnes() {
        assertEquals(Format.MARKDOWN, Format.parse(null));
        assertEquals(Format.MARKDOWN, Format.parse(" "));
        assertEquals(Format.MARKDOWN, Format.parse("MD"));
        assertEquals(Format.JSON, Format.parse("json"));
        assertEquals(Format.TEXT, Format.parse("txt"));
        assertThrows(IllegalArgumentException.class, () -> Format.parse("pdf"));
    }

    @Test
    void attachmentNotesAreSplitOffUserTurns() {
        ChatExportRenderer.SplitMessage s =
                ChatExportRenderer.splitAttachmentNotes(
                        "Look at this\n[attached: report.pdf (application/pdf)]\n[attached: chart.png (image/png)]");
        assertEquals("Look at this", s.content());
        assertEquals(List.of("report.pdf", "chart.png"), s.attachments());

        ChatExportRenderer.SplitMessage bare =
                ChatExportRenderer.splitAttachmentNotes("[attached: image/png]");
        assertEquals("", bare.content());
        assertEquals(List.of("image/png"), bare.attachments());

        ChatExportRenderer.SplitMessage none =
                ChatExportRenderer.splitAttachmentNotes("no notes here [attached: inline] text");
        assertEquals("no notes here [attached: inline] text", none.content());
        assertTrue(none.attachments().isEmpty());
        assertEquals("", ChatExportRenderer.splitAttachmentNotes(null).content());
    }

    @Test
    void filenamesAreSafeAndDated() {
        assertEquals(
                "Trip-planning-20261004.md",
                ChatExportRenderer.filename("Trip planning", EXPORTED, Format.MARKDOWN));
        assertEquals(
                "rm--rf-20261004.json",
                ChatExportRenderer.filename("../..\"; rm -rf", EXPORTED, Format.JSON));
        assertEquals("chat-20261004.txt", ChatExportRenderer.filename(null, EXPORTED, Format.TEXT));
        String longName = ChatExportRenderer.filename("a".repeat(200), EXPORTED, Format.MARKDOWN);
        assertTrue(longName.length() <= 60 + "-20261004.md".length());
        assertFalse(
                ChatExportRenderer.filename("a/b\\c:d", EXPORTED, Format.MARKDOWN)
                        .matches(".*[/\\\\:\"].*"));
    }
}
