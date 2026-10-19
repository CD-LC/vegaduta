package com.agenticai.core.workflow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * File bytes to plain text for the {@code document_extractor} workflow node, with hard size, page and row caps.
 *
 * <p>Reuse, no new dependency: PDF / DOCX / HTML go through the knowledge module's existing ingestion extractor (the
 * sidecar's {@code /extract}, reached by {@code SidecarClient#extractText}, handed in as an {@code extractor}
 * function so this class stays free of Spring and testable); plain text, Markdown and CSV are decoded directly (they
 * never needed an extractor - {@code .md} is read as-is by the knowledge upload too); XLSX is read with Apache POI,
 * which is already in the reactor for the contacts import (and PDFBox for the page cap is already a dependency of
 * this module for the Output node's PDF renderer).
 *
 * <p>Caps: the CALLER caps the input bytes before this class sees them; here {@code maxPages} bounds a PDF (extra pages
 * are cut from the document BEFORE it is sent for extraction, so a 2,000-page file never costs a 2,000-page OCR pass),
 * {@code maxRows} bounds spreadsheets (all sheets together) and CSV, and {@code maxChars} bounds the final text for
 * every format. Anything cut is reported in {@link Result#truncated()}.
 */
final class WorkflowDocumentText {

    /** Supported formats; the wire names are what the node's {@code format} config takes. */
    enum Format {
        PDF("pdf"), DOCX("docx"), XLSX("xlsx"), CSV("csv"), TXT("txt"), MD("md"), HTML("html");

        final String wire;

        Format(String wire) {
            this.wire = wire;
        }
    }

    /** Caps for one extraction. */
    record Limits(int maxChars, int maxPages, int maxRows) {
    }

    /**
     * @param pages     page count of the SOURCE document when it has pages (PDF), else -1
     * @param truncated true when any cap cut content (pages, rows or characters)
     */
    record Result(String text, Format format, int pages, boolean truncated) {
    }

    /** A person-readable reason the document could not be turned into text; the node reports it as SKIPPED. */
    static final class DocumentExtractionException extends RuntimeException {
        DocumentExtractionException(String message) {
            super(message);
        }
    }

    static final String TRUNCATION_MARKER = "\n[truncated]";
    static final int MAX_SHEETS = 10;
    private static final int MAX_ZIP_ENTRIES_SNIFFED = 200;

    private WorkflowDocumentText() {
    }

    /** The format a node's {@code format} config names, or null for blank/"auto". Unknown names throw. */
    static Format parseFormat(String hint) {
        if (hint == null || hint.isBlank() || hint.strip().equalsIgnoreCase("auto")) {
            return null;
        }
        String h = hint.strip().toLowerCase(Locale.ROOT);
        for (Format f : Format.values()) {
            if (f.wire.equals(h)) {
                return f;
            }
        }
        throw new DocumentExtractionException("unsupported document format '" + hint + "' (supported: pdf, docx, xlsx, csv, txt, md, html)");
    }

    /** File name extension, then Content-Type, then a look at the bytes. Null when none of them identifies a supported format. */
    static Format detect(String fileName, String contentType, byte[] content) {
        Format byName = fromExtension(fileName);
        if (byName != null) {
            return byName;
        }
        Format byType = fromContentType(contentType);
        if (byType != null) {
            return byType;
        }
        return sniff(content);
    }

    static Format fromExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.strip().toLowerCase(Locale.ROOT);
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "pdf" -> Format.PDF;
            case "docx" -> Format.DOCX;
            case "xlsx" -> Format.XLSX;
            case "csv", "tsv" -> Format.CSV;
            case "txt", "text", "log", "json" -> Format.TXT;
            case "md", "markdown" -> Format.MD;
            case "html", "htm" -> Format.HTML;
            default -> null;
        };
    }

    static Format fromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("application/pdf")) {
            return Format.PDF;
        }
        if (type.contains("wordprocessingml.document")) {
            return Format.DOCX;
        }
        if (type.contains("spreadsheetml.sheet")) {
            return Format.XLSX;
        }
        if (type.contains("text/csv")) {
            return Format.CSV;
        }
        if (type.contains("text/markdown")) {
            return Format.MD;
        }
        if (type.contains("text/html") || type.contains("application/xhtml")) {
            return Format.HTML;
        }
        if (type.contains("text/plain") || type.contains("application/json")) {
            return Format.TXT;
        }
        return null;
    }

    /** Magic bytes: %PDF-, a ZIP holding word/ or xl/ parts, otherwise text when the head has no NUL bytes. */
    static Format sniff(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-') {
            return Format.PDF;
        }
        if (content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4) {
            return sniffOffice(content);
        }
        int head = Math.min(content.length, 1024);
        for (int i = 0; i < head; i++) {
            if (content[i] == 0) {
                return null;
            }
        }
        String start = new String(content, 0, head, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html") ? Format.HTML : Format.TXT;
    }

    private static Format sniffOffice(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null && seen++ < MAX_ZIP_ENTRIES_SNIFFED) {
                String name = entry.getName();
                if (name.startsWith("word/")) {
                    return Format.DOCX;
                }
                if (name.startsWith("xl/")) {
                    return Format.XLSX;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * @param content   the file's bytes (already size-capped by the caller)
     * @param format    the format to read it as (use {@link #detect} first)
     * @param fileName  a file name carrying the right extension, handed to the sidecar extractor (which dispatches on it)
     * @param extractor the knowledge ingestion extractor, {@code (fileName, bytes) -> text}; may be null when no
     *                  sidecar is deployed, in which case PDF / DOCX / HTML are refused with a clear reason
     */
    static Result extract(
        byte[] content, Format format, String fileName, BiFunction<String, byte[], String> extractor, Limits limits
    ) {
        if (content == null || content.length == 0) {
            throw new DocumentExtractionException("the file is empty");
        }
        boolean truncated = false;
        int pages = -1;
        String text;
        switch (format) {
            case TXT, MD -> text = decodeText(content);
            case CSV -> {
                String all = decodeText(content);
                String[] lines = all.split("\r?\n", -1);
                if (lines.length > limits.maxRows()) {
                    truncated = true;
                    text = String.join("\n", java.util.Arrays.copyOf(lines, limits.maxRows()));
                } else {
                    text = all;
                }
            }
            case XLSX -> {
                boolean[] cut = new boolean[1];
                text = xlsxText(content, limits.maxRows(), limits.maxChars(), cut);
                truncated = cut[0];
            }
            case PDF -> {
                requireExtractor(extractor, "PDF");
                int[] total = new int[1];
                byte[] capped = capPdfPages(content, limits.maxPages(), total);
                pages = total[0];
                truncated = pages > limits.maxPages();
                text = extractor.apply(safeName(fileName, "document.pdf", ".pdf"), capped);
            }
            case DOCX -> {
                requireExtractor(extractor, "Word");
                text = extractor.apply(safeName(fileName, "document.docx", ".docx"), content);
            }
            case HTML -> {
                requireExtractor(extractor, "HTML");
                text = extractor.apply(safeName(fileName, "document.html", ".html"), content);
            }
            default -> throw new DocumentExtractionException("unsupported document format");
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new DocumentExtractionException("no readable text was found in the document");
        }
        if (text.length() > limits.maxChars()) {
            text = text.substring(0, limits.maxChars()) + TRUNCATION_MARKER;
            truncated = true;
        }
        return new Result(text, format, pages, truncated);
    }

    private static void requireExtractor(BiFunction<String, byte[], String> extractor, String what) {
        if (extractor == null) {
            throw new DocumentExtractionException(what + " extraction is not available in this deployment (the document extraction service is not running)");
        }
    }

    /** The given name when it already ends with {@code ext}, otherwise a stock name - the sidecar dispatches on the extension. */
    static String safeName(String fileName, String fallback, String ext) {
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String base = fileName.strip();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int query = base.indexOf('?');
        if (query >= 0) {
            base = base.substring(0, query);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!base.toLowerCase(Locale.ROOT).endsWith(ext)) {
            return fallback;
        }
        return base.length() > 120 ? fallback : base;
    }

    static String decodeText(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /**
     * Cuts a PDF down to its first {@code maxPages} pages and reports the SOURCE page count in {@code totalOut[0]}.
     * The original bytes come back untouched when the document is within the cap.
     */
    static byte[] capPdfPages(byte[] pdf, int maxPages, int[] totalOut) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            totalOut[0] = total;
            if (total <= maxPages) {
                return pdf;
            }
            for (int i = total - 1; i >= maxPages; i--) {
                document.removePage(i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new DocumentExtractionException("the PDF could not be read (it may be corrupt or password-protected)");
        }
    }

    /**
     * Tab-separated text of the workbook's sheets (first {@link #MAX_SHEETS}), at most {@code maxRows} non-empty rows in
     * total, stopping once {@code maxChars} is exceeded. Formula cells read their cached result - nothing is evaluated.
     */
    static String xlsxText(byte[] content, int maxRows, int maxChars, boolean[] truncatedOut) {
        StringBuilder out = new StringBuilder();
        int rows = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int sheets = workbook.getNumberOfSheets();
            if (sheets > MAX_SHEETS) {
                truncatedOut[0] = true;
            }
            for (int s = 0; s < Math.min(sheets, MAX_SHEETS); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                out.append("## Sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    String line = rowText(row, formatter);
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (rows >= maxRows || out.length() > maxChars) {
                        truncatedOut[0] = true;
                        return out.toString();
                    }
                    out.append(line).append('\n');
                    rows++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // POI signals a corrupt/unsupported file with a mix of IOException and runtime exceptions.
            throw new DocumentExtractionException("the spreadsheet could not be read (it may be corrupt or password-protected)");
        }
        return out.toString();
    }

    private static String rowText(Row row, DataFormatter formatter) {
        int last = row.getLastCellNum();
        if (last <= 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        int lastNonEmpty = -1;
        String[] cells = new String[last];
        for (int c = 0; c < last; c++) {
            String value = cellText(row.getCell(c), formatter).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
            cells[c] = value;
            if (!value.isEmpty()) {
                lastNonEmpty = c;
            }
        }
        for (int c = 0; c <= lastNonEmpty; c++) {
            if (c > 0) {
                line.append('\t');
            }
            line.append(cells[c]);
        }
        return line.toString();
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> formatter.formatRawCellContents(
                    cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        }
        return formatter.formatCellValue(cell);
    }
}
