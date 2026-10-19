package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentContent} into CSV bytes. Prefers {@link
 * DocumentContent#table()} when present (headers + rows, properly quoted);
 * otherwise falls back to a single-column CSV built from {@link
 * DocumentContent#sections()}'s paragraphs/bullet points flattened in order,
 * or - if there are no sections either - one row per line of {@link
 * DocumentContent#rawText()}.
 */
@Component
public class CsvDocumentGenerator implements DocumentGenerator {

    private static final String LINE_SEPARATOR = "\r\n";

    @Override
    public DocumentType supportedType() {
        return DocumentType.CSV;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        DocumentContent content = request.content();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            DocumentContent.Table table = content.table();
            if (table != null && table.headers() != null && !table.headers().isEmpty()) {
                writeTable(writer, table);
            } else {
                writeSingleColumnFallback(writer, content);
            }
        }
        return out.toByteArray();
    }

    private void writeTable(Writer writer, DocumentContent.Table table) throws Exception {
        writeRow(writer, table.headers());
        List<List<String>> rows = table.rows();
        if (rows != null) {
            for (List<String> row : rows) {
                writeRow(writer, row);
            }
        }
    }

    private void writeRow(Writer writer, List<String> cells) throws Exception {
        if (cells == null || cells.isEmpty()) {
            writer.write(LINE_SEPARATOR);
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(",");
            }
            writer.write(escape(cells.get(i)));
        }
        writer.write(LINE_SEPARATOR);
    }

    /**
     * No table supplied - fall back to one row per paragraph/bullet point
     * flattened from {@link DocumentContent#sections()}, in order; if there
     * are no sections either, one row per line of {@link
     * DocumentContent#rawText()}.
     */
    private void writeSingleColumnFallback(Writer writer, DocumentContent content) throws Exception {
        List<DocumentContent.Section> sections = content.sections();
        boolean wroteAny = false;
        if (sections != null && !sections.isEmpty()) {
            for (DocumentContent.Section section : sections) {
                if (section.heading() != null && !section.heading().isBlank()) {
                    writer.write(escape(section.heading()));
                    writer.write(LINE_SEPARATOR);
                    wroteAny = true;
                }
                if (section.paragraphs() != null) {
                    for (String paragraph : section.paragraphs()) {
                        writer.write(escape(paragraph));
                        writer.write(LINE_SEPARATOR);
                        wroteAny = true;
                    }
                }
                if (section.bulletPoints() != null) {
                    for (String bullet : section.bulletPoints()) {
                        writer.write(escape(bullet));
                        writer.write(LINE_SEPARATOR);
                        wroteAny = true;
                    }
                }
            }
        }

        if (!wroteAny && content.rawText() != null && !content.rawText().isEmpty()) {
            String[] lines = content.rawText().split("\\r?\\n", -1);
            for (String line : lines) {
                writer.write(escape(line));
                writer.write(LINE_SEPARATOR);
            }
        }
    }

    /**
     * CSV-quotes a single field: wraps it in double quotes and doubles any
     * embedded quotes if the value contains a comma, double quote, or
     * newline; returns it unchanged otherwise. Null values render as empty.
     *
     * <p>SEC-046: document content is model/user supplied, so a cell starting
     * with {@code = + - @}, tab or CR is prefixed with {@code '} to stop a
     * spreadsheet from evaluating it as a formula (see {@link
     * com.agenticai.core.security.CsvCells}).
     */
    private String escape(String value) {
        return com.agenticai.core.security.CsvCells.escape(value);
    }
}
