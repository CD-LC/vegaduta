package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Two migrations with one version in the same location stop core at start-up (Flyway: "Found more than one
 * migration with version N"). It happened on 2026-10-23 when a branch and main each added a V662; nothing caught
 * it before an image was built. This pins it at unit-test time for every migration location.
 */
class MigrationVersionsUniqueTest {

    private static final Path ROOT = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9][0-9._]*)__.+\\.(sql|java)$");

    @Test
    void everyMigrationLocationHasDistinctVersions() throws IOException {
        assertThat(ROOT).isDirectory();
        List<String> duplicates = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(ROOT)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Map<String, List<String>> byVersion = new TreeMap<>();
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile).forEach(file -> {
                        Matcher m = VERSIONED.matcher(file.getFileName().toString());
                        if (m.matches()) {
                            String version = m.group(1).replace('_', '.');
                            byVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(file.getFileName().toString());
                        }
                    });
                }
                byVersion.forEach((version, names) -> {
                    if (names.size() > 1) {
                        duplicates.add(ROOT.relativize(dir) + " V" + version + ": " + names);
                    }
                });
            }
        }
        assertThat(duplicates).as("migrations sharing a version").isEmpty();
    }
}
