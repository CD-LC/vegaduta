package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import com.agenticai.core.documentgen.brand.PdfBrandHeader;
import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentType#PDF} {@link DocumentGenerator} - renders a {@link
 * DocumentContent} into a paginated PDF using Apache PDFBox. PDFBox has no
 * built-in text-flow/layout engine, so this class does its own greedy
 * word-wrap (measuring each candidate line against the font's actual glyph
 * widths, same approach as {@code
 * com.agenticai.core.workflow.output.PdfOutputRenderer}) and its own
 * pagination, opening a new {@link PDPage} whenever the next line/row would
 * run past the bottom margin.
 *
 * <p>Rendering order mirrors {@link DocumentContent}'s field order: title
 * (bold, larger) first, then each {@link DocumentContent.Section} (heading,
 * paragraphs, then "{@code - }"-prefixed bullet points), then {@link
 * DocumentContent#table()} as a simple ruled grid if present. When {@link
 * DocumentContent#sections()} is empty, {@link DocumentContent#rawText()} is
 * wrapped and rendered in its place instead.
 */
@Component
public class PdfDocumentGenerator implements DocumentGenerator {

    private static final float MARGIN = 50f;
    private static final float TITLE_SIZE = 18f;
    private static final float HEADING_SIZE = 13f;
    private static final float BODY_SIZE = 11f;
    private static final float TITLE_LEADING = 24f;
    private static final float HEADING_LEADING = 18f;
    private static final float BODY_LEADING = 15f;
    private static final float PARAGRAPH_GAP = 5f;
    private static final float SECTION_GAP = 10f;
    private static final float TABLE_ROW_HEIGHT = 20f;
    private static final float TABLE_CELL_PADDING = 4f;

    @Override
    public DocumentType supportedType() {
        return DocumentType.PDF;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        return generate(request, DocumentBrandResolver.platform());
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request, DocumentBrand brand) throws Exception {
        DocumentContent content = request.content();
        try (PDDocument document = new PDDocument()) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            RenderState state = new RenderState(document, font, boldFont, new PdfBrandHeader(document, brand));
            state.newPage();

            boolean hasTitle = content.title() != null && !content.title().isBlank();
            if (hasTitle) {
                writeWrapped(state, sanitize(content.title()), boldFont, TITLE_SIZE, TITLE_LEADING);
                state.y -= SECTION_GAP;
            }

            List<DocumentContent.Section> sections = content.sections();
            boolean hasSections = sections != null && !sections.isEmpty();
            if (hasSections) {
                for (DocumentContent.Section section : sections) {
                    renderSection(state, section);
                }
            } else if (content.rawText() != null && !content.rawText().isBlank()) {
                writeWrapped(state, sanitize(content.rawText()), font, BODY_SIZE, BODY_LEADING);
            }

            DocumentContent.Table table = content.table();
            if (table != null && table.headers() != null && !table.headers().isEmpty()) {
                if (hasTitle || hasSections) {
                    state.y -= SECTION_GAP;
                }
                renderTable(state, table);
            }

            state.closeStream();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IOException("Could not generate PDF document: " + e.getMessage(), e);
        }
    }

    private void renderSection(RenderState state, DocumentContent.Section section) throws IOException {
        if (section == null) {
            return;
        }
        if (section.heading() != null && !section.heading().isBlank()) {
            writeWrapped(state, sanitize(section.heading()), state.boldFont, HEADING_SIZE, HEADING_LEADING);
            state.y -= 2f;
        }
        List<String> paragraphs = section.paragraphs();
        if (paragraphs != null) {
            for (String paragraph : paragraphs) {
                if (paragraph == null || paragraph.isBlank()) {
                    continue;
                }
                writeWrapped(state, sanitize(paragraph), state.font, BODY_SIZE, BODY_LEADING);
                state.y -= PARAGRAPH_GAP;
            }
        }
        List<String> bullets = section.bulletPoints();
        if (bullets != null) {
            for (String bullet : bullets) {
                if (bullet == null || bullet.isBlank()) {
                    continue;
                }
                writeBullet(state, sanitize(bullet), state.font, BODY_SIZE, BODY_LEADING);
            }
        }
        state.y -= SECTION_GAP;
    }

    /** Word-wraps {@code text} to {@code state}'s content width and draws each resulting line, paginating as needed. */
    private void writeWrapped(RenderState state, String text, PDFont font, float fontSize, float leading) throws IOException {
        for (String line : wrap(text, font, fontSize, state.contentWidth)) {
            drawTextLine(state, MARGIN, line, font, fontSize, leading);
        }
    }

    /**
     * Wraps {@code text} to fit alongside a {@code "- "} bullet prefix, drawing the prefix on the
     * first line only and a matching-width blank indent on any wrapped continuation lines.
     */
    private void writeBullet(RenderState state, String text, PDFont font, float fontSize, float leading) throws IOException {
        float prefixWidth = font.getStringWidth("- ") / 1000f * fontSize;
        List<String> lines = wrap(text, font, fontSize, Math.max(state.contentWidth - prefixWidth, 10f));
        if (lines.isEmpty()) {
            lines = List.of("");
        }
        for (int i = 0; i < lines.size(); i++) {
            String prefix = i == 0 ? "- " : "  ";
            drawTextLine(state, MARGIN, prefix + lines.get(i), font, fontSize, leading);
        }
    }

    private void drawTextLine(RenderState state, float x, String text, PDFont font, float fontSize, float leading) throws IOException {
        state.ensureSpace(leading);
        PDPageContentStream stream = state.stream;
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(x, state.y);
        stream.showText(text);
        stream.endText();
        state.y -= leading;
    }

    /** Renders {@code table} as a ruled grid: equal-width columns, one row per header/data row, cell text truncated with an ellipsis if it overflows its column. */
    private void renderTable(RenderState state, DocumentContent.Table table) throws IOException {
        List<String> headers = table.headers();
        int cols = headers.size();
        float colWidth = state.contentWidth / cols;

        List<List<String>> allRows = new ArrayList<>();
        allRows.add(headers);
        if (table.rows() != null) {
            allRows.addAll(table.rows());
        }

        for (int r = 0; r < allRows.size(); r++) {
            List<String> row = allRows.get(r);
            state.ensureSpace(TABLE_ROW_HEIGHT);
            float top = state.y;
            float bottom = top - TABLE_ROW_HEIGHT;
            PDPageContentStream stream = state.stream;

            stream.setLineWidth(0.5f);
            stream.moveTo(MARGIN, top);
            stream.lineTo(MARGIN + state.contentWidth, top);
            stream.stroke();
            for (int c = 0; c <= cols; c++) {
                float x = MARGIN + c * colWidth;
                stream.moveTo(x, top);
                stream.lineTo(x, bottom);
                stream.stroke();
            }
            stream.moveTo(MARGIN, bottom);
            stream.lineTo(MARGIN + state.contentWidth, bottom);
            stream.stroke();

            PDFont cellFont = r == 0 ? state.boldFont : state.font;
            for (int c = 0; c < cols; c++) {
                String raw = row != null && c < row.size() ? row.get(c) : "";
                String cellText = truncateToWidth(sanitize(raw == null ? "" : raw), cellFont, BODY_SIZE, colWidth - 2 * TABLE_CELL_PADDING);
                if (cellText.isEmpty()) {
                    continue;
                }
                stream.beginText();
                stream.setFont(cellFont, BODY_SIZE);
                stream.newLineAtOffset(MARGIN + c * colWidth + TABLE_CELL_PADDING, bottom + (TABLE_ROW_HEIGHT - BODY_SIZE) / 2f + 2f);
                stream.showText(cellText);
                stream.endText();
            }
            state.y = bottom;
        }
    }

    /**
     * Greedy word-wrap against the font's actual measured string width - PDFBox has no layout
     * engine of its own. Splits on {@code "\n"} first so an explicit newline in source text still
     * produces a blank/new line rather than being folded into surrounding words.
     */
    private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(candidate) / 1000f * fontSize;
                if (width > maxWidth && !current.isEmpty()) {
                    result.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            result.add(current.toString());
        }
        return result;
    }

    /** Shortens {@code text} with a trailing ellipsis until it fits {@code maxWidth}, for table cells that can't wrap. */
    private String truncateToWidth(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        if (text.isEmpty() || font.getStringWidth(text) / 1000f * fontSize <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            String candidate = sb.toString() + c + ellipsis;
            if (font.getStringWidth(candidate) / 1000f * fontSize > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /** PDFBox's WinAnsiEncoding rejects characters outside its code page - strip anything it can't draw. */
    private String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c < 32 && c != '\t' ? ' ' : (c > 255 ? '?' : c));
        }
        return sb.toString();
    }

    /**
     * Mutable per-render cursor: current page/content stream and vertical write position, plus the
     * two fonts, shared by every helper method above so pagination logic lives in one place ({@link
     * #ensureSpace}) rather than being duplicated at each call site.
     */
    private static final class RenderState {
        private final PDDocument document;
        private final PDFont font;
        private final PDFont boldFont;
        private final float pageHeight = PDRectangle.LETTER.getHeight();
        private final float contentWidth = PDRectangle.LETTER.getWidth() - 2 * MARGIN;
        private final PdfBrandHeader brandHeader;
        private PDPageContentStream stream;
        private float y;

        private RenderState(PDDocument document, PDFont font, PDFont boldFont, PdfBrandHeader brandHeader) {
            this.document = document;
            this.font = font;
            this.boldFont = boldFont;
            this.brandHeader = brandHeader;
        }

        private void newPage() throws IOException {
            closeStream();
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = pageHeight - MARGIN;
            // Brand strip on every page (tenant brand or VegaDuta's).
            y -= brandHeader.draw(stream, font, MARGIN, y, contentWidth);
        }

        private void ensureSpace(float needed) throws IOException {
            if (stream == null || y - needed < MARGIN) {
                newPage();
            }
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
