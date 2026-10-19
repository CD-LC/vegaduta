package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link McpCatalogService} backs the "MCP Tools" gallery. Zero test
 * coverage before this suite despite the class's own javadoc documenting a
 * real production incident: {@code fetch} and {@code time} catalog entries
 * pointed at npm packages that never existed, and - worse - one broken
 * connection in {@code application-mcp.yml}'s shared discovery call was
 * silently taking down puppeteer/filesystem/memory/sequential-thinking with
 * it too. This suite locks in the fix (those two entries removed) as a
 * regression test, and asserts the structural invariants a broken catalog
 * entry could otherwise violate silently (a `ready` entry with a dangling
 * required-env-var, a duplicate key breaking the frontend's React list, a
 * dynamic entry with mismatched command/args wiring).
 */
class McpCatalogServiceTest {

    private final McpCatalogService service = new McpCatalogService();

    @Test
    void listReturnsANonEmptyCatalog() {
        assertFalse(service.list().isEmpty());
    }

    @Test
    void everyEntryHasTheBasicFieldsThePersonRegisteringItNeeds() {
        for (McpCatalogEntry entry : service.list()) {
            assertTrue(entry.key() != null && !entry.key().isBlank(), "blank key");
            assertTrue(entry.name() != null && !entry.name().isBlank(), "blank name for " + entry.key());
            assertTrue(entry.type() != null, "null type for " + entry.key());
            assertTrue(entry.description() != null && !entry.description().isBlank(), "blank description for " + entry.key());
            assertTrue(entry.docsUrl() != null && !entry.docsUrl().isBlank(), "blank docsUrl for " + entry.key());
            assertTrue(entry.requiredEnvVars() != null, "null (not just empty) requiredEnvVars list for " + entry.key());
        }
    }

    @Test
    void everyCatalogKeyIsUnique() {
        // McpCatalogEntry#key is the frontend's React list key and doubles
        // as the registration name for `ready` entries - a duplicate would
        // silently break both.
        List<String> keys = service.list().stream().map(McpCatalogEntry::key).toList();

        assertTrue(keys.size() == Set.copyOf(keys).size(), "duplicate catalog key found: " + keys);
    }

    @Test
    void readyEntriesRequireNoEnvironmentVariablesSinceTheyreAlreadyWiredStatically() {
        for (McpCatalogEntry entry : service.list()) {
            if (entry.ready()) {
                assertTrue(entry.requiredEnvVars().isEmpty(),
                    "ready entry '" + entry.key() + "' should need zero credentials, found " + entry.requiredEnvVars());
            }
        }
    }

    @Test
    void nonReadyEntriesDeclareAtLeastOneRequiredCredentialExplainingWhyTheyArentReady() {
        for (McpCatalogEntry entry : service.list()) {
            // An OAuth-backed entry (2026-08-15, e.g. atlassian) has a
            // different, equally valid reason for not being ready: it needs
            // a real browser-redirect consent flow, not a pasted credential
            // - requiredEnvVars is legitimately empty for these.
            if (!entry.ready() && !entry.requiresOAuth()) {
                assertFalse(entry.requiredEnvVars().isEmpty(),
                    "non-ready entry '" + entry.key() + "' declares no required env vars - why isn't it ready?");
            }
        }
    }

    @Test
    void fetchAndTimeAreNotInTheCatalog() {
        // Regression test for the documented incident (see this class's
        // javadoc and McpCatalogService's own javadoc): both pointed at npm
        // packages that never existed and were removed, not just left
        // broken - a re-add without also wiring a real uvx-based transport
        // would reintroduce the same silent failure.
        Set<String> keys = service.list().stream().map(McpCatalogEntry::key).collect(Collectors.toSet());

        assertFalse(keys.contains("fetch"), "'fetch' should not be back in the catalog without a real uvx transport");
        assertFalse(keys.contains("time"), "'time' should not be back in the catalog without a real uvx transport");
    }

    @Test
    void dynamicCommandAndArgsAreBothPresentOrBothAbsentTogether() {
        for (McpCatalogEntry entry : service.list()) {
            boolean hasCommand = entry.dynamicCommand() != null;
            boolean hasArgs = entry.dynamicArgs() != null;
            assertTrue(hasCommand == hasArgs,
                "entry '" + entry.key() + "' has mismatched dynamicCommand/dynamicArgs presence");
        }
    }

    @Test
    void findMatchesByNameCaseInsensitivelyAndIsEmptyForAnUnknownName() {
        assertTrue(service.find("Brave-Search").isPresent(), "find should be case-insensitive");
        assertEquals("brave-search", service.find("BRAVE-SEARCH").orElseThrow().key());
        assertTrue(service.find("my-custom-server").isEmpty());
        assertTrue(service.find(null).isEmpty());
    }

    @Test
    void isCatalogEntryAgreesWithFind() {
        for (McpCatalogEntry entry : service.list()) {
            if (entry.oauthProviderId() != null) {
                continue; // provider-backed entries count only while the provider is configured (see McpRegistryControllerCatalogFilterTest)
            }
            assertTrue(service.isCatalogEntry(entry.name()));
        }
        assertFalse(service.isCatalogEntry("definitely-not-a-catalog-entry"));
    }

