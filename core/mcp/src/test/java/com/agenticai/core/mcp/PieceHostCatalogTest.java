package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Activepieces piece-host catalog entries (see {@link PieceHostCatalog} and piece-host/README.md).
 * The contract this suite locks:
 *
 * <ul>
 *   <li>flag OFF (the default, and {@code new McpCatalogService()}): the catalog is unchanged - not
 *       one piece entry is visible or findable;
 *   <li>flag ON: ~30 STDIO dynamic-connect entries are appended, each launching {@code node
 *       <entrypoint> --pieces <piece>} with exactly one credential env var in the {@code
 *       PIECE_AUTH_*} namespace the host reads, and each acceptable to {@link McpStdioCommandGuard}
 *       (the real gate on what the runner will spawn);
 *   <li>every catalog piece is on the host's pinned allow-list (piece-host/pieces.txt), so an entry
 *       can never point at a package the image does not contain.
 * </ul>
 */
class PieceHostCatalogTest {

    private static final List<String> EXPECTED_PIECES =
            List.of(
                    "google-sheets",
                    "gmail",
                    "slack",
                    "notion",
                    "airtable",
                    "hubspot",
                    "trello",
                    "asana",
                    "jira-cloud",
                    "github",
                    "stripe",
                    "shopify",
                    "twilio",
                    "discord",
                    "telegram-bot",
                    "zoho-crm",
                    "salesforce",
                    "mailchimp",
                    "calendly",
                    "typeform",
                    "woocommerce",
                    "clickup",
                    "linear",
                    "zendesk",
                    "freshdesk",
                    "pipedrive",
                    "dropbox",
                    "microsoft-onedrive",
                    "zoom",
                    "intercom");

    private final McpCatalogService off = new McpCatalogService();
    private final McpCatalogService on = new McpCatalogService(true, "/opt/piece-host/server.js");

    private static boolean isPiece(McpCatalogEntry entry) {
        return entry.key().startsWith("piece-");
    }

    private List<McpCatalogEntry> pieces(McpCatalogService service) {
        return service.list().stream().filter(PieceHostCatalogTest::isPiece).toList();
    }

    @Test
    void flagOffLeavesTheCatalogExactlyAsItWas() {
        McpCatalogService explicitOff = new McpCatalogService(false, "/opt/piece-host/server.js");

        assertTrue(pieces(off).isEmpty(), "no piece entries without the flag");
        assertTrue(pieces(explicitOff).isEmpty());
        assertEquals(
                off.list(), explicitOff.list(), "explicit false must equal the no-arg catalog");
        assertTrue(off.find("piece-slack").isEmpty());
        assertFalse(off.isCatalogEntry("piece-slack"));
        assertEquals(
                off.list().size(),
                on.list().size() - EXPECTED_PIECES.size(),
                "the flag adds exactly the piece entries and removes/changes nothing else");
    }

    @Test
    void flagOnAppendsTheThirtyPopularPiecesAfterTheExistingEntries() {
        assertEquals(30, EXPECTED_PIECES.size());
        assertEquals(
                EXPECTED_PIECES.stream().map(k -> "piece-" + k).collect(Collectors.toSet()),
                pieces(on).stream().map(McpCatalogEntry::key).collect(Collectors.toSet()));
        // Existing entries keep their order and identity at the front.
        assertEquals(off.list(), on.list().subList(0, off.list().size()));
    }

    @Test
    void everyKeyAndNameStaysUniqueAcrossTheWholeCatalogWhenOn() {
        List<String> keys = on.list().stream().map(McpCatalogEntry::key).toList();
        List<String> names =
                on.list().stream().map(McpCatalogEntry::name).map(String::toLowerCase).toList();

        assertEquals(keys.size(), new HashSet<>(keys).size(), "duplicate catalog key");
        assertEquals(
                names.size(),
                new HashSet<>(names).size(),
                "duplicate catalog name (find() is by name)");
    }

