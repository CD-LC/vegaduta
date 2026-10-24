package com.agenticai.core.documentgen.generator.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.Version;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * Apache POI stays at or above 5.4.0 (Dependabot alerts #5 and #6, CVE-2025-31672 and the other
 * advisories fixed in 5.4.0), and the XLSX and DOCX generators still write files POI itself reads
 * back, so a later POI bump that breaks either format fails here. The version is declared once as
 * {@code poi.version} in core/pom.xml and shared by documentgen and omnichannel.
 */
class PoiVersionAndRoundTripTest {

    @Test
    void poiIsAtLeastTheFixedRelease() {
        String[] parts = Version.getVersion().split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        assertTrue(
                major > 5 || (major == 5 && minor >= 4),
                "Apache POI " + Version.getVersion() + " is below 5.4.0 (CVE-2025-31672)");
    }

    @Test
    void xlsxTableSurvivesAWriteAndParseRoundTrip() throws Exception {
        DocumentContent content =
                new DocumentContent(
                        "Contacts",
                        List.of(),
                        new DocumentContent.Table(
                                List.of("Name", "Phone"),
                                List.of(
                                        List.of("Asha", "+911234567890"),
                                        List.of("Ravi", "+919876543210"))),
                        null,
                        null);
        byte[] xlsx =
                new XlsxDocumentGenerator()
                        .generate(
                                new DocumentGenerationRequest(
                                        DocumentType.XLSX, "contacts", content));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Name", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Phone", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Asha", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("+919876543210", sheet.getRow(2).getCell(1).getStringCellValue());
        }
    }

    @Test
    void docxTextSurvivesAWriteAndParseRoundTrip() throws Exception {
        DocumentContent content =
                new DocumentContent(
                        "Quarterly report",
                        List.of(
                                new DocumentContent.Section(
                                        "Summary", List.of("Revenue grew."), List.of("point one"))),
                        null,
                        null,
                        null);
        byte[] docx =
                new DocxDocumentGenerator()
                        .generate(
                                new DocumentGenerationRequest(
                                        DocumentType.DOCX, "report", content));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            assertTrue(text.contains("Quarterly report"), text);
            assertTrue(text.contains("Revenue grew."), text);
            assertTrue(text.contains("point one"), text);
        }
    }
}
