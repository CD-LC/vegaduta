package com.agenticai.core.documentgen.model;

import java.util.List;

/**
 * Format-agnostic document body a {@code DocumentGenerator} renders into bytes. Every {@code
 * generator.impl} implementation reads whichever subset of these fields makes sense for its format
 * (e.g. {@code CsvDocumentGenerator} reads only {@link #table()}, {@code MarkdownDocumentGenerator}
 * reads {@link #title()}/{@link #sections()}, {@code JsonDocumentGenerator} reads only {@link
 * #json()}) - callers set whichever fields apply to the {@link DocumentType} they're requesting and
 * leave the rest {@code null}/empty; no generator is required to honor every field.
 *
 * @param title optional document title (nullable - not every format uses one, e.g. CSV)
 * @param sections ordered body content - headings, paragraphs, bullet points. Empty (never null)
 *     when unused.
 * @param table optional tabular data (nullable) - the primary payload for CSV/XLSX, an optional
 *     extra section for DOCX/PDF/PPTX
 * @param rawText optional pre-formatted plain text (nullable) - the primary payload for TXT, and
 *     MD's fallback when {@link #sections()} is empty
 * @param json raw passthrough value for {@link DocumentType#JSON} (nullable) - typically a {@code
 *     com.fasterxml.jackson.databind.JsonNode} or {@code Map<String, Object>}; the JSON generator
 *     serializes this value verbatim rather than reading title/sections/table
 */
public record DocumentContent(
        String title, List<Section> sections, Table table, String rawText, Object json) {

    /**
     * One block of prose within a document: an optional heading, ordinary paragraphs, and/or a
     * bullet list. A section may carry both paragraphs and bullet points (rendered in that order)
     * or just one of the two.
     *
     * @param heading optional section heading (nullable)
     * @param paragraphs ordinary paragraph text, rendered in order. Empty (never null) when unused.
     * @param bulletPoints bullet-list items, rendered in order after {@link #paragraphs()}. Empty
     *     (never null) when unused.
     */
    /**
     * One section: heading, prose, bullets, and an optional public http(s) image URL that the
     * generator fetches (SSRF-guarded, png/jpeg) and embeds on that section's slide/page. The 3-arg
     * constructor keeps the pre-image call sites.
     */
    public record Section(
            String heading, List<String> paragraphs, List<String> bulletPoints, String imageUrl) {

        public Section(String heading, List<String> paragraphs, List<String> bulletPoints) {
            this(heading, paragraphs, bulletPoints, null);
        }
    }

    /**
     * A simple rectangular table: one header row plus data rows, each row the same width as {@link
     * #headers()} (generators are free to reject or pad a malformed row rather than crash, at their
     * own discretion).
     *
     * @param headers column headers, in order
     * @param rows data rows, each an ordered list of cell values matching {@link #headers()}'s
     *     width
     */
    public record Table(List<String> headers, List<List<String>> rows) {}
}
