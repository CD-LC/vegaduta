package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settings-catalog key that nothing reads is a lie to the person who sets it: the page accepts the override, stores it,
 * and the code keeps doing what the platform property says. Five keys shipped like that once ({@code delegation.*},
 * {@code quality.*}); this guard makes it impossible to ship another.
 *
 * <p>Every key of the catalog (the committed snapshot {@code web/app/lib/api/fixtures/settingsCatalog.snapshot.json},
 * which {@link SettingsCatalogContractTest} keeps equal to the assembled catalog) must have a <b>call site</b> in main code:
 * a source file under {@code core/*}{@code /src/main/java} that, outside comments, names the key (as the literal string or
 * through a {@code static final String} constant holding it) in a place that reads it. In a {@code SettingsContributor}
 * file only a line that calls the resolver counts, because the definition itself names the key too. A key read through a
 * generic mechanism that never names it is listed in {@link #GENERIC_MECHANISM} with the reason; an entry that turns out to
 * be wired directly, or whose key left the catalog, fails the test so the list cannot rot.
 */
class SettingsWiringGuardTest {

    /** Keys read by a mechanism that builds the key at runtime, so no source line names it. Each with the reason. */
    private static final Map<String, String> GENERIC_MECHANISM = Map.of(
            "agent.limits.maxToolSteps", agentLimits(),
            "agent.limits.turnTimeoutSeconds", agentLimits(),
            "agent.limits.toolTimeoutSeconds", agentLimits(),
            "agent.limits.toolOutputMaxChars", agentLimits(),
            "agent.limits.retrievalTopK", agentLimits(),
            "agent.limits.retrievalMinScore", agentLimits(),
            "agent.limits.continuationPasses", agentLimits());

    private static String agentLimits() {
        return "The seven runtime limits are keyed off the RuntimeSetting enum (prefix + apiName in RuntimeLimitsSettings) and "
                + "read through AgentRuntimeSettingsResolver, a SettingBackend over tenant_runtime_limits and the agent table; "
                + "the turn reads the resolved AgentRuntimeSettings, never the key.";
    }

    private static final Pattern CONSTANT = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"\\s*;");
    private static final Pattern RESOLVER_CALL =
            Pattern.compile("\\.(get|getInt|getLong|getDecimal|getBool|getString|getDuration|getEnum)\\s*\\(");

    // ---------------------------------------------------------------------------------------------- the guard

    @Test
    void everyCatalogKeyHasACallSiteInMainCode() throws IOException {
        Path root = repoRoot();
        Set<String> keys = catalogKeys(root);
        Scanner scanner = new Scanner(root.resolve("core"));

        List<String> unwired = new ArrayList<>();
        for (String key : keys) {
            if (!GENERIC_MECHANISM.containsKey(key) && scanner.callSites(key).isEmpty()) {
                unwired.add(key);
            }
        }
        assertThat(unwired)
                .as("catalog keys with no resolver call site in main code. Read the key through the SettingsResolver (or a "
                        + "module accessor that does) where the platform value is used today, with the real request's tenant "
                        + "and agent; a key read by a generic mechanism goes in GENERIC_MECHANISM with its reason")
                .isEmpty();
    }

    @Test
    void theGenericMechanismListHoldsOnlyKeysThatAreInTheCatalogAndReallyHaveNoDirectCallSite() throws IOException {
        Path root = repoRoot();
        Set<String> keys = catalogKeys(root);
        Scanner scanner = new Scanner(root.resolve("core"));

        assertThat(GENERIC_MECHANISM.keySet()).as("allowlisted keys that left the catalog").isSubsetOf(keys);
        List<String> redundant = GENERIC_MECHANISM.keySet().stream().filter(k -> !scanner.callSites(k).isEmpty()).toList();
        assertThat(redundant).as("allowlisted keys that now have a direct call site: remove them from GENERIC_MECHANISM").isEmpty();
        GENERIC_MECHANISM.values().forEach(reason -> assertThat(reason).isNotBlank());
    }

    // ---------------------------------------------------------------------------------------------- the scanner is itself tested

    @Test
    void aKeyOnlyTheContributorNamesHasNoCallSite(@TempDir Path dir) throws IOException {
        module(dir, "Settings.java", """
                package a;
                public class Settings implements SettingsContributor {
                    public static final String LIMIT = "x.limit";
                    public List<SettingDefinition> definitions() {
                        return List.of(SettingDefinition.builder(LIMIT, "g", INT).platform(props::getLimit).build(),
                                SettingDefinition.builder("x.other", "g", INT).build());
                    }
                }
                """);
        module(dir, "Tool.java", """
                package a;
                class Tool {
                    // reads x.limit in the docs only, and x.other too
                    int cap() { return props.getLimit(); }
                }
                """);

        Scanner scanner = new Scanner(dir);

        assertThat(scanner.callSites("x.limit")).isEmpty();
        assertThat(scanner.callSites("x.other")).isEmpty();
    }

    @Test
    void aResolverCallThroughTheLiteralTheConstantOrAContributorsOwnAccessorIsACallSite(@TempDir Path dir) throws IOException {
        module(dir, "Keys.java", """
                package a;
                public final class Keys {
                    public static final String A = "x.a";
                    public static final String B = "x.b";
                }
                """);
        module(dir, "ByLiteral.java", """
                package a;
                class ByLiteral { int n() { return settings.getInt("x.lit", SettingContext.tenant()); } }
                """);
        module(dir, "ByConstant.java", """
                package a;
                class ByConstant { int n() { return settings.getInt(Keys.A, SettingContext.forAgent(id)); } }
                """);
        module(dir, "Own.java", """
                package a;
                public class Own implements SettingsContributor {
                    static final String C = "x.c";
                    public List<SettingDefinition> definitions() { return List.of(SettingDefinition.builder(C, "g", INT).build()); }
                    static long cooldown(SettingsResolver settings) { return settings.getLong(C, SettingContext.tenant()); }
                }
                """);

        Scanner scanner = new Scanner(dir);

        assertThat(scanner.callSites("x.lit")).containsExactly("ByLiteral.java");
        assertThat(scanner.callSites("x.a")).containsExactly("ByConstant.java");
        assertThat(scanner.callSites("x.c")).containsExactly("Own.java");
        assertThat(scanner.callSites("x.b")).as("a constant nobody uses").isEmpty();
    }

    @Test
    void aMentionInACommentOrInTestCodeIsNoCallSite(@TempDir Path dir) throws IOException {
        module(dir, "Doc.java", """
                package a;
                /** Reads {@code settings.getInt("x.doc", ctx)} someday. */
                class Doc {
                    // settings.getInt("x.doc", SettingContext.tenant());
                    /* settings.getInt("x.doc", SettingContext.tenant()); */
                }
                """);
        Path test = dir.resolve("m/src/test/java/a");
        Files.createDirectories(test);
        Files.writeString(test.resolve("T.java"), "class T { int n() { return settings.getInt(\"x.doc\", SettingContext.tenant()); } }");

        assertThat(new Scanner(dir).callSites("x.doc")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- scanner

    /** Main-code call sites of catalog keys under {@code <core>/*}{@code /src/main/java}. */
    static final class Scanner {
        private final Map<String, String> sources = new LinkedHashMap<>(); // file name -> code without comments
        private final Map<String, List<String[]>> constantsByValue = new LinkedHashMap<>(); // value -> [class, NAME]

        Scanner(Path core) throws IOException {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(core)) {
                files = walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                        .toList();
            }
            for (Path f : files) {
                String code = stripComments(Files.readString(f));
                sources.put(f.toString(), code);
                Matcher m = CONSTANT.matcher(code);
                while (m.find()) {
                    constantsByValue.computeIfAbsent(m.group(2), v -> new ArrayList<>())
                            .add(new String[] {className(f.toString()), m.group(1)});
                }
            }
        }

        /** The simple file names of the main sources that read {@code key} through the resolver or a module accessor. */
        List<String> callSites(String key) {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                String file = e.getKey();
                String code = e.getValue();
                boolean contributor = code.contains("SettingsContributor");
                for (String line : code.split("\n")) {
                    if (!namesKey(file, code, line, key)) {
                        continue;
                    }
                    if (line.contains("SettingDefinition.builder(") || CONSTANT.matcher(line).find()) {
                        continue; // the definition and the constant's own declaration
                    }
                    if (contributor && !RESOLVER_CALL.matcher(line).find()) {
                        continue; // a contributor names its keys to define them; only a resolver call reads one
                    }
                    out.add(Path.of(file).getFileName().toString());
                    break;
                }
            }
            return List.copyOf(out);
        }

        private boolean namesKey(String file, String code, String line, String key) {
            if (line.contains("\"" + key + "\"")) {
                return true;
            }
            for (String[] c : constantsByValue.getOrDefault(key, List.of())) {
                String cls = c[0];
                String name = c[1];
                boolean ownFile = className(file).equals(cls);
                Pattern qualified = Pattern.compile("\\b" + Pattern.quote(cls) + "\\." + Pattern.quote(name) + "\\b");
                if (qualified.matcher(line).find()) {
                    return true;
                }
                boolean staticImport = code.contains("." + cls + "." + name + ";") || code.contains("." + cls + ".*;");
                if ((ownFile || staticImport) && Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line).find()) {
                    return true;
                }
            }
            return false;
        }

        private static String className(String file) {
            String n = Path.of(file).getFileName().toString();
            return n.substring(0, n.length() - ".java".length());
        }

        private static String stripComments(String source) {
            return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//.*$", "").replaceAll("(?m)\\s//(?=[^\"\\n]*$).*$", "");
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static void module(Path dir, String fileName, String source) throws IOException {
        Path main = dir.resolve("m/src/main/java/a");
        Files.createDirectories(main);
        Files.writeString(main.resolve(fileName), source);
    }

    private static Set<String> catalogKeys(Path root) throws IOException {
        JsonNode snapshot = new ObjectMapper().readTree(
                Files.readString(root.resolve("web/app/lib/api/fixtures/settingsCatalog.snapshot.json")));
        Set<String> keys = new LinkedHashSet<>();
        snapshot.get("entries").forEach(e -> keys.add(e.get("key").asText()));
        assertThat(keys).as("the snapshot lists the catalog").hasSizeGreaterThan(30);
        return keys;
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("web/app/lib/api/fixtures/settingsCatalog.snapshot.json"))) {
            root = root.getParent();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "not run from a checkout of the repository");
        return root;
    }
}
