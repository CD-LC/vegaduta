package com.agenticai.core.knowledge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sidecar-free text extraction for the formats that need no native library: HTML and DOCX. Used by
 * {@link SidecarClient#extractText} only when the sidecar is switched off
 * ({@code app.sidecar.enabled=false}); with the sidecar on, every format still goes to its
 * {@code /extract}, unchanged.
 *
 * <p>PDF and images (OCR) genuinely need the sidecar's parsers, so they answer with a
 * {@link FeatureUnavailableException} that says so, rather than failing at boot or returning garbage.
 * The parsers are deliberately hand-rolled single-pass scanners: no XML/HTML parser is instantiated, so
 * there is no external-entity surface, the DOCX inflate is capped so a zip bomb cannot exhaust memory, and
 * work stays linear in the input however many tags are left unterminated.
 */
final class InProcessTextExtractor {

    /** Cap on the inflated size of {@code word/document.xml} (the plain text of a 5MB upload is far below this). */
    static final int MAX_INFLATED_BYTES = 32 * 1024 * 1024;

    // Whole-tag shapes, matched against a single tag only (bounded by the next '<' or '>'), never
    // against the document: the scanners below walk the input once, so a hostile upload full of
    // unterminated tags cannot trigger the quadratic backtracking that a document-wide lazy regex has.
    private static final Pattern HTML_BLOCK_END_TAG =
        Pattern.compile("(?i)</(?:p|div|li|tr|h[1-6]|section|article|table|ul|ol|blockquote|pre)\\s*>|<br\\s*/?>");
    private static final Pattern DOCX_T_OPEN = Pattern.compile("<w:t(?:\\s[^<>]*)?>");
    private static final Pattern DOCX_TAB = Pattern.compile("<w:tab\\s*/>");
    private static final Pattern DOCX_BREAK = Pattern.compile("<w:(?:br|cr)\\s*/>");
    private static final String[] HTML_SKIPPED_ELEMENTS = {"script", "style", "head"};
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);");
    private static final Pattern BLANK_RUNS = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private InProcessTextExtractor() {
    }

    static String extract(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            text = htmlToText(new String(content, StandardCharsets.UTF_8));
        } else if (lower.endsWith(".docx")) {
            text = docxToText(content);
        } else if (lower.endsWith(".pdf")) {
            throw new FeatureUnavailableException(
                "PDF text extraction needs the Python sidecar, which is disabled (app.sidecar.enabled=false). "
                    + "Upload .md, .html or .docx, or start the sidecar and set app.sidecar.enabled=true.");
        } else if (isImage(lower)) {
            throw new FeatureUnavailableException(
                "Image OCR needs the Python sidecar, which is disabled (app.sidecar.enabled=false). "
                    + "Upload .md, .html or .docx, or start the sidecar and set app.sidecar.enabled=true.");
        } else {
            throw new InvalidKnowledgeUploadException("Unsupported file type for extraction: " + filename);
        }
        if (text.isBlank()) {
            throw new InvalidKnowledgeUploadException("No readable text found in " + filename);
        }
        return text;
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    static String htmlToText(String html) {
        StringBuilder out = new StringBuilder(html.length());
        boolean[] noClose = new boolean[HTML_SKIPPED_ELEMENTS.length];
        int n = html.length();
        int i = 0;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                out.append(html, i, n);
                break;
            }
            out.append(html, i, lt);
            int gt = html.indexOf('>', lt + 1);
            if (gt < 0) {
                out.append(html, lt, n); // a '<' that never closes is literal text
                break;
            }
            int skipEnd = skippedElementEnd(html, lt, gt, noClose);
            if (skipEnd >= 0) {
                out.append(' ');
                i = skipEnd;
                continue;
            }
            out.append(HTML_BLOCK_END_TAG.matcher(html).region(lt, gt + 1).matches() ? '\n' : ' ');
            i = gt + 1;
        }
        return tidy(decodeEntities(out.toString()));
    }

    /**
     * If the tag at {@code lt..gt} opens a script/style/head element that has a closing tag, the index
     * just past that closing tag; else -1 (the tag is then stripped like any other). One forward
     * search per element name that finds nothing, never one per occurrence.
     */
    private static int skippedElementEnd(String html, int lt, int gt, boolean[] noClose) {
        for (int k = 0; k < HTML_SKIPPED_ELEMENTS.length; k++) {
            String name = HTML_SKIPPED_ELEMENTS[k];
            if (noClose[k] || !html.regionMatches(true, lt + 1, name, 0, name.length())) {
                continue;
            }
            int after = lt + 1 + name.length();
            if (after < gt && isWordChar(html.charAt(after))) {
                continue; // <header>, <styled>: a different element
            }
            int from = gt + 1;
            while (true) {
                int close = html.indexOf("</", from);
                if (close < 0) {
                    noClose[k] = true;
                    return -1;
                }
                if (html.regionMatches(true, close + 2, name, 0, name.length())) {
                    int p = close + 2 + name.length();
                    while (p < html.length() && Character.isWhitespace(html.charAt(p))) {
                        p++;
                    }
                    if (p < html.length() && html.charAt(p) == '>') {
                        return p + 1;
                    }
                }
                from = close + 2;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static String docxToText(byte[] docx) {
        String xml = readDocumentXml(docx);
        StringBuilder out = new StringBuilder(xml.length() / 4);
        boolean noCloseT = false;
        int n = xml.length();
        int i = 0;
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            i = lt + 1;
            if (xml.startsWith("</w:p>", lt)) {
                out.append('\n');
                i = lt + 6;
                continue;
            }
            Matcher open = DOCX_T_OPEN.matcher(xml).region(lt, n);
            if (open.lookingAt() && !open.group().endsWith("/>")) {
                int close = noCloseT ? -1 : xml.indexOf("</w:t>", open.end());
                if (close < 0) {
                    noCloseT = true;
                    continue;
                }
                out.append(decodeEntities(xml.substring(open.end(), close)));
                i = close + 6;
                continue;
            }
            if (DOCX_TAB.matcher(xml).region(lt, n).lookingAt()) {
                out.append('\t');
            } else if (DOCX_BREAK.matcher(xml).region(lt, n).lookingAt()) {
                out.append('\n');
            }
        }
        return tidy(out.toString());
    }

    private static String readDocumentXml(byte[] docx) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!"word/document.xml".equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    if (buffer.size() + read > MAX_INFLATED_BYTES) {
                        throw new InvalidKnowledgeUploadException("The .docx expands to more text than the extractor allows");
                    }
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidKnowledgeUploadException("The file is not a readable .docx document");
        }
        throw new InvalidKnowledgeUploadException("The file is not a readable .docx document (no word/document.xml)");
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(entity(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String entity(String name) {
        switch (name) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "nbsp":
                return " ";
            default:
                try {
                    int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    return Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : " ";
                } catch (NumberFormatException e) {
                    return " ";
                }
        }
    }

    private static String tidy(String s) {
        String t = BLANK_RUNS.matcher(s).replaceAll(" ");
        t = t.replaceAll(" *\\n *", "\n");
        t = MANY_NEWLINES.matcher(t).replaceAll("\n\n");
        return t.strip();
    }
}
