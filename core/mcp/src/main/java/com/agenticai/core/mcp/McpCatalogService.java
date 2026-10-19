package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The curated MCP server catalog behind the "MCP Tools" gallery - a fixed,
 * hand-maintained list rather than a live fetch from the public MCP
 * Registry (registry.modelcontextprotocol.io), deliberately: this platform's
 * Stage 4 MVP wires MCP client transports statically in
 * application-mcp.yml (see McpServerRegistration's javadoc), so surfacing
 * every server in the public registry would mostly show entries nobody
 * running this deployment could actually turn on - a worse experience than
 * a short, honest list of servers this platform specifically knows how to
 * wire up, each with the exact YAML needed to do it.
 *
 * <p>`ready` entries (filesystem, puppeteer, playwright-vision, memory,
 * sequential-thinking - the ones needing zero external credentials) are
 * already wired statically in application-mcp.yml; registering one
 * immediately produces real tools. `playwright-vision` was added per
 * Master-Roadmap-2026.md item #10 ("Vision-driven browser automation
 * upgrade") - see that entry's own comment below for why it sits alongside
 * puppeteer rather than replacing it.
 *
 * <p><b>{@code fetch} and {@code time} are deliberately NOT in this catalog</b>
 * (removed, not just left unwired) - direct incident: both used to be listed
 * here as `ready` npx entries pointing at {@code
 * @modelcontextprotocol/server-fetch}/{@code @modelcontextprotocol/server-time},
 * neither of which has ever existed on the npm registry (confirmed via a
 * real "npm ERR! 404" at runtime, not a guess). Worse than just those two
 * being broken: application-mcp.yml wires every zero-credential server
 * through ONE shared discovery call, and one connection failing there was
 * silently taking puppeteer/filesystem/memory/sequential-thinking down with
 * it too (see AgentToolProvider#resolveStaticMcpTools's javadoc on this
 * exact risk, and application-mcp.yml's own comment for the fuller story).
 * The official reference implementations for both are Python packages run
 * via {@code uvx mcp-server-fetch}/{@code uvx mcp-server-time}, not npx -
 * this platform has no wiring anywhere for a Python/uvx-launched MCP server
 * yet (every static entry assumes npx; the dynamic per-tenant "Connect"
 * form only appears for entries with at least one required credential, so
 * there's no clean way to offer a zero-credential-but-not-prewired entry
 * today either). Rather than leave an Activate/Add button that would just
 * silently no-op again, they're removed until one of those two things is
 * actually built - add `command: uvx` / `args: ["mcp-server-fetch"]` (or
 * `mcp-server-time`) to application-mcp.yml's stdio connections yourself,
 * once `uv` is installed, if you want either back before then. Every other,
 * credential-gated entry (github,
 * postgres, brave-search, slack, gitlab, google-maps, google-drive, sqlite,
 * sentry) is no longer YAML-only, though - see {@code #424}/{@link
 * com.agenticai.core.mcp.McpDynamicConnector}: the frontend can now collect
 * the required credential(s) and register a live, per-tenant dynamic
 * connection through this same catalog via {@link
 * McpCatalogEntry#dynamicCommand}/{@link McpCatalogEntry#dynamicArgs}, no
 * deploy-time YAML edit required. `yamlSnippet` is kept as the
 * once-for-every-tenant alternative for whoever manages the deployment.
 */
@Service
public class McpCatalogService {

    /** The reference servers first, then the first-party and hosted remote entries (gallery order). */
    private static final List<McpCatalogEntry> CATALOG = java.util.stream.Stream.concat(
            McpReferenceServerCatalog.ENTRIES.stream(), McpPlatformAndRemoteCatalog.ENTRIES.stream())
        .toList();

    /**
     * The catalog this instance serves: {@link #CATALOG} alone (the default,
     * byte-for-byte today's behaviour) or {@link #CATALOG} plus the ~30
     * Activepieces-piece entries from {@link PieceHostCatalog} when
     * {@code app.mcp.piece-host.enabled=true}. Piece entries are STDIO entries
     * that launch {@code node /opt/piece-host/server.js --pieces <piece>} in the
     * sandboxed mcp-stdio-runner (see piece-host/README.md and
     * docs/plans/PIECE-HOST.md); showing them only makes sense once that image
     * is deployed, hence the flag, default OFF.
     */
    private final List<McpCatalogEntry> servedCatalog;

    /** Piece host OFF - the catalog is exactly {@link #CATALOG}. Used by tests and callers that construct directly. */
    public McpCatalogService() {
        this(false, PieceHostCatalog.DEFAULT_ENTRYPOINT);
    }

    @Autowired
    public McpCatalogService(
        @Value("${app.mcp.piece-host.enabled:false}") boolean pieceHostEnabled,
        @Value("${app.mcp.piece-host.entrypoint:/opt/piece-host/server.js}") String pieceHostEntrypoint
    ) {
        if (pieceHostEnabled) {
            List<McpCatalogEntry> all = new ArrayList<>(CATALOG);
            all.addAll(PieceHostCatalog.entries(pieceHostEntrypoint));
            this.servedCatalog = List.copyOf(all);
        } else {
            this.servedCatalog = CATALOG;
        }
    }

    // Optional (setter-injected: tests construct this class with no arguments). Entries backed by a one-click
    // OAuth provider only count as catalog entries while that provider is configured on this deployment.
    private OAuthProviderRegistry oauthProviderRegistry;

    @Autowired(required = false)
    void setOAuthProviderRegistry(OAuthProviderRegistry registry) {
        this.oauthProviderRegistry = registry;
    }

    public List<McpCatalogEntry> list() {
        return servedCatalog;
    }

    /**
     * Whether {@code name} matches a curated catalog entry (case-insensitive,
     * same key {@link McpRegistryService#register} upserts by) - the one
     * signal {@link McpConnectorPlanLimits#allowsCustomServers} has to tell a
     * "built-in" registration from a hand-typed/registry-browsed custom one,
     * since {@link McpServerRegistration} persists no separate
     * catalog-vs-custom source column.
     */
    public boolean isCatalogEntry(String name) {
        return find(name).isPresent();
    }

    /**
     * The catalog entry {@code name} matches (case-insensitive), if any -
     * shared lookup behind {@link #isCatalogEntry} and, since MCP-STDIO-
     * SANDBOXING (2026-08-05), also used to (a) exempt catalog-sourced STDIO
     * registrations from the platform-staff review gate ({@link
     * McpRegistryService#register}) and (b) name a specific missing required
     * env var in a connection-timeout diagnosis instead of a generic hint
     * ({@code McpConnectionDiagnoser}, core/agents).
     */
    public Optional<McpCatalogEntry> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Optional<McpCatalogEntry> gallery = servedCatalog.stream()
            .filter(entry -> entry.name().equalsIgnoreCase(name))
            .filter(this::providerAvailable)
            .findFirst();
        // The personal-install starters (McpStarterCatalog) are curated but never listed in the gallery.
        return gallery.isPresent() ? gallery : McpStarterCatalog.find(name);
    }

    /**
     * False for an entry backed by a one-click OAuth provider ({@code oauthProviderId} set) whose provider is not
     * configured here - with the feature off, "github-oauth" must stay an ordinary custom name, otherwise a FREE
     * tenant could register ANY URL under it and pass the "catalog connectors only" plan gate.
     */
    private boolean providerAvailable(McpCatalogEntry entry) {
        if (entry.oauthProviderId() == null) {
            return true;
        }
        return oauthProviderRegistry != null
            && oauthProviderRegistry.find(entry.oauthProviderId()).map(OAuthProviderRegistry.Provider::configured).orElse(false);
    }

    /**
     * SEC-002: the TRUST-DECISION form of {@link #find}. {@link #find} and
     * {@link #isCatalogEntry} match on the name only, so any registration that
     * reuses a catalog name ({@code "github"}) with an arbitrary launch spec
     * ({@code npx -y attacker-pkg}) used to be treated as staff-vetted: it
     * skipped the STDIO review queue and the FREE-plan custom-server gate.
     * Name-only lookups remain fine for cosmetic uses (diagnosis hints,
     * readiness labels); every decision that grants trust must use this
     * method instead.
     *
     * <p>A registration is catalog-sourced only when it reproduces the entry's
     * exact launch spec:
     * <ul>
     *   <li>STDIO: the {@code type} is the entry's type; {@code command} equals
     *       {@link McpCatalogEntry#dynamicCommand()} exactly (no trimming, no
     *       case folding - {@code "NPX"} or {@code " npx"} is a different
     *       spec); {@code args} has the same length as {@link
     *       McpCatalogEntry#dynamicArgs()} and every element equals it exactly
     *       (so the package name AND any pinned version must match, and no
     *       argument may be added, removed or reordered). A catalog element
     *       written as {@code {{VAR}}} is a placeholder the console substitutes
     *       with a tenant value (a connection string or a file path): it
     *       accepts any non-empty value that does not start with {@code -}, so
     *       it can never smuggle in an extra flag. The env key set must be a
     *       subset of {@link McpCatalogEntry#requiredEnvVars()} (values are
     *       tenant secrets and are not compared).</li>
     *   <li>Remote: {@code url} equals {@link McpCatalogEntry#dynamicUrl()}
     *       exactly and no command is given.</li>
     *   <li>Name-only (no command, no url) - a reference to a statically wired
     *       connection: the name match alone, as there is no launch spec to
     *       deviate from.</li>
     * </ul>
     * An entry with no {@code dynamicCommand} (the {@code ready} built-ins)
     * therefore never matches a request that carries a command.
     *
     * <p>The personal-install starters ({@link McpStarterCatalog}) count as
     * catalog entries here, with their pinned version as part of the spec.
     */
    public Optional<McpCatalogEntry> findStrictMatch(
        String name, McpServerType type, String command, List<String> args,
        java.util.Collection<String> envKeys, String url
    ) {
        Optional<McpCatalogEntry> byName = find(name);
        if (byName.isEmpty()) {
            return Optional.empty();
        }
        Optional<McpCatalogEntry> match = strictMatch(byName.get(), type, command, args, envKeys, url);
        if (match.isPresent()) {
            return match;
        }
        // A starter pins a package version, and the gallery entry of the same name (sequential-thinking)
        // does not: the exact starter spec is catalog-sourced too, so check it when the gallery one misses.
        return McpStarterCatalog.find(name)
            .filter(starter -> starter != byName.get())
            .flatMap(starter -> strictMatch(starter, type, command, args, envKeys, url));
    }

    private Optional<McpCatalogEntry> strictMatch(
        McpCatalogEntry entry, McpServerType type, String command, List<String> args,
        java.util.Collection<String> envKeys, String url
    ) {
        Optional<McpCatalogEntry> byName = Optional.of(entry);
        boolean hasCommand = command != null && !command.isEmpty();
        boolean hasUrl = url != null && !url.isEmpty();
        if (!hasCommand && !hasUrl) {
            // Name-only reference to a statically wired connection.
            return (args == null || args.isEmpty()) && (envKeys == null || envKeys.isEmpty())
                ? byName : Optional.empty();
        }
        if (hasCommand && hasUrl) {
            return Optional.empty();
        }
        if (type != null && type != entry.type()) {
            return Optional.empty();
        }
        if (hasUrl) {
            boolean urlMatches = entry.dynamicUrl() != null && entry.dynamicUrl().equals(url)
                && (args == null || args.isEmpty());
            return urlMatches ? byName : Optional.empty();
        }
        if (entry.type() != McpServerType.STDIO || entry.dynamicCommand() == null
            || !entry.dynamicCommand().equals(command)) {
            return Optional.empty();
        }
        if (!argsMatch(entry.dynamicArgs(), args)) {
            return Optional.empty();
        }
        if (envKeys != null && !envKeys.isEmpty()) {
            List<String> declared = entry.requiredEnvVars() == null ? List.of() : entry.requiredEnvVars();
            for (String key : envKeys) {
                if (key == null || !declared.contains(key)) {
                    return Optional.empty();
                }
            }
        }
        return byName;
    }

    /** Boolean form of {@link #findStrictMatch}. */
    public boolean isStrictCatalogMatch(
        String name, McpServerType type, String command, List<String> args,
        java.util.Collection<String> envKeys, String url
    ) {
        return findStrictMatch(name, type, command, args, envKeys, url).isPresent();
    }

    private static final java.util.regex.Pattern ARG_PLACEHOLDER =
        java.util.regex.Pattern.compile("^\\{\\{[A-Za-z_][A-Za-z0-9_]*}}$");

    private static boolean argsMatch(List<String> catalogArgs, List<String> requestArgs) {
        List<String> expected = catalogArgs == null ? List.of() : catalogArgs;
        List<String> actual = requestArgs == null ? List.of() : requestArgs;
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            String want = expected.get(i);
            String got = actual.get(i);
            if (got == null) {
                return false;
            }
            if (ARG_PLACEHOLDER.matcher(want).matches()) {
                if (got.isEmpty() || got.startsWith("-")) {
                    return false;
                }
            } else if (!want.equals(got)) {
                return false;
            }
        }
        return true;
    }
}
