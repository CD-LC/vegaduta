package com.agenticai.core.tables;

import com.agenticai.core.json.JsonMappers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * CSV import parsing and export rendering for data tables. Quotes follow RFC 4180 (doubled quote
 * escapes, embedded newlines allowed); the delimiter (comma, semicolon or tab) is sniffed from the
 * first line. Pure.
 *
 * <p>Round trip: {@link #export} neutralises spreadsheet-formula injection by prefixing a single
 * quote to TEXT cells that start with {@code = + - @ TAB CR} (number/boolean/date/json cells are
 * never touched, so a negative number stays a number), and {@link #parse}'s consumers call {@link
 * #unneutralize} to drop exactly that prefix again - so export followed by import returns the
 * original text. JSON cells holding a bare string are treated like text. A genuine text value that
 * itself starts with a quote and a formula character is exported with a second quote, so the
 * convention is a bijection and nothing collides.
 */
public final class DataTableCsv {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String FORMULA_CHARS = "=+-@\t\r";

    private DataTableCsv() {}

    /** Thrown for malformed or over-cap input; the controller maps it to HTTP 400. */
    public static final class CsvException extends IllegalArgumentException {
        public CsvException(String message) {
            super(message);
        }
    }

    /** A parsed file: its header row and the data rows (every row as raw strings). */
    public record Parsed(List<String> header, List<List<String>> rows) {}

    /** The outcome of mapping parsed rows onto a table's columns. */
    public record Mapped(
            List<Map<String, Object>> rows,
            List<String> errors,
            List<String> ignoredHeaders,
            int skipped) {}

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    public static Parsed parse(String text, int maxRows) {
        String t = text == null ? "" : text.replace("\uFEFF", "");
        List<List<String>> records = tokenize(t);
        if (records.isEmpty()) {
            throw new CsvException("The file is empty - the first row must be the column headers");
        }
        List<String> header = new ArrayList<>();
        for (String h : records.get(0)) {
            header.add(h == null ? "" : h.trim());
        }
        if (records.size() - 1 > maxRows) {
            throw new CsvException("More than " + maxRows + " rows - split the file and retry");
        }
        return new Parsed(header, new ArrayList<>(records.subList(1, records.size())));
    }

    /**
     * Maps parsed rows onto the table's existing columns by header (case-insensitive). Headers that
     * match no column are ignored (and listed); an empty cell is "no value"; a row with a cell that
     * fails its column type is skipped and reported ("Row N: ..."), the rest still import.
     */
    public static Mapped toRows(List<DataTableColumn> columns, Parsed parsed, int maxCellBytes) {
        DataTableColumn[] byIndex = new DataTableColumn[parsed.header().size()];
        List<String> ignored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < byIndex.length; i++) {
            String h = parsed.header().get(i);
            DataTableColumn col = DataTableValidator.find(columns, h);
            if (col == null || !seen.add(col.name())) {
                if (!h.isEmpty()) {
                    ignored.add(h);
                }
                continue;
            }
            byIndex[i] = col;
        }
        boolean any = false;
        for (DataTableColumn c : byIndex) {
            any |= c != null;
        }
        if (!any) {
            throw new CsvException("None of the header names match a column of this table");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        int rowNo = 0;
        for (List<String> rec : parsed.rows()) {
            rowNo++;
            Map<String, Object> row = new LinkedHashMap<>();
            try {
                for (int i = 0; i < byIndex.length && i < rec.size(); i++) {
                    if (byIndex[i] == null) {
                        continue;
                    }
                    String cell = rec.get(i);
                    if (cell == null || cell.isEmpty()) {
                        continue;
                    }
                    boolean guarded =
                            byIndex[i].type() == DataTableColumnType.TEXT
                                    || byIndex[i].type() == DataTableColumnType.JSON;
                    String value = guarded ? unneutralize(cell) : cell;
                    Object coerced = DataTableValidator.coerce(byIndex[i], value, maxCellBytes);
                    if (coerced != null) {
                        row.put(byIndex[i].name(), coerced);
                    }
                }
            } catch (IllegalArgumentException e) {
                skipped++;
                if (errors.size() < 50) {
                    errors.add("Row " + rowNo + ": " + e.getMessage());
                }
                continue;
            }
            if (row.isEmpty()) {
                skipped++;
                continue;
            }
            rows.add(row);
        }
        return new Mapped(rows, errors, ignored, skipped);
    }

    /**
     * Builds a column list from a CSV header and its values: names are sanitised to the column
     * grammar and made unique, types are inferred from every value (number, boolean, date or else
     * text). Used to create a table straight from a file.
     */
    public static List<DataTableColumn> inferColumns(Parsed parsed, int maxColumns) {
        List<String> header = parsed.header();
        if (header.size() > maxColumns) {
            throw new CsvException(
                    "The file has " + header.size() + " columns - the limit is " + maxColumns);
        }
        List<DataTableColumn> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String name = sanitizeName(header.get(i), i + 1);
            String unique = name;
            int n = 2;
            while (!used.add(unique.toLowerCase(Locale.ROOT))) {
                String suffix = "_" + n++;
                unique =
                        name.substring(
                                        0,
                                        Math.min(
                                                name.length(),
                                                DataTableColumn.MAX_NAME_LENGTH - suffix.length()))
                                + suffix;
            }
            out.add(new DataTableColumn(unique, inferType(parsed.rows(), i)));
        }
        return out;
    }

    static String sanitizeName(String header, int position) {
        String s =
                header == null
                        ? ""
                        : header.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) {
            return "column_" + position;
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "c_" + s;
        }
        return s.length() > DataTableColumn.MAX_NAME_LENGTH
                ? s.substring(0, DataTableColumn.MAX_NAME_LENGTH)
                : s;
    }

    private static DataTableColumnType inferType(List<List<String>> rows, int index) {
        boolean number = true;
        boolean bool = true;
        boolean date = true;
        int seen = 0;
        for (List<String> rec : rows) {
            if (index >= rec.size()) {
                continue;
            }
            String v = rec.get(index) == null ? "" : rec.get(index).trim();
            if (v.isEmpty()) {
                continue;
            }
            seen++;
            number &= looksNumeric(v);
            bool &= v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false");
            date &= looksDate(v);
            if (!number && !bool && !date) {
                return DataTableColumnType.TEXT;
            }
        }
        if (seen == 0) {
            return DataTableColumnType.TEXT;
        }
        if (bool) {
            return DataTableColumnType.BOOLEAN;
        }
        if (number) {
            return DataTableColumnType.NUMBER;
        }
        if (date) {
            return DataTableColumnType.DATE;
        }
        return DataTableColumnType.TEXT;
    }

    /**
     * Plain decimal numbers only; leading zeros ("007", phone numbers) and very long digit runs
     * stay text.
     */
    private static boolean looksNumeric(String v) {
        if (!v.matches("-?\\d+(\\.\\d+)?")) {
            return false;
        }
        String digits = v.startsWith("-") ? v.substring(1) : v;
        if (digits.length() > 15) {
            return false;
        }
        return !(digits.length() > 1 && digits.charAt(0) == '0' && digits.charAt(1) != '.');
    }

    private static boolean looksDate(String v) {
        try {
            LocalDate.parse(v);
            return true;
        } catch (DateTimeParseException ignored) {
            // not date-only
        }
        try {
            Instant.parse(v);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    /** Drops the single quote {@link #export} adds in front of a formula-looking text cell. */
    static String unneutralize(String cell) {
        // Mirror of needsGuard: strip one leading quote exactly when what remains would itself have
        // needed the guard,
        // so '=x -> =x and ''=x -> '=x (a genuine value that starts with a quote and a formula
        // character).
        if (cell.length() > 1 && cell.charAt(0) == '\'' && needsGuard(cell.substring(1))) {
            return cell.substring(1);
        }
        return cell;
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    public static String export(List<DataTableColumn> columns, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(columns.get(i).name(), true));
        }
        sb.append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                DataTableColumn col = columns.get(i);
                Object value = row.get(col.name());
                // TEXT cells and JSON cells holding a bare string are user-controlled text;
                // objects, arrays, numbers
                // and booleans serialise to JSON and are never a formula.
                boolean userText =
                        col.type() == DataTableColumnType.TEXT
                                || (col.type() == DataTableColumnType.JSON
                                        && value instanceof String);
                sb.append(escape(cell(col, value), userText));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String cell(DataTableColumn col, Object value) {
        if (value == null) {
            return "";
        }
        if (col.type() == DataTableColumnType.JSON) {
            try {
                return value instanceof String s ? s : MAPPER.writeValueAsString(value);
            } catch (JacksonException e) {
                return "";
            }
        }
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    /**
     * A cell needs the quote prefix when it starts with a formula character, or when it already
     * starts with a quote followed by one (so the import-side strip of a single leading quote
     * restores the original exactly: the guard is a bijection and a genuine {@code '=x} value
     * round-trips as {@code ''=x}).
     */
    private static boolean needsGuard(String v) {
        if (v.isEmpty()) {
            return false;
        }
        if (FORMULA_CHARS.indexOf(v.charAt(0)) >= 0) {
            return true;
        }
        return v.length() > 1 && v.charAt(0) == '\'' && FORMULA_CHARS.indexOf(v.charAt(1)) >= 0;
    }

    static String escape(String value, boolean neutralizeFormulas) {
        String v = value;
        if (neutralizeFormulas && needsGuard(v)) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0
                || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0
                || v.indexOf('\r') >= 0
                || v.indexOf(';') >= 0
                || v.indexOf('\t') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Tokenizer (RFC 4180, delimiter sniffed on the first line)
    // ------------------------------------------------------------------

    static List<List<String>> tokenize(String text) {
        char delim = sniffDelimiter(text);
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                any = true;
            } else if (c == delim) {
                current.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || field.length() > 0) {
                    current.add(field.toString());
                    records.add(current);
                }
                current = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    private static char sniffDelimiter(String text) {
        int nl = text.indexOf('\n');
        String head = nl < 0 ? text : text.substring(0, nl);
        int commas = count(head, ',');
        int semis = count(head, ';');
        int tabs = count(head, '\t');
        if (tabs > commas && tabs >= semis) {
            return '\t';
        }
        return semis > commas ? ';' : ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
