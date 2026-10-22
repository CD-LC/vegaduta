package com.agenticai.core.apidocs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared plumbing for the tests that compare the generated OpenAPI document with files committed at
 * the repository root ({@code docs/api/openapi.json}, {@code contracts/consumers/**}): finding the
 * root, reading the core version, and generating the document once per JVM.
 *
 * <p>It mirrors the private helpers of {@link OpenApiSpecGeneratorTest} (same root rule, same
 * version rule) so the freshness check compares against exactly what that test's write mode
 * produces.
 */
final class ApiContractRepoSupport {

    static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private static String specJson;
    private static JsonNode specDoc;

    private ApiContractRepoSupport() {}

    /**
     * Walks up from the working directory to the first directory holding both docs/ and
     * core/pom.xml; null when there is none (a test run outside a checkout).
     */
    static Path findRepoRoot() {
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
     * The core aggregator's version from core/pom.xml without -SNAPSHOT (generator default if
     * absent).
     */
    static String projectVersion(Path root) {
        try {
            String pom =
                    Files.readString(
                            root.resolve("core").resolve("pom.xml"), StandardCharsets.UTF_8);
            Matcher m =
                    Pattern.compile("<artifactId>core</artifactId>\\s*<version>([^<]+)</version>")
                            .matcher(pom);
            if (m.find()) {
                String v = m.group(1).trim();
                return v.endsWith("-SNAPSHOT")
                        ? v.substring(0, v.length() - "-SNAPSHOT".length())
                        : v;
            }
            return OpenApiSpecGenerator.DEFAULT_VERSION;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The document generated from the controllers on the test classpath, as written by write mode.
     */
    static synchronized String generatedSpecJson(Path root) {
        if (specJson == null) {
            specJson = new OpenApiSpecGenerator(projectVersion(root)).generateJson();
        }
        return specJson;
    }

    static synchronized JsonNode generatedSpec(Path root) throws IOException {
        if (specDoc == null) {
            specDoc = MAPPER.readTree(generatedSpecJson(root));
        }
        return specDoc;
    }
}
