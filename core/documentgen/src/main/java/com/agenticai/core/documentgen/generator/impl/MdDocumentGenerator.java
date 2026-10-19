package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link DocumentContent} as GitHub-flavoured Markdown. {@code MD} has been in {@link
 * DocumentType} (and in the {@code generate_document} tool's schema) since the start, but no
 * generator was ever registered for it, so every Markdown request ended FAILED with "No document
 * generator is registered for MD yet." Same precedence as {@link TxtDocumentGenerator}: {@code
 * rawText} verbatim, otherwise {@code # title} + sections ({@code ## heading}, paragraphs, {@code -
 * } bullets) + the table as a pipe table, otherwise the table alone, otherwise the title.
 */
@Component
public class MdDocumentGenerator implements DocumentGenerator {

    private static final String NL = "\n";

    @Override
    public DocumentType supportedType() {
        return DocumentType.MD;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) {
        DocumentContent content = request.content();
        if (content.rawText() != null && !content.rawText().isEmpty()) {
            return content.rawText().getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder();
        String title = content.title();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title.strip()).append(NL).append(NL);
        }
        List<DocumentContent.Section> sections = content.sections();
        if (sections != null) {
            for (DocumentContent.Section section : sections) {
                if (section == null) {
                    continue;
                }
                if (section.heading() != null && !section.heading().isBlank()) {
                    sb.append("## ").append(section.heading().strip()).append(NL).append(NL);
                }
                if (section.paragraphs() != null) {
                    for (String paragraph : section.paragraphs()) {
                        if (paragraph != null && !paragraph.isBlank()) {
                            sb.append(paragraph.strip()).append(NL).append(NL);
                        }
                    }
                }
                if (section.bulletPoints() != null && !section.bulletPoints().isEmpty()) {
                    for (String bullet : section.bulletPoints()) {
                        if (bullet != null && !bullet.isBlank()) {
                            sb.append("- ").append(bullet.strip()).append(NL);
                        }
                    }
                    sb.append(NL);
                }
            }
        }
        DocumentContent.Table table = content.table();
        if (table != null && table.headers() != null && !table.headers().isEmpty()) {
            sb.append(renderTable(table));
        }
        return sb.toString().strip().concat(NL).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A GFM pipe table; cell text has pipes and line breaks neutralised so a value cannot break the
     * grid.
     */
    static String renderTable(DocumentContent.Table table) {
        StringBuilder sb = new StringBuilder();
        List<String> headers = table.headers();
        sb.append("| ")
                .append(
                        String.join(
                                " | ", headers.stream().map(MdDocumentGenerator::cell).toList()))
                .append(" |")
                .append(NL);
        sb.append("|").append(" --- |".repeat(headers.size())).append(NL);
        if (table.rows() != null) {
            for (List<String> row : table.rows()) {
                StringBuilder line = new StringBuilder("|");
                for (int i = 0; i < headers.size(); i++) {
                    String v = row != null && i < row.size() ? row.get(i) : "";
                    line.append(' ').append(cell(v)).append(" |");
                }
                sb.append(line).append(NL);
            }
        }
        return sb.toString();
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\r", "").replace("\n", " ").replace("|", "\\|").strip();
    }
}
