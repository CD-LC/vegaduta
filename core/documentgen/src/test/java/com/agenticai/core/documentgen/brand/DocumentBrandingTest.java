package com.agenticai.core.documentgen.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.documentgen.generator.impl.DocxDocumentGenerator;
import com.agenticai.core.documentgen.generator.impl.PdfDocumentGenerator;
import com.agenticai.core.documentgen.generator.impl.PptxDocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.junit.jupiter.api.Test;

/** Generated documents carry the tenant brand when set, VegaDuta's otherwise (2026-10-09). */
class DocumentBrandingTest {

    private static DocumentGenerationRequest request(DocumentType type) {
        DocumentContent content = new DocumentContent(
            "Quarterly report",
            List.of(new DocumentContent.Section("Summary", List.of("Revenue grew."), List.of("Point A"))),
            null, null, null);
        return new DocumentGenerationRequest(type, "report", content);
    }

    private static final DocumentBrand TENANT_NAME_ONLY = new DocumentBrand("Acme Corp", null, true, false);

    private static int pdfImageCount(PDPage page) {
        int n = 0;
        for (var name : page.getResources().getXObjectNames()) {
            n++;
        }
        return n;
    }

    @Test
    void platformBrandHasTheVegaDutaWordmarkBundled() {
        DocumentBrand platform = DocumentBrandResolver.platform();
        assertTrue(platform.platformDefault());
        assertTrue(platform.hasLogo(), "vegaduta-wordmark.png must be on the classpath");
    }

    @Test
    void pdfCarriesTheWordmarkOnEveryPageByDefault() throws Exception {
        byte[] pdf = new PdfDocumentGenerator().generate(request(DocumentType.PDF));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                assertEquals(1, pdfImageCount(page), "brand logo on every page");
            }
        }
    }

    @Test
    void pdfShowsTheTenantNameWhenTheTenantSetOne() throws Exception {
        byte[] pdf = new PdfDocumentGenerator().generate(request(DocumentType.PDF), TENANT_NAME_ONLY);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Acme Corp"), text);
            assertTrue(text.contains("Quarterly report"));
            assertEquals(0, pdfImageCount(doc.getPage(0)), "no VegaDuta logo on a tenant-branded document");
        }
    }

    @Test
    void docxHeaderCarriesTheBrand() throws Exception {
        byte[] docx = new DocxDocumentGenerator().generate(request(DocumentType.DOCX));
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<XWPFHeader> headers = doc.getHeaderList();
            assertFalse(headers.isEmpty());
            assertFalse(headers.get(0).getAllPictures().isEmpty(), "VegaDuta wordmark in the page header");
        }
        byte[] tenant = new DocxDocumentGenerator().generate(request(DocumentType.DOCX), TENANT_NAME_ONLY);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(tenant))) {
            assertTrue(doc.getHeaderList().get(0).getText().contains("Acme Corp"));
        }
    }

    @Test
    void pptxStampsTheBrandOnEverySlide() throws Exception {
        byte[] pptx = new PptxDocumentGenerator(
                        new com.agenticai.core.documentgen.generator.DocumentImageFetcher(
                                com.agenticai.core.security.net.SsrfGuard.strict()))
                .generate(request(DocumentType.PPTX));
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertFalse(ppt.getSlides().isEmpty());
            for (XSLFSlide slide : ppt.getSlides()) {
                assertTrue(slide.getShapes().stream().anyMatch(s -> s instanceof XSLFPictureShape), "logo on every slide");
            }
        }
    }

    @Test
    void pdfSafeFoldsTheMacronSoStandardFontsCanDrawIt() throws Exception {
        var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
        assertEquals("VegaDuta", PdfBrandHeader.pdfSafe(font, "VegaDūta"));
        assertNotNull(PdfBrandHeader.pdfSafe(font, "日本"));
    }
}
