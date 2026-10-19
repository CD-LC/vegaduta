package com.agenticai.core.omnichannel.crm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV import parsing and export rendering for leads. Import is header-driven
 * (column order free, synonyms accepted, comma/semicolon/tab delimiter sniffed
 * from the first line); a file whose first row carries no recognised header is
 * read positionally as {@code name,email,phone,company,title}. Quotes follow
 * RFC 4180 (doubled quote escapes, embedded newlines allowed).
 */
public final class LeadCsv {

    public static final int MAX_ROWS = 5000;

    static final List<String> EXPORT_HEADER = List.of(
        "name", "email", "phone", "company", "title", "source", "stage", "score", "owner", "value", "tags", "notes",
        "last_contacted_at", "last_replied_at", "created_at"
    );

    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();

    static {
        alias("name", "name", "fullname", "contact", "contactname", "leadname");
        alias("email", "email", "emailaddress", "mail");
        alias("phone", "phone", "mobile", "telephone", "phonenumber", "tel");
        alias("company", "company", "organization", "organisation", "account", "companyname");
        alias("title", "title", "jobtitle", "role", "position");
        alias("source", "source", "leadsource", "origin");
        alias("stage", "stage", "status");
        alias("score", "score", "leadscore");
        alias("owner", "owner", "assignedto", "salesrep");
        alias("value", "value", "dealvalue", "amount", "revenue");
        alias("tags", "tags", "labels");
        alias("notes", "notes", "note", "comments", "description");
    }

    private LeadCsv() {
    }

    private static void alias(String canonical, String... names) {
        for (String n : names) {
            HEADER_ALIASES.put(n, canonical);
        }
    }

    /** Thrown for malformed input; the controller maps it to HTTP 400. */
    public static final class CsvException extends RuntimeException {
        public CsvException(String message) {
            super(message);
        }
    }

    /** One parsed data row: its 1-based line-ish row number (header excluded) and the lead fields. */
    public record ParsedRow(int row, LeadService.LeadInput input, String error) {
    }

    public static List<ParsedRow> parse(String text) {
        if (text == null) {
            return List.of();
        }
        text = text.replace("﻿", "");
        List<List<String>> records = tokenize(text);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> first = records.get(0);
        for (int i = 0; i < first.size(); i++) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(first.get(i)));
            if (canonical != null) {
                columns.putIfAbsent(canonical, i);
            }
        }
        int start = 1;
        if (columns.isEmpty()) {
            // No recognisable header: positional name,email,phone,company,title.
            String[] positional = {"name", "email", "phone", "company", "title"};
            for (int i = 0; i < positional.length; i++) {
                columns.put(positional[i], i);
            }
            start = 0;
        }
        if (records.size() - start > MAX_ROWS) {
            throw new CsvException("More than " + MAX_ROWS + " rows - split the file and retry");
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int r = start; r < records.size(); r++) {
            List<String> rec = records.get(r);
            int rowNo = r - start + 1;
            String name = cell(rec, columns, "name");
            String email = cell(rec, columns, "email");
            if (name == null && email == null) {
                rows.add(new ParsedRow(rowNo, null, "row needs a name or an email"));
                continue;
            }
            try {
                String scoreRaw = cell(rec, columns, "score");
                String valueRaw = cell(rec, columns, "value");
                LeadStage stage = LeadStage.parse(cell(rec, columns, "stage"));
                Integer score = scoreRaw == null ? null : Integer.valueOf(scoreRaw.trim());
                BigDecimal value = valueRaw == null ? null : new BigDecimal(valueRaw.replaceAll("[,$\\s]", ""));
                String tagsRaw = cell(rec, columns, "tags");
                List<String> tags = tagsRaw == null ? null : List.of(tagsRaw.split("[;|]"));
                rows.add(new ParsedRow(rowNo, new LeadService.LeadInput(
                    name, email, cell(rec, columns, "phone"), cell(rec, columns, "company"), cell(rec, columns, "title"),
                    cell(rec, columns, "source"), stage, score, cell(rec, columns, "owner"), value, tags,
                    cell(rec, columns, "notes")
                ), null));
            } catch (IllegalArgumentException e) {
                rows.add(new ParsedRow(rowNo, null, "invalid stage, score or value"));
            }
        }
        return rows;
    }

    private static String normalizeHeader(String h) {
        return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(List<String> rec, Map<String, Integer> columns, String key) {
        Integer idx = columns.get(key);
        if (idx == null || idx >= rec.size()) {
            return null;
        }
        String v = rec.get(idx).trim();
        return v.isEmpty() ? null : v;
    }

    /** RFC 4180 tokenizer with delimiter sniffing on the first line. */
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

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    public static String export(List<Lead> leads) {
        StringBuilder sb = new StringBuilder(String.join(",", EXPORT_HEADER)).append("\r\n");
        for (Lead l : leads) {
            List<String> cols = List.of(
                nz(l.getName()), nz(l.getEmail()), nz(l.getPhone()), nz(l.getCompany()), nz(l.getTitle()),
                nz(l.getSource()), l.getStage().name(), String.valueOf(l.getScore()), nz(l.getOwner()),
                l.getValue() == null ? "" : l.getValue().toPlainString(), String.join(";", l.tagList()),
                nz(l.getNotes()), l.getLastContactedAt() == null ? "" : l.getLastContactedAt().toString(),
                l.getLastRepliedAt() == null ? "" : l.getLastRepliedAt().toString(), l.getCreatedAt().toString()
            );
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(cols.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Quote when needed and neutralise spreadsheet formula injection (= + - @ prefixes) in user-supplied text. */
    static String escape(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf(';') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
