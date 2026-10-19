package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentGenerationRequest} into a {@code .docx} file
 * using Apache POI's {@link XWPFDocument}. Title becomes a Heading1
 * paragraph, each {@link DocumentContent.Section} a Heading2 + paragraphs +
 * a simple "&#8226; "-prefixed bullet list, {@link DocumentContent#table()}
 * (if present) an {@link XWPFTable} with a bold header row, and - when
 * {@link DocumentContent#sections()} is empty - {@link
 * DocumentContent#rawText()} is written as plain paragraphs (one per line).
 */
@Component
public class DocxDocumentGenerator implements DocumentGenerator {

    @Override
    public DocumentType supportedType() {
        return DocumentType.DOCX;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        return generate(request, DocumentBrandResolver.platform());
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request, DocumentBrand brand) throws Exception {
        DocumentContent content = request.content();

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            writeBrandHeader(document, brand);

            if (content.title() != null && !content.title().isBlank()) {
                writeHeading(document, content.title(), "Heading1", 28);
            }

            List<DocumentContent.Section> sections = content.sections();
            if (sections != null && !sections.isEmpty()) {
                for (DocumentContent.Section section : sections) {
                    writeSection(document, section);
                }
            } else if (content.rawText() != null && !content.rawText().isBlank()) {
                writeRawText(document, content.rawText());
            }

            if (content.table() != null) {
                writeTable(document, content.table());
            }

            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new Exception("Could not generate the Word document: " + e.getMessage(), e);
        }
    }

    /** Page header on every page: the brand logo, plus the tenant name when it has one. Best-effort. */
    private void writeBrandHeader(XWPFDocument document, DocumentBrand brand) {
        if (brand == null || (!brand.hasLogo() && !brand.hasName())) {
            return;
        }
        try {
            XWPFHeader header = document.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph paragraph = header.createParagraph();
            XWPFRun run = paragraph.createRun();
            if (brand.hasLogo()) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(brand.logo()));
                double heightPt = 20;
                double widthPt = img == null ? 75 : Math.min(130, img.getWidth() * heightPt / Math.max(1, img.getHeight()));
                run.addPicture(new ByteArrayInputStream(brand.logo()),
                    brand.logoIsPng() ? Document.PICTURE_TYPE_PNG : Document.PICTURE_TYPE_JPEG,
                    brand.logoIsPng() ? "brand-logo.png" : "brand-logo.jpg",
                    Units.toEMU(widthPt), Units.toEMU(heightPt));
            }
            if (brand.hasName()) {
                XWPFRun name = paragraph.createRun();
                name.setText((brand.hasLogo() ? "  " : "") + brand.name());
                name.setBold(true);
                name.setFontSize(11);
            }
        } catch (Exception e) {
            // A broken logo must never fail the document - it just ships without a header.
        }
    }

    private void writeHeading(XWPFDocument document, String text, String style, int fontSize) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(fontSize);
    }

    private void writeSection(XWPFDocument document, DocumentContent.Section section) {
        if (section.heading() != null && !section.heading().isBlank()) {
            writeHeading(document, section.heading(), "Heading2", 18);
        }

        if (section.paragraphs() != null) {
            for (String paragraphText : section.paragraphs()) {
                if (paragraphText == null || paragraphText.isBlank()) {
                    continue;
                }
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(paragraphText);
            }
        }

        if (section.bulletPoints() != null) {
            for (String bullet : section.bulletPoints()) {
                if (bullet == null || bullet.isBlank()) {
                    continue;
                }
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setIndentationLeft(360);
                XWPFRun run = paragraph.createRun();
                run.setText("• " + bullet);
            }
        }
    }

    private void writeRawText(XWPFDocument document, String rawText) {
        for (String line : rawText.split("\\R", -1)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(line);
        }
    }

    private void writeTable(XWPFDocument document, DocumentContent.Table table) {
        List<String> headers = table.headers();
        List<List<String>> rows = table.rows();
        if (headers == null || headers.isEmpty()) {
            return;
        }

        XWPFTable xwpfTable = document.createTable(1, headers.size());
        xwpfTable.getRow(0).getCtRow(); // ensure header row exists before styling

        XWPFTableRow headerRow = xwpfTable.getRow(0);
        for (int col = 0; col < headers.size(); col++) {
            XWPFTableCell cell = headerRow.getCell(col);
            cell.removeParagraph(0);
            XWPFParagraph paragraph = cell.addParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(headers.get(col));
            run.setBold(true);
        }

        if (rows != null) {
            for (List<String> row : rows) {
                XWPFTableRow dataRow = xwpfTable.createRow();
                for (int col = 0; col < headers.size(); col++) {
                    String value = (row != null && col < row.size()) ? row.get(col) : "";
                    XWPFTableCell cell = dataRow.getCell(col);
                    cell.removeParagraph(0);
                    XWPFParagraph paragraph = cell.addParagraph();
                    XWPFRun run = paragraph.createRun();
                    run.setText(value == null ? "" : value);
                }
            }
        }
    }
}