    @Test
    void pieceEntriesAreStdioDynamicConnectEntriesPointingAtThePieceHost() {
        for (McpCatalogEntry entry : pieces(on)) {
            String piece = entry.key().substring("piece-".length());
            assertEquals(entry.key(), entry.name(), "name == key, like every other catalog entry");
            assertEquals(McpServerType.STDIO, entry.type(), entry.key());
            assertFalse(entry.ready(), entry.key() + " needs the tenant's own credential");
            assertFalse(
                    entry.requiresOAuth(),
                    entry.key()
                            + ": the host runs no OAuth flow; the tenant pastes a token they obtained");
            assertEquals("node", entry.dynamicCommand(), entry.key());
            assertEquals(
                    List.of("/opt/piece-host/server.js", "--pieces", piece),
                    entry.dynamicArgs(),
                    entry.key());
            assertNull(entry.dynamicUrl(), entry.key());
            assertNull(
                    entry.yamlSnippet(),
                    entry.key()
                            + ": /opt/piece-host exists only in the runner image, so there is no static-YAML alternative");
            assertTrue(
                    entry.docsUrl()
                            .startsWith(
                                    "https://github.com/activepieces/activepieces/tree/main/packages/pieces/community/"
                                            + piece),
                    entry.key());
            assertFalse(
                    entry.verified(),
                    entry.key()
                            + " has not been run against the live service, so it must not claim 'verified'");
        }
    }

    @Test
    void eachEntryNamesExactlyOneCredentialVariableInThePieceAuthNamespace() {
        for (McpCatalogEntry entry : pieces(on)) {
            assertEquals(1, entry.requiredEnvVars().size(), entry.key());
            String env = entry.requiredEnvVars().get(0);
            String expectedBase =
                    "PIECE_AUTH_"
                            + entry.key()
                                    .substring("piece-".length())
                                    .toUpperCase()
                                    .replace('-', '_');
            assertTrue(
                    env.equals(expectedBase) || env.equals(expectedBase + "_JSON"),
                    entry.key()
                            + " credential variable '"
                            + env
                            + "' does not match what the host reads ("
                            + expectedBase
                            + "[_JSON])");
        }
        // Spot checks that pin the naming rule, including a dash and the multi-field JSON form.
        assertEquals(
                List.of("PIECE_AUTH_SLACK"),
                on.find("piece-slack").orElseThrow().requiredEnvVars());
        assertEquals(
                List.of("PIECE_AUTH_GOOGLE_SHEETS"),
                on.find("piece-google-sheets").orElseThrow().requiredEnvVars());
        assertEquals(
                List.of("PIECE_AUTH_TRELLO_JSON"),
                on.find("piece-trello").orElseThrow().requiredEnvVars());
        assertEquals(
                List.of("PIECE_AUTH_MICROSOFT_ONEDRIVE"),
                on.find("piece-microsoft-onedrive").orElseThrow().requiredEnvVars());
    }

    @Test
    void descriptionsStateHonestlyWhichCredentialsTheUserMustSupplyAndWhatIsNotVerified() {
        for (McpCatalogEntry entry : pieces(on)) {
            String d = entry.description();
            assertTrue(d.contains("Credentials you must supply:"), entry.key());
            assertTrue(d.contains("Activepieces community piece"), entry.key());
            assertTrue(d.contains("Not yet verified against the live service."), entry.key());
        }
        // Short-lived OAuth tokens: the host cannot refresh them, and the entry must say so.
        for (String key :
                List.of(
                        "piece-google-sheets",
                        "piece-gmail",
                        "piece-microsoft-onedrive",
                        "piece-zoom",
                        "piece-dropbox")) {
            assertTrue(
                    on.find(key)
                            .orElseThrow()
                            .description()
                            .contains("does not run OAuth or refresh tokens"),
                    key);
        }
        // Money-moving services carry a warning an agent will read.
        assertTrue(on.find("piece-stripe").orElseThrow().description().contains("real money"));
        assertTrue(on.find("piece-twilio").orElseThrow().description().contains("cost money"));
    }

