package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdDocumentGeneratorTest {

    private final MdDocumentGenerator generator = new MdDocumentGenerator();

    @Test
    void supportsMd() {
        assertEquals(DocumentType.MD, generator.supportedType());
    }

    @Test
    void rawTextIsEmittedVerbatim() {
        DocumentContent content =
                new DocumentContent("T", List.of(), null, "# already markdown\n", null);
        assertEquals("# already markdown\n", generate(content));
    }

    @Test
    void sectionsAndTableRenderAsGithubMarkdown() {
        DocumentContent content =
                new DocumentContent(
                        "Quarterly report",
                        List.of(
                                new DocumentContent.Section(
                                        "Summary",
                                        List.of("Revenue grew.", "Costs flat."),
                                        List.of("win a", "win b")),
                                new DocumentContent.Section(null, List.of("Tail."), List.of())),
                        new DocumentContent.Table(
                                List.of("Region", "Revenue | note"),
                                List.of(
                                        List.of("North", "1,200"),
                                        List.of("South\nEast", "900"),
                                        List.of("West"))),
                        null,
                        null);

        assertEquals(
                """
                # Quarterly report

                ## Summary

                Revenue grew.

                Costs flat.

                - win a
                - win b

                Tail.

                | Region | Revenue \\| note |
                | --- | --- |
                | North | 1,200 |
                | South East | 900 |
                | West |  |
                """,
                generate(content));
    }

    @Test
    void titleOnlyBecomesAHeading() {
        assertEquals(
                "# Just a title\n",
                generate(new DocumentContent("Just a title", null, null, null, null)));
    }

    private String generate(DocumentContent content) {
        return new String(
                generator.generate(new DocumentGenerationRequest(DocumentType.MD, "f", content)),
                StandardCharsets.UTF_8);
    }
}
