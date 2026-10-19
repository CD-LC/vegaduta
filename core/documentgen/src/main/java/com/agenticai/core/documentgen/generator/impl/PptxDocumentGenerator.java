package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentContent} into a PPTX slide deck via Apache POI's
 * {@link XMLSlideShow}. Slide order: a title slide (built from {@link
 * DocumentContent#title()}, falling back to {@link
 * DocumentGenerationRequest#filename()} when absent), then one content slide
 * per {@link DocumentContent.Section} (heading as the slide title, paragraphs
 * then bullet points as body text), then - if {@link DocumentContent#table()}
 * is present - a final slide holding a simple {@link XSLFTable} built from
 * its headers and rows. When there are neither sections nor a table, {@link
 * DocumentContent#rawText()} is rendered as bullet lines on a single content
 * slide instead.
 */
@Component
public class PptxDocumentGenerator implements DocumentGenerator {

    private static final Rectangle2D TABLE_ANCHOR = new Rectangle2D.Double(50, 100, 620, 380);

    private final com.agenticai.core.documentgen.generator.DocumentImageFetcher imageFetcher;

    public PptxDocumentGenerator(com.agenticai.core.documentgen.generator.DocumentImageFetcher imageFetcher) {
        this.imageFetcher = imageFetcher;
    }

    @Override
    public DocumentType supportedType() {
        return DocumentType.PPTX;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        return generate(request, DocumentBrandResolver.platform());
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request, DocumentBrand brand) throws Exception {
        DocumentContent content = request.content();

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlideLayout titleLayout = resolveLayout(master, SlideLayout.TITLE);
            XSLFSlideLayout contentLayout = resolveLayout(master, SlideLayout.TITLE_AND_CONTENT);

            String title = (content.title() != null && !content.title().isBlank())
                ? content.title()
                : request.filename();
            addTitleSlide(ppt, titleLayout, title);

            List<DocumentContent.Section> sections = content.sections();
            DocumentContent.Table table = content.table();
            boolean hasSections = sections != null && !sections.isEmpty();
            boolean hasTable = table != null && table.headers() != null && !table.headers().isEmpty();

            if (hasSections) {
                for (DocumentContent.Section section : sections) {
                    addSectionSlide(ppt, contentLayout, section);
                }
            }
            if (hasTable) {
                addTableSlide(ppt, contentLayout, table);
            }
            if (!hasSections && !hasTable && content.rawText() != null && !content.rawText().isEmpty()) {
                addRawTextSlide(ppt, contentLayout, content.rawText());
            }

            stampBrand(ppt, brand);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    /** Brand logo (and tenant name) in the top-right corner of every slide. Best-effort. */
    private void stampBrand(XMLSlideShow ppt, DocumentBrand brand) {
        if (brand == null || (!brand.hasLogo() && !brand.hasName())) {
            return;
        }
        try {
            java.awt.Dimension page = ppt.getPageSize();
            XSLFPictureData picture = null;
            double w = 0;
            double h = 24;
            if (brand.hasLogo()) {
                picture = ppt.addPicture(brand.logo(), brand.logoIsPng() ? PictureData.PictureType.PNG : PictureData.PictureType.JPEG);
                java.awt.Dimension px = picture.getImageDimension();
                w = Math.min(140, px.getWidth() * h / Math.max(1, px.getHeight()));
            }
            for (XSLFSlide slide : ppt.getSlides()) {
                double right = page.getWidth() - 18;
                if (picture != null) {
                    XSLFPictureShape shape = slide.createPicture(picture);
                    shape.setAnchor(new Rectangle2D.Double(right - w, 12, w, h));
                    right -= w + 8;
                }
                if (brand.hasName()) {
                    XSLFTextBox box = slide.createTextBox();
                    box.setAnchor(new Rectangle2D.Double(Math.max(18, right - 220), 12, 220, h));
                    XSLFTextParagraph para = box.addNewTextParagraph();
                    para.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT);
                    XSLFTextRun run = para.addNewTextRun();
                    run.setText(brand.name());
                    run.setFontSize(12.0);
                    run.setBold(true);
                }
            }
        } catch (Exception e) {
            // A broken logo must never fail the deck - it just ships unbranded.
        }
    }

    private XSLFSlideLayout resolveLayout(XSLFSlideMaster master, SlideLayout type) {
        XSLFSlideLayout layout = master.getLayout(type);
        if (layout != null) {
            return layout;
        }
        XSLFSlideLayout[] layouts = master.getSlideLayouts();
        return (layouts != null && layouts.length > 0) ? layouts[0] : null;
    }

    private void addTitleSlide(XMLSlideShow ppt, XSLFSlideLayout titleLayout, String title) {
        XSLFSlide slide = ppt.createSlide(titleLayout);
        XSLFTextShape titleShape = slide.getPlaceholder(0);
        if (titleShape != null) {
            titleShape.setText(title == null ? "" : title);
        }
    }

    private void addSectionSlide(XMLSlideShow ppt, XSLFSlideLayout contentLayout, DocumentContent.Section section) {
        XSLFSlide slide = ppt.createSlide(contentLayout);
        XSLFTextShape titleShape = slide.getPlaceholder(0);
        if (titleShape != null) {
            titleShape.setText(section.heading() != null ? section.heading() : "");
        }

        XSLFTextShape body = slide.getPlaceholder(1);
        if (body == null) {
            return;
        }
        body.clearText();

        List<String> paragraphs = section.paragraphs();
        if (paragraphs != null) {
            for (String paragraph : paragraphs) {
                addBodyLine(body, paragraph, false);
            }
        }
        List<String> bulletPoints = section.bulletPoints();
        if (bulletPoints != null) {
            for (String bullet : bulletPoints) {
                addBodyLine(body, bullet, true);
            }
        }
        addSectionImage(ppt, slide, section);
    }

    /** Best-effort: a section image fills the right half of the content area; a failed fetch just skips it. */
    private void addSectionImage(XMLSlideShow ppt, XSLFSlide slide, DocumentContent.Section section) {
        if (section.imageUrl() == null || section.imageUrl().isBlank()) {
            return;
        }
        try {
            com.agenticai.core.documentgen.generator.DocumentImageFetcher.Result result =
                    imageFetcher.fetch(section.imageUrl());
            if (!result.ok() || result.image() == null) {
                return;
            }
            XSLFPictureData picture =
                    ppt.addPicture(result.image().bytes(), result.image().type());
            java.awt.Dimension page = ppt.getPageSize();
            java.awt.Dimension px = picture.getImageDimension();
            double w = page.getWidth() * 0.42;
            double h = px.getHeight() * w / Math.max(1, px.getWidth());
            double maxH = page.getHeight() * 0.62;
            if (h > maxH) {
                h = maxH;
                w = px.getWidth() * h / Math.max(1, px.getHeight());
            }
            XSLFPictureShape shape = slide.createPicture(picture);
            shape.setAnchor(new Rectangle2D.Double(page.getWidth() - w - 30, (page.getHeight() - h) / 2, w, h));
        } catch (Exception ignored) {
            // A broken embed must never fail the deck.
        }
    }

    private void addRawTextSlide(XMLSlideShow ppt, XSLFSlideLayout contentLayout, String rawText) {
        XSLFSlide slide = ppt.createSlide(contentLayout);
        XSLFTextShape titleShape = slide.getPlaceholder(0);
        if (titleShape != null) {
            titleShape.setText("");
        }

        XSLFTextShape body = slide.getPlaceholder(1);
        if (body == null) {
            return;
        }
        body.clearText();

        String[] lines = rawText.split("\\r?\\n", -1);
        for (String line : lines) {
            if (!line.isEmpty()) {
                addBodyLine(body, line, true);
            }
        }
    }

    private void addBodyLine(XSLFTextShape body, String text, boolean bullet) {
        XSLFTextParagraph paragraph = body.addNewTextParagraph();
        paragraph.setBullet(bullet);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text == null ? "" : text);
    }

    private void addTableSlide(XMLSlideShow ppt, XSLFSlideLayout contentLayout, DocumentContent.Table table) {
        XSLFSlide slide = ppt.createSlide(contentLayout);
        XSLFTextShape titleShape = slide.getPlaceholder(0);
        if (titleShape != null) {
            titleShape.setText("Data");
        }
        // Remove the empty body placeholder inherited from the layout so it
        // doesn't sit underneath/overlap the table shape.
        XSLFTextShape body = slide.getPlaceholder(1);
        if (body != null) {
            slide.removeShape(body);
        }

        List<String> headers = table.headers();
        int numCols = headers.size();

        XSLFTable xslfTable = slide.createTable();
        xslfTable.setAnchor(TABLE_ANCHOR);

        XSLFTableRow headerRow = xslfTable.addRow();
        for (String header : headers) {
            XSLFTableCell cell = headerRow.addCell();
            cell.setText(header == null ? "" : header);
        }

        List<List<String>> rows = table.rows();
        if (rows != null) {
            for (List<String> row : rows) {
                XSLFTableRow tableRow = xslfTable.addRow();
                for (int i = 0; i < numCols; i++) {
                    XSLFTableCell cell = tableRow.addCell();
                    String value = (row != null && i < row.size()) ? row.get(i) : "";
                    cell.setText(value == null ? "" : value);
                }
            }
        }

        double columnWidth = TABLE_ANCHOR.getWidth() / numCols;
        for (int i = 0; i < numCols; i++) {
            xslfTable.setColumnWidth(i, columnWidth);
        }
    }
}
