package com.agenticai.core.workflow.output;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import com.agenticai.core.documentgen.brand.DocumentBrand;
import com.agenticai.core.documentgen.brand.DocumentBrandResolver;
import com.agenticai.core.documentgen.brand.PdfBrandHeader;
import com.agenticai.core.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Output node "pdf" file type (#390 - "if he wants pdf, we should be able to
 * generate from output generator module"): renders a run's plain-text output
 * as a real, paginated PDF document, not merely a {@code .txt} file with a
 * {@code .pdf} extension slapped on. Deliberately simple - one monospace-ish
 * body font, word-wrapped, paginated when it overflows a page - since the
 * source content here is always an agent/workflow's text output, never a
 * user-authored layout that would need real typesetting.
 *
 * <p>The result is Base64-encoded into a {@code String} before being handed
 * back to {@link com.agenticai.core.workflow.WorkflowExecutionEngine}, since
 * {@code WorkflowRun.outputData} is a single {@code TEXT} column shared with
 * the plain-text file destination - see that field's javadoc. {@code
 * WorkflowController#downloadRunOutput} decides whether to Base64-decode
 * based on the output file's {@code .pdf} extension, so a plain-text output
 * (never Base64) and a PDF output (always Base64) never get confused with
 * each other.
 */
@Component
public class PdfOutputRenderer {

    private static final float MARGIN = 50f;
    private static final float FONT_SIZE = 11f;
    private static final float LEADING = 16f;

    /** Optional: absent (tests, minimal contexts) -> the VegaDuta brand. */
    private DocumentBrandResolver brandResolver;

    @Autowired(required = false)
    void setBrandResolver(DocumentBrandResolver brandResolver) {
        this.brandResolver = brandResolver;
    }

    /** Tenant brand when the current tenant set one, VegaDuta's otherwise (2026-10-09). */
    private DocumentBrand brand() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return brandResolver != null ? brandResolver.resolve(tenantId) : DocumentBrandResolver.platform();
    }

    /** @return the rendered PDF's bytes, Base64-encoded as an ASCII string. */
    public String renderToBase64(String title, String body) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            List<String> lines = wrap(body == null ? "" : body, font, FONT_SIZE, PDRectangle.LETTER.getWidth() - 2 * MARGIN);
            float pageTop = PDRectangle.LETTER.getHeight() - MARGIN;

            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);

            // Brand strip on the first page, then the title below it.
            float titleTop = pageTop - new PdfBrandHeader(document, brand())
                .draw(stream, font, MARGIN, pageTop + FONT_SIZE, PDRectangle.LETTER.getWidth() - 2 * MARGIN);

            stream.beginText();
            stream.setFont(boldFont, 14f);
            stream.newLineAtOffset(MARGIN, titleTop);
            stream.showText(sanitize(truncate(title == null || title.isBlank() ? "Workflow Output" : title, 90)));
            stream.endText();

            float y = titleTop - LEADING * 2;
            stream.setFont(font, FONT_SIZE);
            stream.beginText();
            stream.newLineAtOffset(MARGIN, y);
            boolean textOpen = true;

            for (String line : lines) {
                if (y < MARGIN) {
                    stream.endText();
                    stream.close();
                    page = new PDPage(PDRectangle.LETTER);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    stream.setFont(font, FONT_SIZE);
                    y = pageTop;
                    stream.beginText();
                    stream.newLineAtOffset(MARGIN, y);
                    textOpen = true;
                }
                stream.showText(line);
                stream.newLineAtOffset(0, -LEADING);
                y -= LEADING;
            }
            if (textOpen) {
                stream.endText();
            }
            stream.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not render PDF output: " + e.getMessage(), e);
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /**
     * Simple greedy word-wrap against the font's actual measured string
     * width - no external layout library needed. Sanitizes each word up
     * front (not just for width measurement) so every line this returns is
     * already safe to pass straight to {@code showText} - WinAnsiEncoding
     * throws on characters outside its code page, and there's no second
     * sanitize pass at draw time.
     */
    private List<String> wrap(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        for (String rawParagraph : text.split("\n", -1)) {
            String paragraph = sanitize(rawParagraph);
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                float width = font.getStringWidth(candidate) / 1000 * fontSize;
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

    /** PDFBox's WinAnsiEncoding rejects characters outside its code page - strip anything it can't draw. */
    private String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c < 32 && c != '\t' ? ' ' : (c > 255 ? '?' : c));
        }
        return sb.toString();
    }
}
