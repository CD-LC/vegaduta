package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import java.util.List;

/**
 * The rest of {@link McpCatalogService}'s catalog, after the reference servers: this platform's
 * own first-party connectors (code-intel, home-assistant), Atlassian's hosted remote server and
 * the one-click OAuth provider entries. Each entry's comment carries its own provenance.
 */
final class McpPlatformAndRemoteCatalog {

    private McpPlatformAndRemoteCatalog() {}

    static final List<McpCatalogEntry> ENTRIES = List.of(
        // code-intel: this platform's OWN standalone code-intelligence service
        // (code-intel/), exposed to agents over standard MCP via its stdio
        // bridge (code-intel/app/mcp_bridge.py). Distinct from the native
        // core/codeintel ToolCallbacks (which every agent already gets when
        // code-intel.enabled=true) - this catalog entry is the MCP-standard
        // path, for deployments that prefer registering it like any other MCP
        // server, or external MCP clients. The bridge defaults to READ-ONLY
        // over this HTTP/registry path (CODE_INTEL_MCP_READONLY - only
        // search_code/code_callers/code_callees/code_impact), so it never
        // opens a second, unapproved write path: code writes stay on the
        // native approval-gated code-workspace tools. `ready:false` because it
        // needs the code-intel service reachable at CODE_INTEL_URL and the
        // bridge package present on whatever host launches the subprocess -
        // see code-intel/README.md's "Plugging into the platform's agents".
        new McpCatalogEntry(
            "code-intel",
            "code-intel",
            McpServerType.STDIO,
            "Semantic code search and the deterministic AST code graph (callers/callees/blast-radius) over this tenant's indexed repositories - read-only by default. Writes/execution stay on the platform's approval-gated code-workspace tools.",
            "git-branch",
            "https://github.com/modelcontextprotocol/servers",
            false,
            List.of("CODE_INTEL_URL", "CODE_INTEL_INTERNAL_TOKEN", "CODE_INTEL_TENANT"),
            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        code-intel:
                          command: python3
                          args: ["-m", "app.mcp_bridge"]
                          env:
                            CODE_INTEL_URL: "${CODE_INTEL_BASE_URL}"
                            CODE_INTEL_INTERNAL_TOKEN: "${CODE_INTEL_INTERNAL_TOKEN}"
                            CODE_INTEL_TENANT: "${CODE_INTEL_TENANT}"
                            CODE_INTEL_MCP_READONLY: "true"
            """,
            "python3",
            List.of("-m", "app.mcp_bridge")
        ),
        // home-assistant: this platform's OWN first-party IoT connector
        // (mcp-servers/home-assistant/ - VEGADUTA-MASTER-ROADMAP-2026-07-19.md
        // §9.4 decided IoT = one first-party MCP connector for Home Assistant,
        // not per-vendor integrations). Zero-npm-dependency Node script
        // (hand-rolled newline-delimited JSON-RPC 2.0 stdio transport - see
        // its index.js header for why). Its tool surface includes reads
        // (entities/state/history/logbook/templates/camera snapshots) AND
        // writes (call_service, fire_event) - see
        // mcp-servers/home-assistant/README.md for the authoritative tool
        // list rather than enumerating it here, where it has already gone
        // stale once. All tools are plain calls against Home
        // Assistant's documented REST API. `ready:false` because it needs two
        // tenant-supplied credentials (the tenant's own HA base URL and a
        // long-lived access token generated in HA under Profile -> Security).
        // dynamicArgs points at /opt/mcp-servers/home-assistant/index.js -
        // provided by deploy/docker-compose.yml's read-only ../mcp-servers
        // bind mount on BOTH core (direct-spawn mode) and mcp-stdio-runner
        // (the sandboxed-spawn mode that actually runs the subprocess when
        // MCP_STDIO_SIDECAR_ENABLED=true, the default). If that mount is
        // absent the connection lands as a clean ERROR status (node exits on
        // a nonexistent path), not anything dangerous. Honest caveat, carried
        // from the connector's own README: it has been integration-tested
        // against a local mock HA server (test/server.test.js spawns the real
        // process and speaks real MCP over stdio) but NEVER against a live
        // Home Assistant instance - smoke-test against a real one before
        // treating it as production-ready.
        new McpCatalogEntry(
            "home-assistant",
            "home-assistant",
            McpServerType.STDIO,
            "Query and control this tenant's Home Assistant smart home (IoT): list entities like lights, locks, climate and cameras, read live state and history, fetch camera snapshots, fire events, and call any HA service (light.turn_on, lock.unlock, ...). First-party reference connector - not yet smoke-tested against a live Home Assistant instance.",
            "home",
            "https://developers.home-assistant.io/docs/api/rest/",
            false,
            List.of("HASS_URL", "HASS_TOKEN"),
            """
            spring:
              ai:
                mcp:
                  client:
                    stdio:
                      connections:
                        home-assistant:
                          command: node
                          args: ["/opt/mcp-servers/home-assistant/index.js"]
                          env:
                            HASS_URL: "${HASS_URL}"
                            HASS_TOKEN: "${HASS_TOKEN}"
            """,
            "node",
            List.of("/opt/mcp-servers/home-assistant/index.js")
        ),
        // Atlassian's official hosted remote MCP server (Jira, Confluence, Jira
        // Service Management, Bitbucket, Compass) - the first streamable-HTTP
        // remote-type catalog entry (every entry above is STDIO/subprocess).
        // 2026-08-15 correction: the legacy SSE endpoint
        // (https://mcp.atlassian.com/v1/sse, used here until today) stopped
        // being supported after 2026-06-30 per Atlassian's own Rovo MCP
        // server docs - every real connection built from it now fails. The
        // only working endpoint is https://mcp.atlassian.com/v1/mcp
        // (streamable-HTTP), which is what both this entry's dynamicUrl and
        // AtlassianOAuthService#persistConnection's registration now use -
        // McpServerType is STREAMABLE_HTTP to match (DynamicMcpConnectionManager#
        // buildRemoteTransport picks SSE vs streamable-HTTP wire protocol off
        // this type field, not off the URL string, so the two must change
        // together).
        //
        // Unlike every entry above, `ready` is false and there is genuinely NO
        // static application-mcp.yml wiring path for it: that file's static
        // block is STDIO-only (see this class's own javadoc + application-mcp.yml's
        // comments) - remote SSE/STREAMABLE_HTTP transports in this codebase are
        // instead built exclusively per-tenant, from a McpServerRegistration DB
        // row's url/env, by DynamicMcpConnectionManager#buildRemoteTransport. So
        // `yamlSnippet` below documents that per-tenant registration shape
        // rather than a (nonexistent) deploy-time YAML block.
        //
        // requiresOAuth=true, requiredEnvVars=[] (2026-08-15, direct report -
        // a saved static "Bearer <token>" value always failed with a clean
        // 401: mcp.atlassian.com is OAuth 2.1-only, confirmed by probing its
        // /.well-known/oauth-authorization-server discovery document - there
        // is no credential a person can type in here that would ever work).
        // AtlassianOAuthController now owns the real connection: the
        // frontend's CatalogCardInspector must check requiresOAuth BEFORE
        // its generic dynamicUrl+requiredEnvVars credential-form branch and
        // send the person through a real browser-redirect Authorization
        // Code + PKCE flow instead - see AtlassianOAuthService's javadoc.
        // dynamicUrl is kept (not used by the frontend for this entry
        // anymore, informational only) as the well-known endpoint
        // AtlassianOAuthService.persistConnection registers per-tenant once
        // OAuth consent completes.
        new McpCatalogEntry(
            "atlassian",
            "atlassian",
            McpServerType.STREAMABLE_HTTP,
            "Search and update Jira issues, Confluence pages, and other Atlassian data via Atlassian's official hosted remote MCP server.",
            "kanban",
            "https://support.atlassian.com/atlassian-rovo-mcp-server/docs/getting-started-with-the-atlassian-remote-mcp-server/",
            false,
            List.of(),
            """
            # Atlassian's remote MCP server requires real OAuth 2.1 (Authorization
            # Code + PKCE) - there is no static credential to paste. The platform
            # registers itself with Atlassian once via Dynamic Client Registration
            # (AtlassianPlatformClientService); each tenant then completes its own
            # consent redirect (GET /api/mcp/registry/atlassian/authorize-url,
            # "Connect via Atlassian" in the console) rather than filing a
            # POST /api/mcp/registry request directly.
            """,
            null,
            null,
            "https://mcp.atlassian.com/v1/mcp",
            true,
            true
        ),
        // One-click OAuth provider entries (OAuthProviderRegistry): the person signs in at the
        // provider instead of pasting a token, and the connection's token becomes the bearer of
        // this remote server (oauthProvider marker "oauth:<id>", resolved in McpRemoteHeaders).
        // McpRegistryController#catalog lists them ONLY while the provider is actually configured
        // (feature on + client id/secret + callback base), so there is never a Connect button that
        // cannot work. The name equals OAuthProviderRegistry.Provider#mcpServerName() because
        // OAuthProviderMcpLinker registers the server under it; being a catalog name also lets a
        // FREE-plan tenant register it (find()/isCatalogEntry() count it only while its provider is configured).
        // GitHub's hosted server takes a bearer token (https://github.com/github/github-mcp-server) but its public
        // docs do NOT say whether an OAuth-app token (gho_...) is accepted - unverified, hence the hedged copy
        // below; Linear documents OAuth access tokens as
        // bearer on https://mcp.linear.app/mcp (https://linear.app/docs/mcp).
        new McpCatalogEntry(
            "github-oauth",
            "github-oauth",
            McpServerType.STREAMABLE_HTTP,
            "Repos, issues and pull requests through GitHub's hosted MCP server, connected by signing in to GitHub. Sign-in reads your profile by default; your admin widens the access. Not yet confirmed that GitHub's hosted server accepts OAuth-app tokens - if calls are refused, connect with a personal access token instead.",
            "github",
            "https://github.com/github/github-mcp-server",
            false,
            List.of(),
            """
            # Connected through the one-click OAuth provider flow (POST /api/mcp/oauth/github/authorize),
            # not by registering a token. The platform admin must configure the GitHub OAuth app
            # (app.mcp.oauth.providers.github.*) first.
            """,
            null,
            null,
            "https://api.githubcopilot.com/mcp/",
            true,
            true,
            "github"
        ),
        new McpCatalogEntry(
            "linear-oauth",
            "linear-oauth",
            McpServerType.STREAMABLE_HTTP,
            "Issues, projects and comments through Linear's hosted MCP server, connected by signing in to Linear - no API key to paste.",
            "kanban",
            "https://linear.app/docs/mcp",
            false,
            List.of(),
            """
            # Connected through the one-click OAuth provider flow (POST /api/mcp/oauth/linear/authorize),
            # not by registering a token. The platform admin must configure the Linear OAuth app
            # (app.mcp.oauth.providers.linear.*) first.
            """,
            null,
            null,
            "https://mcp.linear.app/mcp",
            true,
            true,
            "linear"
        )
    );
}
