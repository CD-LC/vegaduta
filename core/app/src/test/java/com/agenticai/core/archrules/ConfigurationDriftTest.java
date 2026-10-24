package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

/**
 * One source of truth per configuration key. Three ways the configuration of this platform drifts, each a
 * failing test:
 *
 * <ol>
 *   <li><b>default drift</b>: a key whose default in code ({@code @Value("${key:default}")}, a
 *       {@code @ConfigurationProperties} field initialiser or defaults resource, {@code getProperty})
 *       differs from the default {@code application.yml} gives it. The yml wins in a running install and
 *       the code default in a context without it, so the two differ silently (a safety switch that is
 *       off in a test and on in production, or the reverse).
 *   <li><b>dead key</b>: a key in an {@code application*.yml} that no code reads. The operator sets it,
 *       nothing happens (it was mis-keyed, or the reader was removed).
 *   <li><b>undocumented key</b>: a key the code reads that {@code docs/configuration.md} does not name.
 * </ol>
 *
 * <p>A finding is fixed in the code or the yml. A deliberate exception goes in {@code
 * /archrules/config-drift-allowlist.txt}, one {@code rule | key | reason} line each ({@code key} may end in
 * {@code *}); an entry that no longer matches a finding fails {@link #allowlistHasNoStaleEntries()}, so the
 * list cannot outlive the thing it excuses. Source-tree based like {@link ReplicaStoreCoverageTest}: no
 * Spring context, no database.
 */
class ConfigurationDriftTest {

    private static final String ALLOWLIST = "/archrules/config-drift-allowlist.txt";
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    private static final Pattern DURATION = Pattern.compile("[-+]?\\d+(ns|us|ms|s|m|h|d)|[-+]?P.+", Pattern.CASE_INSENSITIVE);

    private static ConfigSourceModel model;
    private static Map<String, String> allowlist;
    private static final Map<String, Map<String, String>> raw = new LinkedHashMap<>();

