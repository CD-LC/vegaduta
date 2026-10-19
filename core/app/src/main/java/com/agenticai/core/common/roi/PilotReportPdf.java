package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.AssumptionLine;
import com.agenticai.core.common.roi.PilotReportDoc.Row;
import com.agenticai.core.common.roi.PilotReportDoc.Section;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;

/**
 * PDF of a {@link PilotReportDoc}, drawn with PDFBox's standard Helvetica (no font files, no
 * network). PDFBox has no layout engine, so this wraps and paginates itself. Characters the
 * standard font cannot encode are reduced to their base letter or '?'; the report's currency
 * figures are written with ISO codes, so no symbol is needed. A demo document gets a large
 * diagonal "EXAMPLE" watermark on every page.
 */
final class PilotReportPdf {

    private static final float MARGIN = 50f;
    private static final float BOTTOM = 56f;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private final PDDocument doc = new PDDocument();
    private final PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final boolean demo;
    private PDPageContentStream cs;
    private float y;

    private PilotReportPdf(boolean demo) {
        this.demo = demo;
    }

    static byte[] render(PilotReportDoc d) throws IOException {
        PilotReportPdf p = new PilotReportPdf(d.demo());
        try {
            return p.draw(d);
        } finally {
            p.doc.close();
        }
    }

    private byte[] draw(PilotReportDoc d) throws IOException {
        newPage();
        if (d.demo()) {
            wrapped(
                    PilotReportDoc.WATERMARK + (d.demoLabel() == null ? "" : " (" + d.demoLabel() + ")"),
                    bold,
                    10,
                    0,
                    Color.decode("#8a1c1c"));
            y -= 6;
        }
        wrapped(d.title(), bold, 18, 0, Color.BLACK);
        wrapped(d.subtitle(), font, 10, 0, Color.DARK_GRAY);
        y -= 8;
        for (Section s : d.sections()) {
            heading(s.heading());
            for (Row r : s.rows()) {
                row(r.label(), r.value(), r.note(), 0);
            }
            if (s.message() != null) {
                wrapped(s.message(), font, 10, 6, Color.DARK_GRAY);
            }
        }
        assumptionsBox(d.assumptions());
        y -= 6;
        for (String f : d.footnotes()) {
            wrapped(f, font, 8.5f, 0, Color.GRAY);
        }
        cs.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private void assumptionsBox(List<AssumptionLine> lines) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (AssumptionLine a : lines) {
            rows.add(new String[] {a.label(), a.value() + "  [" + a.source() + "]"});
        }
        float need = 60f + rows.size() * 26f;
        float usable = PDRectangle.A4.getHeight() - 2 * MARGIN;
        if (y - need < BOTTOM && need < usable) {
            newPage();
        }
        boolean boxed = need < usable;
        float top = y + 4;
        heading("Assumptions");
        wrapped(
                "Every number below was typed by a person at the tenant, or is a report setting."
                        + " The platform did not measure or supply any of them.",
                font,
                8.5f,
                6,
                Color.GRAY);
        if (rows.isEmpty()) {
            wrapped("No tenant-entered numbers were used.", font, 10, 6, Color.BLACK);
        }
        for (String[] r : rows) {
            row(r[0], r[1], null, 6);
        }
        if (boxed) {
            cs.setLineWidth(1.5f);
            cs.setStrokingColor(Color.BLACK);
            cs.addRect(MARGIN - 6, y - 4, WIDTH + 12, top - y + 4);
            cs.stroke();
        }
    }

    private void heading(String text) throws IOException {
        ensure(34);
        y -= 12;
        wrapped(text, bold, 12, 0, Color.BLACK);
        cs.setLineWidth(0.5f);
        cs.setStrokingColor(Color.LIGHT_GRAY);
        cs.moveTo(MARGIN, y + 8);
        cs.lineTo(MARGIN + WIDTH, y + 8);
        cs.stroke();
        y -= 2;
    }

    /** Label in a left column, value (and note) wrapped in the right column. */
    private void row(String label, String value, String note, float indent) throws IOException {
        float labelW = WIDTH * 0.42f;
        float left = MARGIN + indent;
        List<String> l = wrap(clean(label), bold, 10, labelW - indent - 6);
        List<String> v = wrap(clean(value), font, 10, WIDTH - labelW);
        List<String> n = note == null ? List.of() : wrap(clean(note), font, 8.5f, WIDTH - labelW);
        int lines = Math.max(l.size(), v.size() + n.size());
        ensure(lines * 13f + 4);
        float top = y;
        float yy = top;
        for (String s : l) {
            text(s, bold, 10, left, yy, Color.BLACK);
            yy -= 13;
        }
        yy = top;
        for (String s : v) {
            text(s, font, 10, MARGIN + labelW, yy, Color.BLACK);
            yy -= 13;
        }
        for (String s : n) {
            text(s, font, 8.5f, MARGIN + labelW, yy, Color.GRAY);
            yy -= 11;
        }
        y = Math.min(top - l.size() * 13f, yy) - 4;
    }

    private void wrapped(String text, PDFont f, float size, float indent, Color c) throws IOException {
        for (String line : wrap(clean(text), f, size, WIDTH - indent)) {
            ensure(size + 4);
            text(line, f, size, MARGIN + indent, y, c);
            y -= size + 4;
        }
    }

    private void text(String s, PDFont f, float size, float x, float yy, Color c) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.setNonStrokingColor(c);
        cs.newLineAtOffset(x, yy);
        cs.showText(s);
        cs.endText();
    }

    private void ensure(float h) throws IOException {
        if (y - h < BOTTOM) {
            newPage();
        }
    }

    private void newPage() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        y = PDRectangle.A4.getHeight() - MARGIN;
        if (demo) {
            cs.beginText();
            cs.setFont(bold, 110);
            cs.setNonStrokingColor(new Color(240, 205, 205));
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35), 110, 260));
            cs.showText("EXAMPLE");
            cs.endText();
        }
    }

    private List<String> wrap(String s, PDFont f, float size, float width) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (width(trial, f, size) <= width || line.length() == 0) {
                line = new StringBuilder(trial);
            } else {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        out.add(line.toString());
        return out;
    }

    private float width(String s, PDFont f, float size) throws IOException {
        return f.getStringWidth(s) / 1000f * size;
    }

    /** Maps text onto what Helvetica (WinAnsi) can draw; never throws on odd input. */
    private String clean(String in) {
        if (in == null) {
            return "";
        }
        StringBuilder o = new StringBuilder(in.length());
        for (char ch : in.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').toCharArray()) {
            if (encodable(ch)) {
                o.append(ch);
                continue;
            }
            String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD);
            char b = base.isEmpty() ? '?' : base.charAt(0);
            o.append(encodable(b) ? b : '?');
        }
        return o.toString();
    }

    private boolean encodable(char ch) {
        try {
            font.encode(String.valueOf(ch));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
