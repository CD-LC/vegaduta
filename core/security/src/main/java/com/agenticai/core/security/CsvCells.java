package com.agenticai.core.security;

import java.util.regex.Pattern;

/**
 * SEC-046: shared CSV cell encoding that neutralises spreadsheet formula
 * injection (CWE-1236). A cell that starts with {@code = + - @}, a tab or a
 * carriage return is interpreted as a formula by Excel / LibreOffice / Sheets
 * ({@code =HYPERLINK(...)}, {@code =cmd|' /C calc'!A0}, {@code @SUM(...)}), so
 * such a cell is prefixed with a single quote, which spreadsheets treat as
 * "text". The same approach as {@code omnichannel.crm.LeadCsv#escape}.
 *
 * <p>A cell that is a plain decimal number ({@code -5}, {@code +1.25},
 * {@code -3e4}) is left alone: it cannot carry a formula and prefixing it would
 * corrupt legitimate numeric data.
 */
public final class CsvCells {

    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?\\d{1,300}(\\.\\d{1,300})?([eE][+-]?\\d{1,4})?");

    private CsvCells() {
    }

    /** @return {@code value} with a leading {@code '} if it would otherwise be evaluated as a formula; null becomes "". */
    public static String neutraliseFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        if (FORMULA_TRIGGERS.indexOf(value.charAt(0)) < 0) {
            return value;
        }
        if (PLAIN_NUMBER.matcher(value).matches()) {
            return value;
        }
        return "'" + value;
    }

    /**
     * Formula-neutralises, then RFC 4180-quotes the cell when it contains a
     * comma, double quote, CR or LF (embedded quotes doubled). Null renders empty.
     */
    public static String escape(String value) {
        String v = neutraliseFormula(value);
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
