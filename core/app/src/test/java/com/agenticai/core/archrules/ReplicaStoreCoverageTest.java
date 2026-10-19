package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps three lists of "per-JVM state that breaks with 2+ replicas" equal, so adding a new
 * in-memory store cannot quietly leave the multi-replica story incomplete:
 *
 * <ol>
 *   <li>every {@code app.*store} property whose code default is {@code memory} (the stores that
 *       have a Valkey implementation behind a switch);
 *   <li>the properties {@code ReplicaModeSafetyCheck} inspects at boot when {@code
 *       app.replica-mode=multi};
 *   <li>the environment variables the Helm library chart sets to {@code redis} when the API runs
 *       more than one replica ({@code deploy/helm/charts/vegaduta-lib/templates/_core.tpl}).
 * </ol>
 *
 * <p>A store that is not in the safety check must be listed, with a reason, in {@code
 * replica-store-allowlist.txt} next to this class. Source-tree based (like {@link
 * ScheduledJobsAreLockedOrAnnotatedTest}): no Spring context, no database.
 */
class ReplicaStoreCoverageTest {

    private static final String CHECK_SOURCE =
            "app/src/main/java/com/agenticai/core/common/replica/ReplicaModeSafetyCheck.java";
    private static final String CORE_TPL = "deploy/helm/charts/vegaduta-lib/templates/_core.tpl";
    private static final String APP_YML = "app-config/src/main/resources/application.yml";

    /** {@code ${app.x.y-store:memory}} in an {@code @Value} or placeholder. */
    private static final Pattern JAVA_MEMORY_DEFAULT =
            Pattern.compile("\\$\\{(app\\.[a-z0-9.\\-]*?(?:-store|\\.store)):memory\\}");

    /** {@code isRedis("app.x.y-store")} in the safety check. */
    private static final Pattern CHECKED_KEY = Pattern.compile("isRedis\\(\"([^\"]+)\"\\)");

    @Test
    void everyMemoryDefaultedStoreIsCheckedAtBootOrAllowlistedWithAReason() throws IOException {
        Path core = coreRoot();
        Set<String> stores = memoryDefaultedStores(core);
        Set<String> checked = checkedKeys(core);
        Map<String, String> allowed = allowlist();

        assertThat(stores)
                .as("the scan must see the project's in-memory stores, or this guard is vacuous")
                .hasSizeGreaterThanOrEqualTo(8);

        List<String> missing = new ArrayList<>();
        for (String store : stores) {
            if (!checked.contains(store) && !allowed.containsKey(store)) {
                missing.add(store);
            }
        }
        assertThat(missing)
                .as(
                        "These stores default to a per-JVM memory implementation but are neither"
                            + " inspected by ReplicaModeSafetyCheck nor allowlisted in"
                            + " replica-store-allowlist.txt. With 2+ replicas a request landing on"
                            + " another replica would not find the state. Add an isRedis(...)"
                            + " finding to ReplicaModeSafetyCheck, set the switch in"
                            + " deploy/helm/charts/vegaduta-lib/templates/_core.tpl, or allowlist"
                            + " it with a reason. Missing")
                .isEmpty();

        List<String> stale = new ArrayList<>();
        for (String key : allowed.keySet()) {
            if (!stores.contains(key)) {
                stale.add(key);
            }
        }
        assertThat(stale)
                .as("replica-store-allowlist.txt lists keys that are no longer in-memory stores")
                .isEmpty();
    }

    @Test
    void everyStoreTheSafetyCheckNamesIsSwitchedToRedisByTheHelmChart() throws IOException {
        Path core = coreRoot();
        Path tpl = core.resolveSibling(CORE_TPL);
        assumeTrue(Files.isRegularFile(tpl), "deploy/ is not part of this checkout");

        String template = Files.readString(tpl, StandardCharsets.UTF_8);
        Map<String, String> envByKey = envNames(core);
        List<String> unwired = new ArrayList<>();
        Set<String> checked = checkedKeys(core);
        assertThat(checked)
                .as("the safety check must name its stores")
                .hasSizeGreaterThanOrEqualTo(8);
        for (String key : checked) {
            String env = envByKey.getOrDefault(key, relaxedEnvName(key));
            if (!template.contains(env + ": \"redis\"")) {
                unwired.add(key + " (" + env + ")");
            }
        }
        assertThat(unwired)
                .as(
                        "ReplicaModeSafetyCheck inspects these stores but vegaduta-lib/_core.tpl"
                            + " never sets them to redis for a multi-replica API. Add the env var"
                            + " under the `if $multi` block with a replicaReadiness flag (and"
                            + " values.yaml). Unwired")
                .isEmpty();
    }

