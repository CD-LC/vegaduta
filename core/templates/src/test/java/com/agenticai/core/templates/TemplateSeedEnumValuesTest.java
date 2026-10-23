package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Platform templates are seeded by control-plane migrations as JSON text. The importer now rejects
 * an unknown enum value, so a seed carrying one would be un-importable (it used to be silently
 * defaulted, which is how {@code "reasoningDepth": "BALANCED"} shipped unnoticed). This reads every
 * control-plane migration and checks each enum-typed design field against the real enum.
 */
class TemplateSeedEnumValuesTest {

    private static final Path MIGRATIONS =
            Path.of("..", "app", "src", "main", "resources", "db", "migration", "controlplane");

    /** {@code "field": "VALUE"} in either plain or backslash-escaped JSON. */
    private static final Pattern FIELD_VALUE =
            Pattern.compile("\\\\?\"([A-Za-z]+)\\\\?\"\\s*:\\s*\\\\?\"([A-Za-z_]+)\\\\?\"");

    /** Shipped migrations are immutable; a later migration repairs these (see the assertion below). */
    private static final String LEGACY_SEED = "V502__personal_agent_starter_template.sql";

    /** The repair migration quotes the bad value in its own WHERE/regexp, on purpose. */
    private static final String REPAIR_FRAGMENT = "fix_personal_assistant_template_reasoning_depth";

    private static List<String> offenders(boolean includeLegacy) throws Exception {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.contains(REPAIR_FRAGMENT) || (!includeLegacy && name.equals(LEGACY_SEED))) {
                    continue;
                }
                Matcher m = FIELD_VALUE.matcher(Files.readString(f));
                while (m.find()) {
                    Class<? extends Enum<?>> type =
                            TemplateSnapshotService.AGENT_ENUM_FIELDS.get(m.group(1));
                    if (type == null) {
                        continue;
                    }
                    String value = m.group(2);
                    boolean known = false;
                    for (Enum<?> c : type.getEnumConstants()) {
                        known |= c.name().equals(value);
                    }
                    if (!known) {
                        bad.add(f.getFileName() + ": " + m.group(1) + "=" + value);
                    }
                }
            }
        }
        return bad;
    }

    @Test
    void everySeededTemplateUsesRealEnumValues_exceptTheOneRepairedLater() throws Exception {
        assertThat(offenders(false)).isEmpty();
    }

    @Test
    void theLegacyBalancedSeedIsRepairedByALaterMigration() throws Exception {
        assertThat(offenders(true)).containsExactly(LEGACY_SEED + ": reasoningDepth=BALANCED");
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            String repair =
                    files.filter(p -> p.getFileName().toString().contains(REPAIR_FRAGMENT))
                            .map(p -> {
                                try {
                                    return Files.readString(p);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            })
                            .findFirst()
                            .orElse("");
            assertThat(repair).contains("community_template").contains("BALANCED").contains("\"FAST\"");
        }
    }
}
