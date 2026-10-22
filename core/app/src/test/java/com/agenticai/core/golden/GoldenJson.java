package com.agenticai.core.golden;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.opentest4j.AssertionFailedError;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectMapper;

/**
 * Golden-file support for the P0 characterisation suites (ADR-0012).
 *
 * <p>{@link #assertMatches} serialises a value with the given mapper (pretty printed with a fixed
 * two-space, LF-only layout so the file is byte-stable across platforms), compares it TEXTUALLY
 * with {@code src/test/resources/golden/<name>.json} (so property order, number formatting and date
 * formats are all part of the contract, which a tree comparison would hide), and on a mismatch
 * fails with a readable line diff.
 *
 * <p><b>Regenerating.</b> Run the tests with {@code -Dgolden.update=true} to (re)write the files
 * instead of asserting, then review the git diff of {@code core/app/src/test/resources/golden/}:
 * every changed line is a change to the wire format that API clients can see. A missing golden file
 * fails the test unless the switch is on. See docs/engineering/CHARACTERISATION-TESTS.md.
 */
public final class GoldenJson {

    public static final String UPDATE_PROPERTY = "golden.update";
    private static final String RESOURCE_DIR = "golden/";
    private static final int MAX_DIFF_LINES = 60;

    private GoldenJson() {}

    public static boolean updateMode() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    /** Serialises {@code value} exactly as the golden file stores it (no normalisation). */
    public static String render(ObjectMapper mapper, Object value) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer =
                new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter);
        try {
            return mapper.writer().with(printer).writeValueAsString(value) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "could not serialise golden value " + value.getClass(), e);
        }
    }

    public static void assertMatches(String name, ObjectMapper mapper, Object value) {
        assertMatches(name, mapper, value, UnaryOperator.identity());
    }

    /**
     * @param normaliser applied to the rendered text before comparing, for the few values that are
     *     legitimately non-deterministic (e.g. an error body's {@code "timestamp": now}).
     */
    public static void assertMatches(
            String name, ObjectMapper mapper, Object value, UnaryOperator<String> normaliser) {
        String actual = normaliser.apply(render(mapper, value));
        if (updateMode()) {
            write(name, actual);
            return;
        }
        String expected = read(name);
        if (expected == null) {
            throw new AssertionFailedError(
                    "Golden file src/test/resources/"
                            + RESOURCE_DIR
                            + name
                            + ".json does not exist. Generate it with -Dgolden.update=true and review it.",
                    "",
                    actual);
        }
        if (!expected.equals(actual)) {
            throw new AssertionFailedError(
                    "API JSON differs from golden file "
                            + RESOURCE_DIR
                            + name
                            + ".json\n"
                            + diff(expected, actual)
                            + "\nIf this change is intended, regenerate with -Dgolden.update=true and review the git diff."
                            + " (- golden, + actual)",
                    expected,
                    actual);
        }
    }

    /**
     * Like {@link #assertMatches(String, ObjectMapper, Object)} but with object keys sorted before
     * comparing. Only for bean-style (non-record, no {@code @JsonPropertyOrder}) classes such as
     * Spring Data's {@code PageImpl}, whose property order follows reflection order and differs
     * between JVM runs. Values, nesting and key SET are still pinned.
     */
    public static void assertMatchesSortedKeys(String name, ObjectMapper mapper, Object value) {
        assertMatches(name, mapper, sortKeys(mapper, mapper.valueToTree(value)));
    }

    private static tools.jackson.databind.JsonNode sortKeys(
            ObjectMapper mapper, tools.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            tools.jackson.databind.node.ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.propertyNames().iterator().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            for (String key : names) {
                sorted.set(key, sortKeys(mapper, node.get(key)));
            }
            return sorted;
        }
        if (node.isArray()) {
            tools.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
            node.forEach(child -> array.add(sortKeys(mapper, child)));
            return array;
        }
        return node;
    }

    // ------------------------------------------------------------------ io

    private static String read(String name) {
        try (InputStream in =
                GoldenJson.class
                        .getClassLoader()
                        .getResourceAsStream(RESOURCE_DIR + name + ".json")) {
            return in == null
                    ? null
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("could not read golden " + name, e);
        }
    }

    private static void write(String name, String content) {
        Path target = Paths.get("src", "test", "resources", "golden", name + ".json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write golden " + target.toAbsolutePath(), e);
        }
    }

    // ---------------------------------------------------------------- diff

    /** Compact line diff (LCS based), enough to see exactly which lines moved or changed. */
    static String diff(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] =
                        a[i].equals(b[j])
                                ? lcs[i + 1][j + 1] + 1
                                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                i++;
                j++;
            } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.add("+ " + (j + 1) + ": " + b[j]);
                j++;
            } else {
                out.add("- " + (i + 1) + ": " + a[i]);
                i++;
            }
        }
        if (out.size() > MAX_DIFF_LINES) {
            int hidden = out.size() - MAX_DIFF_LINES;
            out = new ArrayList<>(out.subList(0, MAX_DIFF_LINES));
            out.add("... " + hidden + " more differing lines");
        }
        return String.join("\n", out);
    }
}
