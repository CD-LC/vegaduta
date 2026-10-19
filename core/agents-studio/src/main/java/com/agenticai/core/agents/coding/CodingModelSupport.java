package com.agenticai.core.agents.coding;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing helpers shared by the LLM fixer and reviewer: tolerant of fences/prose around the one JSON object we asked for. */
final class CodingModelSupport {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$", Pattern.MULTILINE);
    private static final Pattern TEST_PATH = Pattern.compile(
        "(^|/)(tests?|__tests__|spec|specs)/|(^|/)test_[^/]*\\.py$|_test\\.(go|py)$|[._-](test|spec)\\.[A-Za-z]+$"
            + "|(?-i:(Test|Tests|IT)\\.(java|kt))$", Pattern.CASE_INSENSITIVE);

    private CodingModelSupport() {
    }

    /** The first balanced {...} object in the text (string-aware), parsed; empty when there is none. */
    static Optional<JsonNode> extractObject(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchingBrace(text, start);
            if (end > start) {
                try {
                    JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
                    if (node != null && node.isObject()) {
                        return Optional.of(node);
                    }
                } catch (Exception ignored) {
                    // try the next opening brace
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return Optional.empty();
    }

    private static int matchingBrace(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Paths in a unified diff that look like test files (so the reviewer and the human are told). */
    static List<String> touchedTestFiles(String diff) {
        if (diff == null || diff.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = DIFF_HEADER.matcher(diff);
        while (m.find()) {
            String path = m.group(2);
            if (TEST_PATH.matcher(path).find()) {
                out.add(path);
            }
        }
        return new ArrayList<>(out);
    }

    static int countFiles(String diff) {
        if (diff == null || diff.isEmpty()) {
            return 0;
        }
        Matcher m = DIFF_HEADER.matcher(diff);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n[... clipped ...]";
    }
}
