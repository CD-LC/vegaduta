package com.agenticai.core.documentgen.brand;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Draws a document's brand strip (logo, plus the tenant name when it has one)
 * at the top of a PDF page and reports how much vertical space it used. Shared
 * by documentgen's PDF generator and the workflow PDF renderers so every PDF
 * the platform produces carries the same header. Per-document: build one per
 * PDDocument (the image object belongs to that document).
 */
public final class PdfBrandHeader {

    private static final float LOGO_HEIGHT = 18f;
    private static final float MAX_LOGO_WIDTH = 120f;
    private static final float NAME_SIZE = 10f;
    // Callers draw the next line on its BASELINE at the returned y, so the gap must also clear
    // that line's cap height (an 18pt title rises ~13pt above its baseline).
    private static final float GAP_BELOW = 30f;

    private final DocumentBrand brand;
    private final PDImageXObject image;

    public PdfBrandHeader(PDDocument document, DocumentBrand brand) {
        this.brand = brand == null ? DocumentBrandResolver.platform() : brand;
        PDImageXObject img = null;
        if (this.brand.hasLogo()) {
            try {
                img = PDImageXObject.createFromByteArray(document, this.brand.logo(), "brand-logo");
            } catch (IOException | RuntimeException e) {
                img = null; // corrupt/unsupported image: header falls back to the name alone
            }
        }
        this.image = img;
    }

    /**
     * @param x      left edge of the content area
     * @param topY   top of the content area (the page's y cursor)
     * @param width  content-area width, for the hairline under the header
     * @return vertical space consumed, so the caller moves its cursor down by it
     */
    public float draw(PDPageContentStream stream, PDFont font, float x, float topY, float width) throws IOException {
        float used = 0f;
        float textX = x;
        if (image != null) {
            float h = LOGO_HEIGHT;
            float w = image.getWidth() * h / Math.max(1, image.getHeight());
            if (w > MAX_LOGO_WIDTH) {
                h = h * MAX_LOGO_WIDTH / w;
                w = MAX_LOGO_WIDTH;
            }
            stream.drawImage(image, x, topY - h, w, h);
            textX = x + w + 8f;
            used = LOGO_HEIGHT;
        }
        String name = brand.hasName() ? pdfSafe(font, brand.name()) : "";
        if (!name.isEmpty()) {
            stream.beginText();
            stream.setFont(font, NAME_SIZE);
            stream.newLineAtOffset(textX, topY - (used > 0 ? used / 2f + NAME_SIZE / 3f : NAME_SIZE));
            stream.showText(name);
            stream.endText();
            used = Math.max(used, NAME_SIZE + 2f);
        }
        if (used == 0f) {
            return 0f;
        }
        float lineY = topY - used - 5f;
        stream.setLineWidth(0.5f);
        stream.setStrokingColor(0.8f, 0.8f, 0.8f);
        stream.moveTo(x, lineY);
        stream.lineTo(x + width, lineY);
        stream.stroke();
        stream.setStrokingColor(0f, 0f, 0f);
        return used + GAP_BELOW;
    }

    /** Standard-14 fonts only encode WinAnsi: "VegaDūta" -> "VegaDuta"; other unencodable chars dropped. */
    static String pdfSafe(PDFont font, String text) {
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                // not encodable in this font - skip it
            }
            i += Character.charCount(cp);
        }
        return out.toString().trim();
    }
}
