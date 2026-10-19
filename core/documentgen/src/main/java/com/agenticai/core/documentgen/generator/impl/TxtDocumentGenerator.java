package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentContent} into plain-text bytes. Prefers {@link
 * DocumentContent#rawText()} verbatim when present; otherwise flattens
 * {@link DocumentContent#title()}/{@link DocumentContent#sections()} into a
 * title line, a blank line, then each section's heading, paragraphs, and
 * bullet points (each "- " prefixed), with a blank line between sections. If
 * neither raw text nor sections are supplied, falls back to a simple
 * space/tab-aligned rendering of {@link DocumentContent#table()} when
 * present.
 */
@Component
public class TxtDocumentGenerator implements DocumentGenerator {

    private static final String LINE_SEPARATOR = "\n";

    @Override
    public DocumentType supportedType() {
        return DocumentType.TXT;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        DocumentContent content = request.content();

        if (content.rawText() != null && !content.rawText().isEmpty()) {
            return content.rawText().getBytes(StandardCharsets.UTF_8);
        }

        List<DocumentContent.Section> sections = content.sections();
        if (sections != null && !sections.isEmpty()) {
            return renderSections(content).getBytes(StandardCharsets.UTF_8);
        }

        DocumentContent.Table table = content.table();
        if (table != null && table.headers() != null && !table.headers().isEmpty()) {
            return renderTable(table).getBytes(StandardCharsets.UTF_8);
        }

        String title = content.title();
        if (title != null && !title.isBlank()) {
            return (title + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8);
        }

        return new byte[0];
    }

    /**
     * Title line, blank line, then for each section: heading line (if any),
     * paragraphs each on their own line, bullet points each prefixed "- ",
     * and a blank line between sections.
     */
    private String renderSections(DocumentContent content) {
        StringBuilder sb = new StringBuilder();

        String title = content.title();
        if (title != null && !title.isBlank()) {
            sb.append(title).append(LINE_SEPARATOR);
            sb.append(LINE_SEPARATOR);
        }

        List<DocumentContent.Section> sections = content.sections();
        for (int i = 0; i < sections.size(); i++) {
            DocumentContent.Section section = sections.get(i);

            if (section.heading() != null && !section.heading().isBlank()) {
                sb.append(section.heading()).append(LINE_SEPARATOR);
            }
            if (section.paragraphs() != null) {
                for (String paragraph : section.paragraphs()) {
                    sb.append(paragraph).append(LINE_SEPARATOR);
                }
            }
            if (section.bulletPoints() != null) {
                for (String bullet : section.bulletPoints()) {
                    sb.append("- ").append(bullet).append(LINE_SEPARATOR);
                }
            }

            if (i < sections.size() - 1) {
                sb.append(LINE_SEPARATOR);
            }
        }

        return sb.toString();
    }

    /**
     * Simple space/tab-aligned text rendering of a table: header row, then
     * data rows, columns separated by a tab so consumers can still see
     * alignment without computing per-column widths.
     */
    private String renderTable(DocumentContent.Table table) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.join("\t", table.headers())).append(LINE_SEPARATOR);

        List<List<String>> rows = table.rows();
        if (rows != null) {
            for (List<String> row : rows) {
                if (row == null || row.isEmpty()) {
                    sb.append(LINE_SEPARATOR);
                    continue;
                }
                sb.append(String.join("\t", row)).append(LINE_SEPARATOR);
            }
        }

        return sb.toString();
    }
}