    @BeforeAll
    static void scan() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "not run from a checkout of the repository");
        model = new ConfigSourceModel(root);
        allowlist = readAllowlist();
        raw.put("drift", driftFindings());
        raw.put("unread", unreadFindings());
        raw.put("undocumented", undocumentedFindings(root));
    }

    /** Prints every raw finding (before the allowlist): {@code java ... ConfigurationDriftTest}. */
    public static void main(String[] args) throws IOException {
        scan();
        raw.forEach((rule, findings) -> {
            System.out.println("== " + rule + " (" + findings.size() + ")");
            findings.forEach((k, v) -> System.out.println("  " + k + "  <-  " + v));
        });
    }

    // ------------------------------------------------------------------------------------------------ rules
    @Test
    void aKeyHasOneDefaultInCodeAndYml() {
        assertThat(unexcused("drift"))
                .as("keys whose default differs between application.yml and the code that reads them. Make the"
                        + " code default equal the yml default (or the other way round, taking the stricter value"
                        + " for a security setting), or allowlist the key with a reason in " + ALLOWLIST)
                .isEmpty();
    }

    @Test
    void everyYmlKeyIsReadByCode() {
        assertThat(unexcused("unread"))
                .as("keys in an application*.yml that no code reads (set them and nothing happens). Fix the key the"
                        + " code reads, or delete the yml entry, or allowlist a key the code composes at run time"
                        + " (prefix + name) with a reason in " + ALLOWLIST)
                .isEmpty();
    }

    @Test
    void everyKeyTheCodeReadsIsDocumented() {
        assertThat(unexcused("undocumented"))
                .as("keys the code reads that docs/configuration.md does not name. Add a row (key, default, what it"
                        + " does), or allowlist the key with a reason in " + ALLOWLIST)
                .isEmpty();
    }

    @Test
    void allowlistHasNoStaleEntries() {
        List<String> stale = new ArrayList<>();
        for (String entry : allowlist.keySet()) {
            String rule = entry.substring(0, entry.indexOf('|'));
            String key = entry.substring(entry.indexOf('|') + 1);
            boolean used = raw.getOrDefault(rule, Map.of()).keySet().stream().anyMatch(k -> matches(key, k));
            if (!used) {
                stale.add(entry);
            }
        }
        assertThat(stale)
                .as("allowlist entries that excuse nothing any more (delete them from " + ALLOWLIST + ")")
                .isEmpty();
    }

    @Test
    void everyAllowlistEntryNamesAKnownRuleAndGivesAReason() throws IOException {
        for (String line : lines()) {
            String[] parts = line.split("\\|", 3);
            assertThat(parts).as("'rule | key | reason': " + line).hasSize(3);
            assertThat(parts[0].trim()).as(line).isIn("drift", "unread", "undocumented");
            assertThat(parts[1].trim()).as("key in: " + line).isNotEmpty();
            assertThat(parts[2].trim()).as("a reason in: " + line).hasSizeGreaterThan(15);
        }
    }

    @Test
    void theScannerFindsWhatItClaimsToFind() {
        // Guard against the scanner going blind (a refactor of the patterns would make every rule pass vacuously).
        assertThat(model.baseYml).hasSizeGreaterThan(500);
        assertThat(model.reads.stream().filter(r -> r.kind().equals("@Value")).count()).isGreaterThan(500);
        assertThat(model.cpKeys).containsKey("app.chat.continuation.tail-chars");
        assertThat(model.cpKeys).containsKey("app.tools.output.max-chars");
        assertThat(ConfigSourceModel.effectiveDefault("${A:${B:7}}")).isEqualTo("7");
        assertThat(ConfigSourceModel.effectiveDefault("${A:}")).isEmpty();
        assertThat(ConfigSourceModel.effectiveDefault("${A}")).isNull();
        assertThat(same("PT30S", "30s")).isTrue();
        assertThat(same("2.0", "2")).isTrue();
        assertThat(same("1024", "4096")).isFalse();
        assertThat(same("a, b", "a,b")).isTrue();
    }

    // ------------------------------------------------------------------------------------------- findings
    /** key -> description, for every site whose code default differs from application.yml. */
    private static Map<String, String> driftFindings() {
        Map<String, String> out = new TreeMap<>();
        for (ConfigSourceModel.Read read : model.reads) {
            if (read.defaultValue() == null) {
                continue;
            }
            String yml = model.baseYml.get(read.key());
            String ymlDefault = yml == null ? null : ConfigSourceModel.effectiveDefault(yml);
            if (ymlDefault == null || same(read.defaultValue(), ymlDefault)) {
                continue;
            }
            out.merge(read.key(),
                    "code " + show(read.defaultValue()) + " (" + read.kind() + ", " + read.source() + ") vs yml "
                            + show(ymlDefault),
                    (a, b) -> a + "; " + b);
        }
        return out;
    }

    private static Map<String, String> unreadFindings() {
        Map<String, String> out = new TreeMap<>();
        Set<String> readable = new TreeSet<>(model.literalTokens);
        readable.addAll(model.cpKeys.keySet());
        for (Map.Entry<String, List<String>> e : model.allYml.entrySet()) {
            String key = e.getKey().replaceAll("\\[\\d+\\]", "");
            if (isFramework(key) || isRead(key, readable)) {
                continue;
            }
            out.put(key, String.join("; ", e.getValue()));
        }
        return out;
    }

    private static boolean isRead(String key, Set<String> readable) {
        if (readable.contains(key) || model.allYmlText.contains("${" + key)) {
            return true;
        }
        for (String cp : model.cpKeys.keySet()) {
            if (key.startsWith(cp + ".")) {
                return true; // a field holding a map/list/nested type binds everything beneath it
            }
        }
        return false;
    }

    private static Map<String, String> undocumentedFindings(Path root) throws IOException {
        String doc = Files.readString(root.resolve("docs").resolve("configuration.md"), StandardCharsets.UTF_8);
        Set<String> tokens = new TreeSet<>();
        Matcher m = Pattern.compile("[A-Za-z0-9_.\\-]+").matcher(doc);
        while (m.find()) {
            tokens.add(m.group().replaceAll("^\\.+|\\.+$", ""));
        }
        Map<String, String> out = new TreeMap<>();
        for (ConfigSourceModel.Read read : model.reads) {
            check(out, tokens, read.key(), read.kind() + ", " + read.source());
        }
        for (Map.Entry<String, String> e : model.cpKeys.entrySet()) {
            check(out, tokens, e.getKey(), "@ConfigurationProperties, " + e.getValue());
        }
        return out;
    }

    private static void check(Map<String, String> out, Set<String> tokens, String key, String where) {
        if (tokens.contains(key) || isFramework(key)) {
            return;
        }
        // a family documented as one row (`app.agent-runtime.bounds.<setting>`, `app.chat.continuation.nudge.<lang>`, a
        // group row such as `app.a2a.signing`) covers the keys directly beneath it
        String parent = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : key;
        if (parent.split("\\.").length >= 3 && tokens.contains(parent)) {
            return;
        }
        out.putIfAbsent(key, where);
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static boolean isFramework(String key) {
        return ConfigSourceModel.FRAMEWORK_ROOTS.contains(key.split("\\.")[0]);
    }

    private static List<String> unexcused(String rule) {
        List<String> out = new ArrayList<>();
        raw.get(rule).forEach((key, what) -> {
            boolean excused = allowlist.keySet().stream()
                    .anyMatch(entry -> entry.startsWith(rule + "|") && matches(entry.substring(rule.length() + 1), key));
            if (!excused) {
                out.add(key + "  <-  " + what);
            }
        });
        return out;
    }

    private static boolean matches(String pattern, String key) {
        return pattern.endsWith("*") ? key.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(key);
    }

    /** Equal as Spring would bind them: numbers by value, durations by length, lists ignoring blanks around commas. */
    static boolean same(String a, String b) {
        String x = normalise(a);
        String y = normalise(b);
        if (x.equals(y)) {
            return true;
        }
        if (NUMBER.matcher(x).matches() && NUMBER.matcher(y).matches()) {
            return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
        }
        if (DURATION.matcher(x).matches() && DURATION.matcher(y).matches()) {
            Duration d1 = duration(x);
            Duration d2 = duration(y);
            return d1 != null && d1.equals(d2);
        }
        return false;
    }

    private static Duration duration(String s) {
        try {
            return DurationStyle.detectAndParse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalise(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1);
        }
        t = t.replaceAll("\\s*,\\s*", ",");
        return t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false") ? t.toLowerCase() : t;
    }

    private static String show(String s) {
        String one = s.replace("\n", "\\n");
        return "'" + (one.length() > 70 ? one.substring(0, 70) + "...' (" + one.length() + " chars)" : one + "'");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("docs")) && Files.isRegularFile(dir.resolve("core").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static List<String> lines() throws IOException {
        try (InputStream in = ConfigurationDriftTest.class.getResourceAsStream(ALLOWLIST)) {
            assertThat(in).as(ALLOWLIST + " must exist").isNotNull();
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.trim().startsWith("#")) {
                    out.add(line.trim());
                }
            }
            return out;
        }
    }

    /** {@code rule|key} -> reason. */
    private static Map<String, String> readAllowlist() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines()) {
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3) {
                out.put(parts[0].trim() + "|" + parts[1].trim(), parts[2].trim());
            }
        }
        return out;
    }
}
