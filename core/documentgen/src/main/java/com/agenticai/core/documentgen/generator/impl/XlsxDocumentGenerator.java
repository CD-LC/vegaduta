package com.agenticai.core.documentgen.generator.impl;

import com.agenticai.core.documentgen.generator.DocumentGenerator;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentType;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders a {@link DocumentContent} into XLSX bytes via Apache POI's {@link
 * XSSFWorkbook}, one sheet named after {@link
 * DocumentGenerationRequest#filename()} (or {@code "Sheet1"} if blank).
 * Prefers {@link DocumentContent#table()} when present - a bold header row
 * plus data rows below, with every used column auto-sized; otherwise falls
 * back to a single-column layout in column A: {@link DocumentContent#title()}
 * in A1, then each {@link DocumentContent#sections()} entry's heading,
 * paragraphs, and bullet points as successive rows; if there are no sections
 * either, one row per line of {@link DocumentContent#rawText()}.
 */
@Component
public class XlsxDocumentGenerator implements DocumentGenerator {

    private static final int MAX_SHEET_NAME_LENGTH = 31;

    @Override
    public DocumentType supportedType() {
        return DocumentType.XLSX;
    }

    @Override
    public byte[] generate(DocumentGenerationRequest request) throws Exception {
        DocumentContent content = request.content();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName(request.filename()));

            DocumentContent.Table table = content.table();
            int lastColumn;
            if (table != null && table.headers() != null && !table.headers().isEmpty()) {
                lastColumn = writeTable(workbook, sheet, table);
            } else {
                lastColumn = writeSingleColumnFallback(sheet, content);
            }

            for (int col = 0; col <= lastColumn; col++) {
                sheet.autoSizeColumn(col);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** @return the greatest 0-based column index written, for auto-sizing */
    private int writeTable(XSSFWorkbook workbook, Sheet sheet, DocumentContent.Table table) {
        CellStyle headerStyle = headerStyle(workbook);

        List<String> headers = table.headers();
        Row headerRow = sheet.createRow(0);
        for (int col = 0; col < headers.size(); col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(nullToEmpty(headers.get(col)));
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 1;
        List<List<String>> rows = table.rows();
        if (rows != null) {
            for (List<String> row : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                if (row == null) {
                    continue;
                }
                for (int col = 0; col < row.size(); col++) {
                    dataRow.createCell(col).setCellValue(nullToEmpty(row.get(col)));
                }
            }
        }

        return Math.max(0, headers.size() - 1);
    }

    /** @return the greatest 0-based column index written, for auto-sizing */
    private int writeSingleColumnFallback(Sheet sheet, DocumentContent content) {
        int rowIndex = 0;
        boolean wroteAny = false;

        if (content.title() != null && !content.title().isBlank()) {
            sheet.createRow(rowIndex++).createCell(0).setCellValue(content.title());
            wroteAny = true;
        }

        List<DocumentContent.Section> sections = content.sections();
        if (sections != null && !sections.isEmpty()) {
            for (DocumentContent.Section section : sections) {
                if (section.heading() != null && !section.heading().isBlank()) {
                    sheet.createRow(rowIndex++).createCell(0).setCellValue(section.heading());
                    wroteAny = true;
                }
                if (section.paragraphs() != null) {
                    for (String paragraph : section.paragraphs()) {
                        sheet.createRow(rowIndex++).createCell(0).setCellValue(nullToEmpty(paragraph));
                        wroteAny = true;
                    }
                }
                if (section.bulletPoints() != null) {
                    for (String bullet : section.bulletPoints()) {
                        sheet.createRow(rowIndex++).createCell(0).setCellValue("- " + nullToEmpty(bullet));
                        wroteAny = true;
                    }
                }
            }
        }

        if (!wroteAny && content.rawText() != null && !content.rawText().isEmpty()) {
            String[] lines = content.rawText().split("\\r?\\n", -1);
            for (String line : lines) {
                sheet.createRow(rowIndex++).createCell(0).setCellValue(line);
            }
        }

        return 0;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(boldFont);
        return style;
    }

    /** Sheet names can't be blank, exceed 31 chars, or contain {@code []:*?/\\}. */
    private String sheetName(String filename) {
        String name = StringUtils.hasText(filename) ? filename : "Sheet1";
        name = name.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (name.length() > MAX_SHEET_NAME_LENGTH) {
            name = name.substring(0, MAX_SHEET_NAME_LENGTH);
        }
        return name;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