    @Test
    void everyPieceEntryIsAcceptedByTheRealStdioCommandGuard() {
        McpStdioCommandGuard guard = new McpStdioCommandGuard();
        for (McpCatalogEntry entry : pieces(on)) {
            Map<String, String> env = new HashMap<>();
            for (String name : entry.requiredEnvVars()) {
                env.put(name, "value");
            }
            assertTrue(
                    guard.check(entry.dynamicCommand(), entry.dynamicArgs(), env).isEmpty(),
                    entry.key()
                            + " would be rejected at registration: "
                            + guard.check(entry.dynamicCommand(), entry.dynamicArgs(), env));
        }
    }

    @Test
    void theExistingCatalogInvariantsHoldForThePieceEntriesToo() {
        for (McpCatalogEntry entry : on.list()) {
            if (!entry.ready() && !entry.requiresOAuth() && !"context-mode".equals(entry.key())) {
                assertFalse(
                        entry.requiredEnvVars().isEmpty(),
                        "non-ready entry without env vars: " + entry.key());
            }
            assertEquals(entry.dynamicCommand() != null, entry.dynamicArgs() != null, entry.key());
            assertTrue(entry.description() != null && !entry.description().isBlank(), entry.key());
            assertTrue(entry.docsUrl() != null && !entry.docsUrl().isBlank(), entry.key());
            if (!entry.ready()) {
                assertTrue(
                        entry.dynamicCommand() != null
                                || (entry.yamlSnippet() != null && !entry.yamlSnippet().isBlank()),
                        "dead-end entry: " + entry.key());
            }
        }
    }

    @Test
    void catalogMembershipMakesPieceRegistrationsFindableByNameForTheReviewGate() {
        // McpRegistryService#register exempts catalog-named STDIO registrations from the
        // staff-review gate by
        // case-insensitive name - so a piece registration is only exempt while the flag is on.
        assertTrue(on.find("PIECE-Slack").isPresent());
        assertTrue(on.isCatalogEntry("piece-notion"));
        assertTrue(off.find("PIECE-Slack").isEmpty());
    }

    @Test
    void aCustomEntrypointIsUsedAndABlankOneFallsBackToTheDefault() {
        McpCatalogService custom = new McpCatalogService(true, "/srv/ph/server.js");
        assertEquals(
                List.of("/srv/ph/server.js", "--pieces", "slack"),
                custom.find("piece-slack").orElseThrow().dynamicArgs());

        for (String blank : new String[] {"", "   ", null}) {
            McpCatalogService svc = new McpCatalogService(true, blank);
            assertEquals(
                    List.of("/opt/piece-host/server.js", "--pieces", "slack"),
                    svc.find("piece-slack").orElseThrow().dynamicArgs());
        }
    }

    @Test
    void everyCatalogPieceIsOnTheHostsPinnedAllowList() throws IOException {
        // piece-host/pieces.txt is what the image installs and what the host's loader will accept;
        // an entry for a
        // package that is not listed there would connect and then fail every call. Located relative
        // to this module
        // (core/mcp); skipped (not failed) if the module is built outside the repository layout.
        Path allowList = locatePiecesTxt();
        Assumptions.assumeTrue(
                allowList != null,
                "piece-host/pieces.txt not reachable from the test working directory");

        Set<String> pinned = new HashSet<>();
        for (String raw : Files.readAllLines(allowList, StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("#.*$", "").trim();
            if (line.startsWith("@activepieces/piece-")) {
                assertTrue(
                        line.matches(
                                "@activepieces/piece-[a-z0-9-]+@\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?"),
                        "pieces.txt entry must be pinned to an exact version: " + line);
                pinned.add(line.substring("@activepieces/piece-".length(), line.lastIndexOf('@')));
            }
        }
        for (PieceHostCatalog.Spec spec : PieceHostCatalog.SPECS) {
            assertTrue(
                    pinned.contains(spec.key()),
                    spec.key() + " is in the catalog but not pinned in piece-host/pieces.txt");
        }
        assertEquals(PieceHostCatalog.SPECS.size(), EXPECTED_PIECES.size());
    }

    private static Path locatePiecesTxt() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("piece-host").resolve("pieces.txt");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
