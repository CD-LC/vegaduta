package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for the reflection-based OpenAPI generator: structural checks over the REAL controller set
 * (scanned from the classpath, no Spring context, no database), the small helpers, and a "generate"
 * mode that writes {@code docs/api/openapi.json}. The golden-small fixture check is {@link
 * OpenApiFixtureSpecTest}.
 *
 * <p>Regenerate the committed spec with {@code mvn -pl app -am test -Dtest=OpenApiSpecGeneratorTest
 * -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false} from {@code core/} (JDK 24).
 */
class OpenApiSpecGeneratorTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final Pattern BRACES = Pattern.compile("\\{([^/{}]+)}");
    private static final List<String> VERBS =
            List.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    private static String fullJson;
    private static JsonNode fullDoc;

    private static synchronized JsonNode full() throws IOException {
        if (fullDoc == null) {
            fullJson = new OpenApiSpecGenerator("9.9.9").generateJson();
            fullDoc = MAPPER.readTree(fullJson);
        }
        return fullDoc;
    }

    // ================================================================== real controllers

    @Test
    void fullSpecIsValidJsonWithExpectedHeader() throws IOException {
        JsonNode doc = full();
        assertThat(doc.path("openapi").asString()).startsWith("3.1");
        assertThat(doc.path("info").path("title").asString()).isNotBlank();
        assertThat(doc.path("info").path("version").asString()).isEqualTo("9.9.9");
        assertThat(doc.path("info").path("x-skipped").isArray()).isTrue();
        assertThat(doc.path("components").path("schemas").has("Error")).isTrue();
        assertThat(doc.path("components").path("securitySchemes").has(OpenApiSecurity.BEARER))
                .isTrue();
        assertThat(fullJson).doesNotContain("\r").endsWith("}\n");
    }

    @Test
    void fullSpecHasManyOperationsWithUniqueIds() throws IOException {
        JsonNode doc = full();
        int operations = 0;
        Set<String> ids = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
                paths.hasNext(); ) {
            JsonNode item = paths.next().getValue();
            for (String verb : VERBS) {
                if (!item.has(verb)) {
                    continue;
                }
                operations++;
                String id = item.path(verb).path("operationId").asString();
                assertThat(id).isNotBlank();
                if (!ids.add(id)) {
                    duplicates.add(id);
                }
            }
        }
        assertThat(operations).as("operations in the generated spec").isGreaterThanOrEqualTo(200);
        assertThat(duplicates).as("duplicate operationIds").isEmpty();
    }

    @Test
    void everyRefResolves() throws IOException {
        JsonNode doc = full();
        List<String> unresolved = new ArrayList<>();
        collectUnresolvedRefs(doc, doc, unresolved);
        assertThat(unresolved).as("$ref values that do not resolve").isEmpty();
    }

    private static void collectUnresolvedRefs(JsonNode root, JsonNode node, List<String> out) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isString()) {
                String r = ref.asString();
                String prefix = "#/components/";
                boolean ok = false;
                if (r.startsWith(prefix)) {
                    String[] parts = r.substring(prefix.length()).split("/", 2);
                    ok =
                            parts.length == 2
                                    && List.of("schemas", "responses", "headers", "parameters")
                                            .contains(parts[0])
                                    && root.path("components").path(parts[0]).has(parts[1]);
                }
                if (!ok) {
                    out.add(r);
                }
            }
            for (Iterator<JsonNode> it = node.values().iterator(); it.hasNext(); ) {
                collectUnresolvedRefs(root, it.next(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectUnresolvedRefs(root, child, out);
            }
        }
    }

    @Test
    void pathsHaveBalancedBracesAndDeclareEveryPathVariable() throws IOException {
        JsonNode doc = full();
        List<String> problems = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
                paths.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = paths.next();
            String path = entry.getKey();
            if (!path.startsWith("/")) {
                problems.add("path does not start with /: " + path);
            }
            long open = path.chars().filter(c -> c == '{').count();
            long close = path.chars().filter(c -> c == '}').count();
            if (open != close) {
                problems.add("unmatched braces: " + path);
            }
            Set<String> template = new TreeSet<>();
            Matcher m = BRACES.matcher(path);
            while (m.find()) {
                template.add(m.group(1));
            }
            for (String verb : VERBS) {
                JsonNode op = entry.getValue().path(verb);
                if (op.isMissingNode()) {
                    continue;
                }
                Set<String> declared = new TreeSet<>();
                for (JsonNode p : op.path("parameters")) {
                    if ("path".equals(p.path("in").asString())) {
                        declared.add(p.path("name").asString());
                    }
                }
                if (!declared.equals(template)) {
                    problems.add(
                            verb
                                    + " "
                                    + path
                                    + " declares "
                                    + declared
                                    + " but template has "
                                    + template);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void securityFollowsTheDerivedRules() throws IOException {
        JsonNode paths = full().path("paths");
        JsonNode signup = paths.path("/api/signup").path("post");
        if (!signup.isMissingNode()) {
            assertThat(signup.path("security").isArray()).isTrue();
            assertThat(signup.path("security").size()).isZero();
        }
        JsonNode agents = paths.path("/api/agents").path("get");
        if (!agents.isMissingNode()) {
            assertThat(agents.path("security").get(0).has(OpenApiSecurity.BEARER)).isTrue();
        }
        JsonNode dev = paths.path("/api/dev/v1/agents").path("get");
        if (!dev.isMissingNode()) {
            assertThat(dev.path("security").get(0).has(OpenApiSecurity.API_KEY)).isTrue();
        }
    }

    @Test
    void generationIsDeterministic() throws IOException {
        full();
        String again = new OpenApiSpecGenerator("9.9.9").generateJson();
        assertThat(again).isEqualTo(fullJson);
    }

    /**
     * Every curated description key must still name a scanned operation, so a moved or renamed
     * route cannot silently drop its prose; and the prose must land in that operation's
     * description.
     */
    @Test
    void curatedDescriptionsMatchRealOperations() throws IOException {
        JsonNode paths = full().path("paths");
        List<String> missing = new ArrayList<>();
        for (String key : OpenApiDescriptions.keys()) {
            int space = key.indexOf(' ');
            String verb = key.substring(0, space).toLowerCase(java.util.Locale.ROOT);
            String path = key.substring(space + 1);
            JsonNode op = paths.path(path).path(verb);
            if (op.isMissingNode()) {
                missing.add(key);
                continue;
            }
            assertThat(op.path("description").asString())
                    .as("description of %s", key)
                    .startsWith(OpenApiDescriptions.describe(verb, path));
        }
        assertThat(missing).as("curated description keys with no scanned operation").isEmpty();
    }

    @Test
    void sessionRoutesDescribeOwnershipAndFlags() throws IOException {
        JsonNode paths = full().path("paths");
        String base = "/api/agents/{agentId}/sessions/{sessionId}";
        String chat = "/api/agents/{agentId}/chat/sessions/{sessionId}";
        for (String route :
                List.of(
                        "get " + base + "/transcript",
                        "get " + base + "/export",
                        "post " + chat + "/rewind",
                        "post " + chat + "/cancel",
                        "get " + base + "/messages",
                        "get " + base + "/messages/{messageId}/siblings",
                        "put " + base + "/messages/active-leaf",
                        "post " + base + "/messages/{messageId}/edit",
                        "post " + base + "/messages/{messageId}/regenerate")) {
            String verb = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            String description = paths.path(path).path(verb).path("description").asString();
            assertThat(description)
                    .as("%s", route)
                    .contains("only by the caller who started the session")
                    .contains("tenant + agent + user + client session id")
                    .contains("404");
            if (path.endsWith("/export")) {
                assertThat(description).contains("CHAT_EXPORT_ENABLED");
            }
            if (path.contains("/messages")) {
                assertThat(description).contains("APP_CHAT_BRANCHING_ENABLED");
            }
        }
        // The derived notes (SSE, public route) still follow the curated prose.
        String edit =
                paths.path(base + "/messages/{messageId}/edit")
                        .path("post")
                        .path("description")
                        .asString();
        assertThat(edit).contains("Server-Sent Events");
        assertThat(edit.indexOf("APP_CHAT_BRANCHING_ENABLED"))
                .isLessThan(edit.indexOf("Server-Sent Events"));
    }

    // ================================================================== small helpers

    @Test
    void normalizePathCleansTemplates() {
        assertThat(OpenApiSpecGenerator.normalizePath("/api/{k:agents|workflows}/{id}/grants", ""))
                .isEqualTo("/api/{k}/{id}/grants");
        assertThat(OpenApiSpecGenerator.normalizePath("/a", "/")).isEqualTo("/a");
        assertThat(OpenApiSpecGenerator.normalizePath("", "")).isEqualTo("/");
        assertThat(OpenApiSpecGenerator.normalizePath("/api/x", "{n:[0-9]{2,3}}"))
                .isEqualTo("/api/x/{n}");
        assertThat(OpenApiSpecGenerator.normalizePath("/x", "/**")).isNull();
        assertThat(OpenApiSpecGenerator.normalizePath("/x", "${a.b:/c}")).isNull();
    }

    @Test
    void jsonWriterIsStableAndEscapes() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("b", "line1\nline2 \"quoted\" \\ back");
        m.put("a", List.of(1, true));
        m.put("empty", Map.of());
        String json = OpenApiJsonWriter.write(m);
        assertThat(json)
                .isEqualTo(
                        "{\n"
                                + "  \"b\": \"line1\\n"
                                + "line2 \\\"quoted\\\" \\\\ back\",\n"
                                + "  \"a\": [\n"
                                + "    1,\n"
                                + "    true\n"
                                + "  ],\n"
                                + "  \"empty\": {}\n"
                                + "}\n");
    }

    @Test
    void publicRulesAndSecurityHelpers() {
        assertThat(OpenApiSecurity.isPublic("POST", "/api/signup")).isTrue();
        assertThat(OpenApiSecurity.isPublic("GET", "/api/signup")).isFalse();
        assertThat(OpenApiSecurity.isPublic("POST", "/webhook/whatsapp")).isTrue();
        assertThat(OpenApiSecurity.isPublic("POST", "/api/devices/commands/{id}/result")).isTrue();
        assertThat(OpenApiSecurity.isPublic("GET", "/api/agents")).isFalse();
        // JWT or tenant API key (vdak_).
        assertThat(OpenApiSecurity.requirementsFor("GET", "/api/agents")).hasSize(2);
        assertThat(OpenApiSecurity.requirementsFor("GET", "/api/dev/v1/agents")).hasSize(1);
        assertThat(OpenApiSecurity.requirementsFor("POST", "/v1/chat/completions")).hasSize(2);
        assertThat(OpenApiSecurity.requirementsFor("POST", "/api/signup")).isEmpty();
    }

    /**
     * The allowlist is hand-mirrored from SecurityConfig; this fails when someone adds or removes a
     * {@code requestMatchers(...).permitAll()} there without updating {@link OpenApiSecurity}.
     */
    @Test
    void publicAllowlistMirrorsSecurityConfig() throws IOException {
        Path root = findRepoRoot();
        Assumptions.assumeTrue(root != null, "repo root not found from the working directory");
        Path source =
                root.resolve(
                        "core/security/src/main/java/com/agenticai/core/security/SecurityConfig.java");
        Assumptions.assumeTrue(
                Files.isRegularFile(source), "SecurityConfig.java not found at " + source);
        String text = String.join("\n", securityRuleSourceLines(source));

        Set<String> fromSource = new TreeSet<>();
        Matcher m =
                Pattern.compile("requestMatchers\\(([^)]*)\\)\\s*\\.permitAll\\(\\)").matcher(text);
        while (m.find()) {
            String method = null;
            List<String> patterns = new ArrayList<>();
            for (String token : m.group(1).split(",")) {
                String t = token.trim();
                if (t.startsWith("HttpMethod.")) {
                    method = t.substring("HttpMethod.".length());
                } else if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                    patterns.add(t.substring(1, t.length() - 1));
                }
            }
            for (String p : patterns) {
                if (p.startsWith("/actuator") || "OPTIONS".equals(method)) {
                    continue;
                }
                fromSource.add((method == null ? "*" : method) + " " + p);
            }
        }
        Set<String> fromAllowlist = new TreeSet<>();
        for (OpenApiSecurity.PublicRule rule : OpenApiSecurity.PUBLIC_RULES) {
            fromAllowlist.add((rule.method() == null ? "*" : rule.method()) + " " + rule.pattern());
        }
        assertThat(fromAllowlist)
                .as(
                        "OpenApiSecurity.PUBLIC_RULES must mirror the permitAll() lines of"
                                + " SecurityConfig")
                .isEqualTo(fromSource);
    }

    // ================================================================== generate mode

    /**
     * Writes docs/api/openapi.json when run with {@code -Dopenapi.write=true}; otherwise skipped.
     */
    @Test
    void writeSpecWhenRequested() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("openapi.write"),
                "set -Dopenapi.write=true to regenerate docs/api/openapi.json");
        Path root = findRepoRoot();
        assertThat(root)
                .as(
                        "repo root (a directory holding docs/ and core/pom.xml) above the working"
                                + " directory")
                .isNotNull();
        String json = new OpenApiSpecGenerator(projectVersion(root)).generateJson();
        Path out = root.resolve("docs").resolve("api").resolve("openapi.json").normalize();
        assertThat(out.startsWith(root)).as("output stays inside the repo").isTrue();
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    /**
     * Walks up from the working directory to the first directory holding both docs/ and
     * core/pom.xml.
     */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("docs"))
                    && Files.isRegularFile(dir.resolve("core").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * The core aggregator's version from core/pom.xml, without -SNAPSHOT; falls back to the
     * generator default.
     */
    private static String projectVersion(Path root) throws IOException {
        String pom =
                Files.readString(root.resolve("core").resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher m =
                Pattern.compile("<artifactId>core</artifactId>\\s*<version>([^<]+)</version>")
                        .matcher(pom);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.endsWith("-SNAPSHOT") ? v.substring(0, v.length() - "-SNAPSHOT".length()) : v;
        }
        return OpenApiSpecGenerator.DEFAULT_VERSION;
    }

    /**
     * SecurityConfig.java plus the {@code *Rules.java} classes its main chain calls: since the
     * chain was split, most rules live in those classes, so a source check must read all of them.
     */
    private static List<String> securityRuleSourceLines(Path securityConfig) throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(securityConfig.getParent())) {
            for (Path file :
                    files.filter(
                                    p ->
                                            p.equals(securityConfig)
                                                    || p.getFileName()
                                                            .toString()
                                                            .endsWith("Rules.java"))
                            .sorted()
                            .toList()) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return lines;
    }
}
