package com.agenticai.core.quality.memeval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads memory-eval scenarios from JSON. The shipped dataset is listed in {@code
 * eval/memory/manifest.json}; each listed file holds one scenario object or an array of them.
 * Unknown properties and a wrong {@code schema_version} fail loudly: a malformed dataset edit must
 * break the free per-PR test, not a billed nightly run.
 */
public final class MemoryScenarioLoader {

    public static final String RESOURCE_ROOT = "eval/memory/";
    public static final String MANIFEST = RESOURCE_ROOT + "manifest.json";

    static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MemoryScenarioLoader() {}

    /**
     * The whole dataset: the hand-written files in manifest order, then the poisoning scenarios
     * {@link PoisonScenarioGenerator} derives from the shared red-team corpus.
     */
    public static List<MemoryScenario> loadAll() {
        List<MemoryScenario> out = new ArrayList<>(loadManifest());
        out.addAll(PoisonScenarioGenerator.generate());
        return List.copyOf(out);
    }

    /** Only the hand-written files listed in {@code eval/memory/manifest.json}. */
    public static List<MemoryScenario> loadManifest() {
        try (InputStream in = open(MANIFEST)) {
            JsonNode manifest = MAPPER.readTree(in);
            requireVersion(manifest.path("schema_version").asInt(-1), MANIFEST);
            List<MemoryScenario> out = new ArrayList<>();
            for (JsonNode file : manifest.path("files")) {
                try (InputStream f = open(RESOURCE_ROOT + file.asText())) {
                    out.addAll(parse(MAPPER.readTree(f), file.asText()));
                }
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load memory eval dataset", e);
        }
    }

    /** Loads one scenario file (object or array) from disk. */
    public static List<MemoryScenario> load(Path file) {
        try {
            return parse(MAPPER.readTree(Files.readString(file)), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public static List<MemoryScenario> parseJson(String json) {
        try {
            return parse(MAPPER.readTree(json), "<inline>");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse scenario JSON", e);
        }
    }

    private static List<MemoryScenario> parse(JsonNode node, String origin) throws IOException {
        List<MemoryScenario> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                out.add(one(n, origin));
            }
        } else {
            out.add(one(node, origin));
        }
        return out;
    }

    private static MemoryScenario one(JsonNode n, String origin) throws IOException {
        requireVersion(n.path("schema_version").asInt(-1), origin);
        return MAPPER.treeToValue(n, MemoryScenario.class);
    }

    private static void requireVersion(int version, String origin) {
        if (version != MemoryScenario.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    origin
                            + ": schema_version "
                            + version
                            + " is not supported (expected "
                            + MemoryScenario.SCHEMA_VERSION
                            + ")");
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = MemoryScenarioLoader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Missing classpath resource " + resource);
        }
        return in;
    }
}