    @Test
    void homeAssistantIsACuratedFirstPartyIotEntryWithTheDynamicConnectShapeItsOwnReadmeDocuments() {
        // Roadmap §9.4: IoT = ONE first-party Home Assistant MCP connector
        // (mcp-servers/home-assistant/). This locks the catalog entry to the
        // exact registration shape that connector's README documents (command
        // "node", the script path, HASS_URL + HASS_TOKEN) so the frontend's
        // data-driven Connect form and McpStdioCommandGuard's allowlist
        // ("node" is allowed; only the COMMAND may not be a path, args may)
        // keep lining up. The path must match deploy/docker-compose.yml's
        // ../mcp-servers:/opt/mcp-servers:ro bind mounts (core AND
        // mcp-stdio-runner) - change all of them together or the Connect
        // flow dies cleanly-but-uselessly on a missing file.
        McpCatalogEntry entry = service.find("home-assistant").orElseThrow(
            () -> new AssertionError("home-assistant should be in the curated catalog (roadmap §9.4 IoT connector)"));

        assertEquals(McpServerType.STDIO, entry.type());
        assertFalse(entry.ready(), "needs tenant credentials, must not be one-click ready");
        assertEquals(List.of("HASS_URL", "HASS_TOKEN"), entry.requiredEnvVars());
        assertEquals("node", entry.dynamicCommand());
        assertEquals(List.of("/opt/mcp-servers/home-assistant/index.js"), entry.dynamicArgs());
        assertFalse(entry.requiresOAuth(), "HA uses a pasted long-lived token, not an OAuth redirect flow");
        assertTrue(entry.yamlSnippet() != null && entry.yamlSnippet().contains("HASS_TOKEN"),
            "yamlSnippet should document the static-wiring alternative including HASS_TOKEN");
    }

    @Test
    void connectorsWhoseServersDoNotShipInThisRepoAreNotCatalogued() {
        // A catalog entry pointing at /opt/mcp-servers/<name>/index.js for a
        // server that is not in examples/mcp-servers would render a Connect
        // card that can only ever fail on a missing file. clipbee-ui is also
        // excluded on Terms-of-Service grounds (automated UI access).
        for (String name : List.of("youtube-publish", "broll-generate", "clipbee-ui", "sap",
                "office-ally", "outreach", "manufacturing-ops", "marketing-ops")) {
            assertFalse(service.isCatalogEntry(name), name + " must not be in the curated catalog");
        }
    }

    @Test
    void everyNonReadyEntryOffersADynamicConnectPathOrAYamlSnippetSoItIsNotADeadEnd() {
        for (McpCatalogEntry entry : service.list()) {
            if (!entry.ready()) {
                boolean hasDynamicPath = entry.dynamicCommand() != null;
                boolean hasYaml = entry.yamlSnippet() != null && !entry.yamlSnippet().isBlank();
                assertTrue(hasDynamicPath || hasYaml,
                    "non-ready entry '" + entry.key() + "' offers neither a dynamic-connect form nor a YAML snippet");
            }
        }
    }

    // --- SEC-002: findStrictMatch ----------------------------------------------

    @Test
    void strictMatchRequiresTheExactStdioLaunchSpec() {
        McpCatalogService catalog = new McpCatalogService();
        List<String> args = List.of("-y", "@modelcontextprotocol/server-github");
        assertTrue(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx", args,
            Set.of("GITHUB_PERSONAL_ACCESS_TOKEN"), null));
        assertTrue(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx", args, Set.of(), null));
        // name alone is never enough
        assertTrue(catalog.isCatalogEntry("github"));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx",
            List.of("-y", "attacker-pkg"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-github", "-y", "attacker-pkg"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-github@1.2.3"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx",
            List.of("@modelcontextprotocol/server-github", "-y"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "Npx", args, Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx\t", args, Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.SSE, "npx", args, Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx", args,
            Set.of("github_personal_access_token"), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx", args,
            Set.of("GITHUB_PERSONAL_ACCESS_TOKEN", "NPM_TOKEN"), null));
        assertFalse(catalog.isStrictCatalogMatch("github", McpServerType.STDIO, "npx", args, Set.of(),
            "https://mcp.example.com/mcp"));
        assertFalse(catalog.isStrictCatalogMatch("not-a-catalog-name", McpServerType.STDIO, "npx", args, Set.of(), null));
    }

    @Test
    void strictMatchPlaceholderArgsAcceptValuesButNotFlags() {
        McpCatalogService catalog = new McpCatalogService();
        assertTrue(catalog.isStrictCatalogMatch("sqlite", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-sqlite", "--db-path", "/data/app.db"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("sqlite", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-sqlite", "--db-path", "--registry=x"), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("sqlite", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-sqlite", "--db-path", ""), Set.of(), null));
        assertFalse(catalog.isStrictCatalogMatch("sqlite", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-sqlite", "--db-path"), Set.of(), null));
    }

    @Test
    void strictMatchReadyEntriesWithoutADynamicCommandNeverMatchACommand() {
        McpCatalogService catalog = new McpCatalogService();
        assertTrue(catalog.isStrictCatalogMatch("puppeteer", McpServerType.STDIO, null, null, null, null));
        assertFalse(catalog.isStrictCatalogMatch("puppeteer", McpServerType.STDIO, "npx",
            List.of("-y", "@modelcontextprotocol/server-puppeteer"), Set.of(), null));
    }

    @Test
    void strictMatchRemoteRequiresTheCatalogUrl() {
        McpCatalogService catalog = new McpCatalogService();
        assertTrue(catalog.isStrictCatalogMatch("atlassian", McpServerType.STREAMABLE_HTTP, null, null, Set.of(),
            "https://mcp.atlassian.com/v1/mcp"));
        assertFalse(catalog.isStrictCatalogMatch("atlassian", McpServerType.STREAMABLE_HTTP, null, null, Set.of(),
            "https://mcp.atlassian.com.evil.example.com/v1/mcp"));
    }
}
