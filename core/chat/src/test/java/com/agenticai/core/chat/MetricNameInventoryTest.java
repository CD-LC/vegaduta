package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code llm.*} / {@code resilience.*} meter names this module registers. Dashboards and
 * alert rules read these names, and the framework upgrade renames or removes meters in places (one
 * Ollama meter is expected to go). A failure here means a meter was added, renamed or removed:
 * update the set and the dashboards together, on purpose.
 */
class MetricNameInventoryTest {

    private static final Pattern METER = Pattern.compile("\"((?:llm|resilience)\\.[a-z_.]+)\"");

    private static final Set<String> EXPECTED =
            Set.of(
                    "llm.cache.accounted.calls",
                    "llm.cache.hit.ratio",
                    "llm.call.errors",
                    "llm.calls",
                    "llm.cost.usd",
                    "llm.gateway.injection_block",
                    "llm.gateway.injection_flag",
                    "llm.gateway.rejections",
                    "llm.input.tokens.cache.read",
                    "llm.input.tokens.cache.write",
                    "llm.input.tokens.uncached",
                    "llm.latency",
                    "llm.ollama.toolcall_parse_retry",
                    "llm.output.tokens.reasoning",
                    "llm.pricing.missing",
                    "llm.prompt.prefix.tokens",
                    "llm.spend.counter.drift.usd",
                    "llm.spend.counter.missing.schemas",
                    "llm.spend.counter.unavailable",
                    "llm.stream.cancelled");

    @Test
    void theMeterNamesRegisteredByTheChatModuleAreExactlyTheKnownSet() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = METER.matcher(Files.readString(file));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(EXPECTED);
    }
}
