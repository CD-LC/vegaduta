package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowDocumentText.DocumentExtractionException;
import com.agenticai.core.workflow.WorkflowDocumentText.Format;
import com.agenticai.core.workflow.WorkflowDocumentText.Limits;
import com.agenticai.core.workflow.WorkflowDocumentText.Result;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WorkflowDocumentTextTest {

    private static final Limits LIMITS = new Limits(10_000, 50, 100);

    static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static byte[] xlsx(int rows, int sheets) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (int s = 0; s < sheets; s++) {
                Sheet sheet = workbook.createSheet("Sheet" + (s + 1));
                for (int r = 0; r < rows; r++) {
                    Row row = sheet.createRow(r);
                    row.createCell(0).setCellValue("name" + r);
                    row.createCell(1).setCellValue(r * 10);
                    if (r == 0) {
                        row.createCell(3).setCellValue("far right");
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void formatDetectionPrefersTheExtensionThenContentTypeThenMagicBytes() throws Exception {
        assertEquals(Format.PDF, WorkflowDocumentText.detect("Report.PDF", null, new byte[] {1}));
        assertEquals(Format.CSV, WorkflowDocumentText.detect("data.tsv", null, null));
        assertEquals(Format.HTML, WorkflowDocumentText.detect("https://x.example/page.html?id=3", null, null));
        assertEquals(Format.XLSX, WorkflowDocumentText.detect("", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null));
        assertEquals(Format.PDF, WorkflowDocumentText.detect("", null, pdfWithPages(1)));
        assertEquals(Format.XLSX, WorkflowDocumentText.detect("", null, xlsx(2, 1)));
        assertEquals(Format.TXT, WorkflowDocumentText.detect("", null, "plain words".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Format.HTML, WorkflowDocumentText.detect("", null, "  <!DOCTYPE html><p>x".getBytes(StandardCharsets.UTF_8)));
        assertNull(WorkflowDocumentText.detect("", null, new byte[] {0, 1, 2, 3}));
        assertNull(WorkflowDocumentText.detect("archive.zip", null, null));
    }

    @Test
    void parseFormatAcceptsAutoAndRejectsUnknownNames() {
        assertNull(WorkflowDocumentText.parseFormat(null));
        assertNull(WorkflowDocumentText.parseFormat(" Auto "));
        assertEquals(Format.DOCX, WorkflowDocumentText.parseFormat("DOCX"));
        assertThrows(DocumentExtractionException.class, () -> WorkflowDocumentText.parseFormat("rtf"));
    }

    @Test
    void plainTextIsDecodedWithoutAnExtractorAndBomIsDropped() {
        Result r = WorkflowDocumentText.extract("\uFEFFhello world".getBytes(StandardCharsets.UTF_8), Format.TXT, "a.txt", null, LIMITS);
        assertEquals("hello world", r.text());
        assertEquals(-1, r.pages());
        assertEquals(false, r.truncated());
    }

    @Test
    void emptyAndBlankDocumentsAreRefused() {
        assertThrows(DocumentExtractionException.class,
            () -> WorkflowDocumentText.extract(new byte[0], Format.TXT, "a.txt", null, LIMITS));
        assertThrows(DocumentExtractionException.class,
            () -> WorkflowDocumentText.extract("   \n ".getBytes(StandardCharsets.UTF_8), Format.TXT, "a.txt", null, LIMITS));
    }

    @Test
    void characterCapTruncatesWithAMarker() {
        Result r = WorkflowDocumentText.extract("abcdefghij".repeat(100).getBytes(StandardCharsets.UTF_8), Format.MD, "a.md", null,
            new Limits(100, 50, 100));
        assertEquals(100 + WorkflowDocumentText.TRUNCATION_MARKER.length(), r.text().length());
        assertTrue(r.truncated());
    }

    @Test
    void csvRowCap() {
        String csv = "a,b\n1,2\n3,4\n5,6\n";
        Result r = WorkflowDocumentText.extract(csv.getBytes(StandardCharsets.UTF_8), Format.CSV, "a.csv", null, new Limits(10_000, 50, 2));
        assertEquals("a,b\n1,2", r.text());
        assertTrue(r.truncated());
        Result full = WorkflowDocumentText.extract(csv.getBytes(StandardCharsets.UTF_8), Format.CSV, "a.csv", null, new Limits(10_000, 50, 100));
        assertEquals(false, full.truncated());
    }

    @Test
    void pdfWithinThePageCapIsSentUntouchedAndOverTheCapIsCutBeforeExtraction() throws Exception {
        byte[] small = pdfWithPages(3);
        int[] total = new int[1];
        assertSame(small, WorkflowDocumentText.capPdfPages(small, 5, total));
        assertEquals(3, total[0]);

        byte[] big = pdfWithPages(8);
        List<byte[]> sent = new ArrayList<>();
        Result r = WorkflowDocumentText.extract(big, Format.PDF, "big.pdf", (name, bytes) -> {
            sent.add(bytes);
            return "extracted from " + name;
        }, new Limits(10_000, 2, 100));
        assertEquals("extracted from big.pdf", r.text());
        assertEquals(8, r.pages());
        assertTrue(r.truncated());
        assertEquals(2, pageCount(sent.get(0)));
    }

    @Test
    void corruptPdfAndMissingExtractorAreReportedNotThrownRaw() {
        assertThrows(DocumentExtractionException.class, () -> WorkflowDocumentText.capPdfPages("not a pdf".getBytes(StandardCharsets.UTF_8), 5, new int[1]));
        DocumentExtractionException e = assertThrows(DocumentExtractionException.class,
            () -> WorkflowDocumentText.extract("%PDF-1.4".getBytes(StandardCharsets.UTF_8), Format.PDF, "a.pdf", null, LIMITS));
        assertTrue(e.getMessage().contains("not available"), e.getMessage());
        assertThrows(DocumentExtractionException.class,
            () -> WorkflowDocumentText.extract("<p>x</p>".getBytes(StandardCharsets.UTF_8), Format.HTML, "a.html", null, LIMITS));
    }

    @Test
    void docxAndHtmlGoThroughTheExtractorWithSafeFileNames() {
        List<String> names = new ArrayList<>();
        WorkflowDocumentText.extract("x".getBytes(StandardCharsets.UTF_8), Format.DOCX, "../../etc/My Resume.docx", (n, b) -> {
            names.add(n);
            return "text";
        }, LIMITS);
        WorkflowDocumentText.extract("x".getBytes(StandardCharsets.UTF_8), Format.HTML, "weird name without extension", (n, b) -> {
            names.add(n);
            return "text";
        }, LIMITS);
        assertEquals(List.of("My_Resume.docx", "document.html"), names);
    }

    @Test
    void xlsxReadsEverySheetWithRowAndSheetCapsAndSkipsEmptyTrailingCells() throws Exception {
        Result r = WorkflowDocumentText.extract(xlsx(3, 2), Format.XLSX, "a.xlsx", null, LIMITS);
        assertTrue(r.text().contains("## Sheet: Sheet1"), r.text());
        assertTrue(r.text().contains("## Sheet: Sheet2"), r.text());
        assertTrue(r.text().contains("name1\t10"), r.text());
        assertTrue(r.text().contains("name0\t0\t\tfar right"), r.text());
        assertEquals(false, r.truncated());

        Result capped = WorkflowDocumentText.extract(xlsx(10, 1), Format.XLSX, "a.xlsx", null, new Limits(10_000, 50, 4));
        assertTrue(capped.truncated());
        assertEquals(4, capped.text().lines().filter(l -> l.startsWith("name")).count());

        Result manySheets = WorkflowDocumentText.extract(xlsx(1, WorkflowDocumentText.MAX_SHEETS + 2), Format.XLSX, "a.xlsx", null, LIMITS);
        assertTrue(manySheets.truncated());
        assertTrue(!manySheets.text().contains("Sheet" + (WorkflowDocumentText.MAX_SHEETS + 1)));
    }

    @Test
    void corruptSpreadsheetIsReported() {
        assertThrows(DocumentExtractionException.class, () -> WorkflowDocumentText.xlsxText(
            "definitely not a workbook".getBytes(StandardCharsets.UTF_8), 10, 1000, new boolean[1]));
    }
}
