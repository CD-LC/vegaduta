package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.AssumptionLine;
import com.agenticai.core.common.roi.PilotReportDoc.Row;
import com.agenticai.core.common.roi.PilotReportDoc.Section;

/**
 * Self-contained, print-friendly HTML of a {@link PilotReportDoc}: inline CSS only, no script, no
 * external resource. Every dynamic string is escaped. A demo document carries a large diagonal
 * "Example" watermark that prints.
 */
final class PilotReportHtml {

    private PilotReportHtml() {}

    static String render(PilotReportDoc d) {
        StringBuilder s = new StringBuilder(4096);
        s.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                // The policy travels with the document: it is opened from a blob: URL, which drops response headers.
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>")
                .append(esc(d.title()))
                .append("</title><style>")
                .append(CSS)
                .append("</style></head><body>");
        if (d.demo()) {
            s.append("<div class=\"wm\" aria-hidden=\"true\">EXAMPLE</div>")
                    .append("<p class=\"demo\">")
                    .append(esc(PilotReportDoc.WATERMARK))
                    .append(d.demoLabel() == null ? "" : " (" + esc(d.demoLabel()) + ")")
                    .append("</p>");
        }
        s.append("<h1>").append(esc(d.title())).append("</h1><p class=\"sub\">")
                .append(esc(d.subtitle()))
                .append("</p>");
        for (Section sec : d.sections()) {
            s.append("<h2>").append(esc(sec.heading())).append("</h2>");
            if (!sec.rows().isEmpty()) {
                s.append("<table>");
                for (Row r : sec.rows()) {
                    s.append("<tr><th>").append(esc(r.label())).append("</th><td>").append(esc(r.value()));
                    if (r.note() != null) {
                        s.append("<div class=\"note\">").append(esc(r.note())).append("</div>");
                    }
                    s.append("</td></tr>");
                }
                s.append("</table>");
            }
            if (sec.message() != null) {
                s.append("<p class=\"msg\">").append(esc(sec.message())).append("</p>");
            }
        }
        s.append("<div class=\"box\"><h2>Assumptions</h2>")
                .append("<p class=\"note\">Every number below was typed by a person at the tenant, or is a")
                .append(" report setting. The platform did not measure or supply any of them.</p>");
        if (d.assumptions().isEmpty()) {
            s.append("<p>No tenant-entered numbers were used.</p>");
        } else {
            s.append("<table>");
            for (AssumptionLine a : d.assumptions()) {
                s.append("<tr><th>").append(esc(a.label())).append("</th><td>").append(esc(a.value()))
                        .append("<div class=\"note\">").append(esc(a.source())).append("</div></td></tr>");
            }
            s.append("</table>");
        }
        s.append("</div>");
        for (String f : d.footnotes()) {
            s.append("<p class=\"note\">").append(esc(f)).append("</p>");
        }
        return s.append("</body></html>").toString();
    }

    static String esc(String in) {
        if (in == null) {
            return "";
        }
        StringBuilder o = new StringBuilder(in.length() + 16);
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            switch (c) {
                case '&' -> o.append("&amp;");
                case '<' -> o.append("&lt;");
                case '>' -> o.append("&gt;");
                case '"' -> o.append("&quot;");
                case '\'' -> o.append("&#39;");
                default -> o.append(c);
            }
        }
        return o.toString();
    }

    private static final String CSS =
            "body{font:14px/1.45 -apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#111;max-width:780px;margin:24px auto;padding:0 16px}"
                    + "h1{font-size:22px;margin:0}h2{font-size:15px;margin:20px 0 6px;border-bottom:1px solid #ccc;padding-bottom:3px}"
                    + ".sub{color:#555;margin:2px 0 8px}table{width:100%;border-collapse:collapse}"
                    + "th{text-align:left;font-weight:600;width:42%;vertical-align:top;padding:4px 8px 4px 0}td{padding:4px 0;vertical-align:top}"
                    + ".note{color:#666;font-size:12px}.msg{background:#f6f6f6;padding:8px 10px;border-radius:4px}"
                    + ".box{border:2px solid #111;padding:4px 14px 10px;margin-top:22px;border-radius:4px}"
                    + ".demo{background:#fff3cd;border:1px solid #d4a017;padding:8px 10px;font-weight:700;border-radius:4px}"
                    + ".wm{position:fixed;top:38%;left:0;right:0;text-align:center;font-size:120px;font-weight:800;color:rgba(200,0,0,.12);"
                    + "transform:rotate(-25deg);pointer-events:none;z-index:9;letter-spacing:12px}"
                    + "@media print{body{margin:0;max-width:none}}";
}
