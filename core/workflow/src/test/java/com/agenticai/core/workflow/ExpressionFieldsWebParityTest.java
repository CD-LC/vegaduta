package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The web's {@code EXPRESSION_FIELDS} (web/app/components/workflow/nodeDefs.ts) must list exactly the fields
 * {@link ExpressionFields} resolves: a key on the web but not here is an expression field that silently does
 * nothing, and one here but not on the web can never be switched to expression mode. The TypeScript is read
 * as text from the repository, so editing either side alone fails this test.
 */
class ExpressionFieldsWebParityTest {

    private static final String NODE_DEFS = "web/app/components/workflow/nodeDefs.ts";

    @Test
    void webExpressionFieldsEqualTheJavaOnes() {
        Map<String, List<String>> web = parseRecord(RepoFileLocator.read(NODE_DEFS), "EXPRESSION_FIELDS");
        assertFalse(web.isEmpty(), "could not parse EXPRESSION_FIELDS out of " + NODE_DEFS);

        Map<String, List<String>> java = new TreeMap<>();
        for (String type : ExpressionFields.types()) {
            java.put(type, ExpressionFields.paths(type));
        }
        assertEquals(new TreeMap<>(web), java,
            "web EXPRESSION_FIELDS (nodeDefs.ts) and core ExpressionFields disagree");
    }

    @Test
    void webBareFieldsAreExactlyTheJavaBareModeFields() {
        Map<String, List<String>> web = parseRecord(RepoFileLocator.read(NODE_DEFS), "BARE_EXPRESSION_FIELDS");
        assertFalse(web.isEmpty(), "could not parse BARE_EXPRESSION_FIELDS out of " + NODE_DEFS);

        Map<String, List<String>> java = new TreeMap<>();
        for (String type : ExpressionFields.types()) {
            List<String> bare = new ArrayList<>();
            for (ExpressionFields.Field f : ExpressionFields.of(type)) {
                if (f.mode() == ExpressionFields.Mode.BARE) {
                    bare.add(f.path());
                }
            }
            if (!bare.isEmpty()) {
                java.put(type, bare);
            }
        }
        assertEquals(new TreeMap<>(web), java);
    }

    @Test
    void theParserReadsTheShapeItIsGiven() {
        String ts = "export const X: Record<string, readonly string[]> = {\n"
            + "  a: [\"p\", \"q[].r\"],\n"
            + "  b_c: [\n    \"s\", // note ] with a bracket\n    \"t\",\n  ],\n"
            + "};\n";
        Map<String, List<String>> parsed = parseRecord(ts, "X");
        assertEquals(List.of("p", "q[].r"), parsed.get("a"));
        assertEquals(List.of("s", "t"), parsed.get("b_c"));
    }

    /**
     * Parses {@code export const NAME ... = { key: ["a", "b"], ... };}. A tiny scanner rather than a regex:
     * paths such as {@code headers[].value} contain brackets, and comments may too.
     */
    static Map<String, List<String>> parseRecord(String source, String name) {
        int decl = source.indexOf("const " + name);
        if (decl < 0) {
            return Map.of();
        }
        int open = source.indexOf("= {", decl);
        if (open < 0) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        int i = open + 3;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '}') {
                return out;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i = skipLine(source, i);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                String key = source.substring(s, i);
                while (i < n && source.charAt(i) != '[') {
                    i++;
                }
                i++;
                List<String> values = new ArrayList<>();
                while (i < n && source.charAt(i) != ']') {
                    char d = source.charAt(i);
                    if (d == '"') {
                        int e = source.indexOf('"', i + 1);
                        values.add(source.substring(i + 1, e));
                        i = e + 1;
                    } else if (d == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                        i = skipLine(source, i);
                    } else {
                        i++;
                    }
                }
                out.put(key, values);
            }
            i++;
        }
        return out;
    }

    private static int skipLine(String s, int from) {
        int e = s.indexOf('\n', from);
        return e < 0 ? s.length() : e + 1;
    }
}