    @Test
    void extractorsReadTheSourceShapesTheProjectUses() {
        Matcher java =
                JAVA_MEMORY_DEFAULT.matcher(
                        "@Value(\"${app.chat.resumable-streams.store:memory}\") String s,"
                                + " @Value(\"${app.x.max-stored-bytes:5}\") long b,"
                                + " @Value(\"${app.tools.effect-journal.store:memory}\") String e");
        List<String> keys = new ArrayList<>();
        while (java.find()) {
            keys.add(java.group(1));
        }
        assertThat(keys)
                .containsExactly(
                        "app.chat.resumable-streams.store", "app.tools.effect-journal.store");

        Matcher checked =
                CHECKED_KEY.matcher(
                        "if (!isRedis(\"app.a2a.task-store\")) {}"
                                + " isRedis(\"app.abuse.throttle-store\")");
        List<String> found = new ArrayList<>();
        while (checked.find()) {
            found.add(checked.group(1));
        }
        assertThat(found).containsExactly("app.a2a.task-store", "app.abuse.throttle-store");

        assertThat(relaxedEnvName("app.abuse.throttle-store"))
                .isEqualTo("APP_ABUSE_THROTTLE_STORE");
    }

    // ------------------------------------------------------------------ helpers

    static String relaxedEnvName(String propertyKey) {
        return propertyKey.replace('.', '_').replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    /** Stores whose default is memory, from {@code @Value} defaults and from application.yml. */
    private static Set<String> memoryDefaultedStores(Path core) throws IOException {
        Set<String> out = new TreeSet<>(envNames(core).keySet());
        try (Stream<Path> modules = Files.list(core)) {
            for (Path module : (Iterable<Path>) modules::iterator) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file :
                            (Iterable<Path>)
                                    files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                        Matcher m =
                                JAVA_MEMORY_DEFAULT.matcher(
                                        Files.readString(file, StandardCharsets.UTF_8));
                        while (m.find()) {
                            out.add(m.group(1));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Flattened application.yml: property key to the env var in its {@code ${ENV:memory}}
     * placeholder, for the memory-defaulted store keys only.
     */
    private static Map<String, String> envNames(Path core) throws IOException {
        Path yml = core.resolve(APP_YML);
        Map<String, String> out = new TreeMap<>();
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean factory =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        factory.setResources(new org.springframework.core.io.FileSystemResource(yml));
        java.util.Properties props = factory.getObject();
        Pattern placeholder = Pattern.compile("\\$\\{([A-Z0-9_]+):memory\\}");
        if (props != null) {
            for (String key : props.stringPropertyNames()) {
                if (!(key.endsWith("-store") || key.endsWith(".store"))) {
                    continue;
                }
                Matcher m = placeholder.matcher(props.getProperty(key));
                if (m.matches()) {
                    out.put(key, m.group(1));
                }
            }
        }
        return out;
    }

    private static Set<String> checkedKeys(Path core) throws IOException {
        String source = Files.readString(core.resolve(CHECK_SOURCE), StandardCharsets.UTF_8);
        Set<String> out = new TreeSet<>();
        Matcher m = CHECKED_KEY.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** {@code key # reason} lines of replica-store-allowlist.txt; a missing reason is a failure. */
    private static Map<String, String> allowlist() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (var in =
                ReplicaStoreCoverageTest.class.getResourceAsStream("replica-store-allowlist.txt")) {
            assertThat(in).as("replica-store-allowlist.txt must sit next to this test").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int hash = trimmed.indexOf('#');
                assertThat(hash)
                        .as("allowlist line needs a '# reason': " + trimmed)
                        .isGreaterThan(0);
                String reason = trimmed.substring(hash + 1).strip();
                assertThat(reason)
                        .as("allowlist line has an empty reason: " + trimmed)
                        .isNotEmpty();
                out.put(trimmed.substring(0, hash).strip(), reason);
            }
        }
        return out;
    }

    private static Path coreRoot() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (isCoreRoot(p)) {
                return p;
            }
            if (isCoreRoot(p.resolve("core"))) {
                return p.resolve("core");
            }
        }
        throw new IllegalStateException("cannot find the core/ source tree from " + cwd);
    }

    private static boolean isCoreRoot(Path dir) {
        return Files.isDirectory(dir.resolve("app").resolve("src"))
                && Files.isDirectory(dir.resolve("tenancy").resolve("src"));
    }
}
