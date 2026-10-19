package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.util.Collections;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The one binary-format generator this suite covers on purpose: {@link
 * PdfDocumentGenerator} uses only PDFBox's embedded Standard-14 (AFM) font
 * metrics - no system font lookup, no AWT - so it is safe in a headless,
 * resource-free test JVM, unlike the POI generators whose autoSizeColumn
 * path depends on platform font metrics (see the module test-coverage report
 * for why XLSX/DOCX/PPTX were deliberately left out). Output is verified by
 * round-tripping through {@link PDFTextStripper}, not by matching bytes -
 * PDF bytes are timestamp/ID-nondeterministic.
 */
class PdfDocumentGeneratorTest {

    private final PdfDocumentGenerator generator = new PdfDocumentGenerator();

    @Test
    void supportsPdf() {
        assertEquals(DocumentType.PDF, generator.supportedType());
    }

    @Test
    void titleSectionsAndTableAllSurviveTheRoundTrip() throws Exception {
        DocumentContent content = new DocumentContent(
            "Quarterly Report",
            List.of(new DocumentContent.Section(
                "Summary",
                List.of("Revenue grew steadily."),
                List.of("expand to two new cities"))),
            new DocumentContent.Table(List.of("Region", "Total"), List.of(List.of("South", "120"))),
            null, null);

        byte[] bytes = generator.generate(request(content));
        String text = extractText(bytes);

        assertTrue(text.contains("Quarterly Report"), "title missing from rendered PDF");
        assertTrue(text.contains("Summary"), "section heading missing");
        assertTrue(text.contains("Revenue grew steadily."), "paragraph missing");
        assertTrue(text.contains("- expand to two new cities"), "bullet prefix/text missing");
        assertTrue(text.contains("Region"), "table header missing");
        assertTrue(text.contains("South"), "table cell missing");
    }

    @Test
    void rawTextIsRenderedWhenThereAreNoSections() throws Exception {
        DocumentContent content = new DocumentContent(null, List.of(), null, "plain body text", null);

        String text = extractText(generator.generate(request(content)));

        assertTrue(text.contains("plain body text"));
    }

    @Test
    void longContentPaginatesInsteadOfRunningOffThePage() throws Exception {
        // 200 paragraphs cannot fit one LETTER page; if ensureSpace() regressed
        // the renderer would either throw or draw everything below the margin
        // on a single page.
        List<String> paragraphs = Collections.nCopies(200, "A reasonably long paragraph of filler text for pagination.");
        DocumentContent content = new DocumentContent("Long Doc",
            List.of(new DocumentContent.Section(null, paragraphs, List.of())), null, null, null);

        byte[] bytes = generator.generate(request(content));

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            assertTrue(doc.getNumberOfPages() > 1, "expected multi-page output, got " + doc.getNumberOfPages());
        }
    }

    @Test
    void charactersOutsideWinAnsiAreSanitizedNotFatal() throws Exception {
        // The sanitize() contract: WinAnsiEncoding would throw on U+20B9 etc.;
        // the generator must degrade to '?' rather than fail the whole job.
        DocumentContent content = new DocumentContent(null, List.of(), null, "price ₹500 emoji 😀 end", null);

        String text = extractText(generator.generate(request(content)));

        assertTrue(text.contains("price ?500"), "non-WinAnsi char should degrade to '?'");
        assertTrue(text.contains("end"), "text after the sanitized char must survive");
    }

    private DocumentGenerationRequest request(DocumentContent content) {
        return new DocumentGenerationRequest(DocumentType.PDF, "test", content);
    }

    private String extractText(byte[] bytes) throws Exception {
        assertTrue(bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F',
            "output does not start with a %PDF header");
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
