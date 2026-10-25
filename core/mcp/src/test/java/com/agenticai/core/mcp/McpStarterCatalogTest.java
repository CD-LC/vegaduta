package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The personal-install starter set: pinned, guard-clean, catalog-trusted and kept out of the gallery. */
class McpStarterCatalogTest {

    private final McpCatalogService catalog = new McpCatalogService();
    private final McpStdioCommandGuard guard = new McpStdioCommandGuard("");

    /** The version part of a package spec: npm {@code name@1.2.3} or uvx {@code name@2026.8.18}. */
    private static final Pattern EXACT_VERSION = Pattern.compile("^.+@\\d+(\\.\\d+)+$");

    private static String packageSpec(McpCatalogEntry e) {
        return e.dynamicArgs().stream().filter(a -> !a.startsWith("-")).findFirst().orElseThrow();
    }

    @Test
    void everyStarterNeedsNoCredentialAndPinsAnExactVersion() {
        for (McpStarterCatalog.Starter s : McpStarterCatalog.STARTERS) {
            McpCatalogEntry e = s.entry();
            assertEquals(McpServerType.STDIO, e.type(), e.name());
            assertTrue(e.requiredEnvVars().isEmpty(), e.name() + " must not need a secret");
            assertTrue(
                    EXACT_VERSION.matcher(packageSpec(e)).matches(),
                    e.name() + " must pin an exact version, not a range or latest: " + packageSpec(e));
            assertTrue(e.dynamicArgs().stream().noneMatch(a -> a.contains("{{")), e.name());
        }
    }

    @Test
    void everyStarterPassesTheStdioLauncherGuard() {
        for (McpCatalogEntry e : McpStarterCatalog.entries()) {
            Optional<String> problem = guard.check(e.dynamicCommand(), e.dynamicArgs(), java.util.Map.of());
            assertTrue(problem.isEmpty(), e.name() + " rejected by the guard: " + problem.orElse(""));
        }
    }

    @Test
    void namesAreUniqueAndDistinctFromTheGalleryExceptTheOneIntendedOverlap() {
        Set<String> seen = new HashSet<>();
        Set<String> gallery = new HashSet<>();
        catalog.list().forEach(e -> gallery.add(e.name().toLowerCase()));
        for (McpCatalogEntry e : McpStarterCatalog.entries()) {
            assertTrue(seen.add(e.name().toLowerCase()), "duplicate starter " + e.name());
        }
        Set<String> overlap = new HashSet<>(seen);
        overlap.retainAll(gallery);
        assertEquals(Set.of("sequential-thinking"), overlap);
    }

    @Test
    void onlyTimeIsOnByDefault() {
        // fetch reaches whatever the runner's network can (no egress filter); sequential-thinking
        // adds a tool every turn. Both are the owner's opt-in.
        Set<String> on = new HashSet<>();
        for (McpStarterCatalog.Starter s : McpStarterCatalog.STARTERS) {
            if (s.enabledByDefault()) {
                on.add(s.name());
            }
        }
        assertEquals(Set.of("time"), on);
    }

    @Test
    void startersAreNotListedInTheGallery() {
        List<String> names = catalog.list().stream().map(McpCatalogEntry::name).toList();
        assertFalse(names.contains("fetch"));
        assertFalse(names.contains("time"));
    }

    @Test
    void theExactStarterSpecIsCatalogSourced() {
        for (McpCatalogEntry e : McpStarterCatalog.entries()) {
            assertTrue(
                    catalog.isStrictCatalogMatch(
                            e.name(), e.type(), e.dynamicCommand(), e.dynamicArgs(), List.of(), null),
                    e.name());
        }
    }

    @Test
    void aDifferentVersionOrPackageUnderAStarterNameIsNot() {
        assertFalse(
                catalog.isStrictCatalogMatch(
                        "fetch", McpServerType.STDIO, "uvx", List.of("mcp-server-fetch"), List.of(), null),
                "unpinned");
        assertFalse(
                catalog.isStrictCatalogMatch(
                        "fetch", McpServerType.STDIO, "uvx", List.of("mcp-server-fetch@2099.1.1"), List.of(), null),
                "other version");
        assertFalse(
                catalog.isStrictCatalogMatch(
                        "fetch", McpServerType.STDIO, "uvx", List.of("attacker-pkg@1.0.0"), List.of(), null),
                "other package");
        assertFalse(
                catalog.isStrictCatalogMatch(
                        "time", McpServerType.STDIO, "uvx", List.of("mcp-server-time@2026.8.18"), List.of("API_KEY"), null),
                "an env key the entry does not declare");
    }

    @Test
    void theGalleryEntryOfTheSameNameStillMatchesItsOwnUnpinnedSpec() {
        // sequential-thinking is in the gallery as a `ready` entry: nothing that matched before stops matching.
        assertTrue(catalog.isStrictCatalogMatch("sequential-thinking", McpServerType.STDIO, null, null, null, null));
        McpCatalogEntry starter = McpStarterCatalog.find("sequential-thinking").orElseThrow();
        assertTrue(
                catalog.isStrictCatalogMatch(
                        "sequential-thinking", McpServerType.STDIO, starter.dynamicCommand(), starter.dynamicArgs(), List.of(), null));
        assertTrue(
                catalog.isStrictCatalogMatch(
                        "sequential-thinking", McpServerType.STDIO, "npx",
                        List.of("-y", "@modelcontextprotocol/server-sequential-thinking"), List.of(), null),
                "the gallery entry now carries the same launch spec (the static-reference upgrade),"
                        + " so that exact spec is catalog-sourced too");
        assertFalse(
                catalog.isStrictCatalogMatch(
                        "sequential-thinking", McpServerType.STDIO, "npx",
                        List.of("-y", "@modelcontextprotocol/server-sequential-thinking@2099.1.1"),
                        List.of(), null),
                "a different version of the package is still custom");
    }

    @Test
    void lookupByNameIsCaseInsensitiveAndFindsStarters() {
        assertTrue(catalog.find("FETCH").isPresent());
        assertTrue(catalog.isCatalogEntry("time"));
        assertFalse(catalog.isCatalogEntry("not-a-connector"));
    }
}
