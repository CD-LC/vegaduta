package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TxtDocumentGenerator}'s value is its documented precedence chain
 * (rawText > sections > table > title > empty) and the exact flattened
 * layout - both are pure string transforms an agent's generated document
 * depends on verbatim, so the assertions here are exact full-output strings
 * rather than contains() spot checks.
 */
class TxtDocumentGeneratorTest {

    private final TxtDocumentGenerator generator = new TxtDocumentGenerator();

    @Test
    void supportsTxt() {
        assertEquals(DocumentType.TXT, generator.supportedType());
    }

    @Test
    void rawTextWinsOverEverythingElseAndIsEmittedVerbatim() throws Exception {
        DocumentContent content = new DocumentContent("Title", List.of(
            new DocumentContent.Section("H", List.of("p"), List.of("b"))
        ), new DocumentContent.Table(List.of("h"), List.of()), "the raw\ntext", null);

        assertEquals("the raw\ntext", generate(content));
    }

    @Test
    void sectionsRenderTitleBlankLineHeadingParagraphsThenDashedBullets() throws Exception {
        DocumentContent content = new DocumentContent("My Doc", List.of(
            new DocumentContent.Section("Intro", List.of("First para.", "Second para."), List.of("point a", "point b")),
            new DocumentContent.Section(null, List.of("Tail para."), List.of())
        ), null, null, null);

        assertEquals(
            "My Doc\n"
                + "\n"
                + "Intro\n"
                + "First para.\n"
                + "Second para.\n"
                + "- point a\n"
                + "- point b\n"
                + "\n"
                + "Tail para.\n",
            generate(content));
    }

    @Test
    void tableFallbackJoinsCellsWithTabs() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(),
            new DocumentContent.Table(List.of("name", "city"), List.of(
                List.of("Asha", "Pune"),
                Arrays.asList((String) null) // a null-padded short row must not NPE the join
            )), null, null);

        assertEquals("name\tcity\nAsha\tPune\nnull\n", generate(content));
    }

    @Test
    void titleOnlyContentStillProducesATitleLine() throws Exception {
        DocumentContent content = new DocumentContent("Just a title", List.of(), null, null, null);

        assertEquals("Just a title\n", generate(content));
    }

    @Test
    void emptyContentProducesZeroBytesNotAnException() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(), null, null, null);

        assertEquals("", generate(content));
    }

    private String generate(DocumentContent content) throws Exception {
        byte[] bytes = generator.generate(new DocumentGenerationRequest(DocumentType.TXT, "test", content));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
