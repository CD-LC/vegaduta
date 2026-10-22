package com.agenticai.core.json;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Source-scan guard (ADR-0012 P1b): main code in every core module must build non-Spring-managed
 * mappers through {@link JsonMappers}, never {@code new ObjectMapper()}, so the Jackson 3 move is a
 * one-class change. Test sources are exempt. Comment lines are ignored.
 */
class JsonMappersUsageGuardTest {

    @Test
    void mainCodeNeverConstructsAnObjectMapperDirectly() throws IOException {
        Path core = Path.of("").toAbsolutePath().getParent();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> modules = Files.list(core)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        scanned++;
                        if (file.endsWith(
                                Path.of("com", "agenticai", "core", "json", "JsonMappers.java"))) {
                            continue;
                        }
                        List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i).strip();
                            if (line.startsWith("*")
                                    || line.startsWith("//")
                                    || line.startsWith("/*")) {
                                continue;
                            }
                            if (line.contains("new ObjectMapper(")) {
                                offenders.add(core.relativize(file) + ":" + (i + 1));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(
                scanned > 500,
                "guard scanned suspiciously few files (" + scanned + "); wrong working directory?");
        assertTrue(
                offenders.isEmpty(),
                "use JsonMappers.plain()/withJavaTime()/... instead of new ObjectMapper(): "
                        + offenders);
    }
}
