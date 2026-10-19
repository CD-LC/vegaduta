package com.agenticai.core.mcp.dto;

import com.agenticai.core.mcp.McpServerType;
import java.util.List;

/**
 * A well-known MCP server the platform can pre-fill a registration for, so a
 * person never has to type a raw connection name/type/description by hand -
 * see {@code McpCatalogService} for the curated list and why {@code ready}
 * exists at all (Stage 4's MVP scope: the actual client transport is wired
 * statically per {@code application-mcp.yml}, not dynamically from a DB row
 * - see {@code McpServerRegistration}'s javadoc). A catalog entry is
 * everything a person needs to either register it in one click (if it's
 * already wired) or hand the exact YAML block to whoever manages the
 * deployment (if it isn't yet) - never "figure out the right values
 * yourself."
 *
 * @param key stable catalog identifier (not persisted - purely a frontend
 *     React key)
 * @param name the exact value to send as McpServerRegistrationRequest.name -
 *     must match the connection name in application-mcp.yml for `ready`
 *     entries
 * @param type transport type, matching the connection's section in
 *     application-mcp.yml (stdio/sse/streamable-http)
 * @param description short, human-facing description of what this server's
 *     tools let an agent do
 * @param iconKey a key the frontend maps to one of its brand icons (not a
 *     URL - keeps this catalog free of any external asset dependency)
 * @param docsUrl link to the MCP server's own documentation/README
 * @param ready true for servers already wired statically in this
 *     deployment's application-mcp.yml - registering a `ready` entry
 *     immediately makes its tools available, no further setup needed.
 * @param requiredEnvVars environment variables this server needs to actually
 *     start. For a `ready` entry these are already set by whoever manages
 *     the deployment. For a non-ready entry, {@code #424} means these no
 *     longer require editing {@code application-mcp.yml} and restarting -
 *     the frontend collects one value per entry here and registers a
 *     dynamic connection (see {@link com.agenticai.core.mcp.McpDynamicConnector})
 *     that connects live, immediately, scoped to the tenant that entered it.
 * @param yamlSnippet the exact static-wiring alternative, still shown for
 *     anyone who'd rather set this up once for every tenant via deploy
 *     config instead of per-tenant through the UI
 * @param dynamicCommand the subprocess command the dynamic-connection form
 *     submits (e.g. {@code "npx"}) - null for entries where dynamic setup
 *     isn't offered (currently only true `ready` entries, which don't need
 *     it)
 * @param dynamicArgs the subprocess args the dynamic-connection form
 *     submits. An element written as {@code "{{VAR_NAME}}"} is a
 *     placeholder: the frontend substitutes the value the person typed for
 *     the {@code requiredEnvVars} entry named {@code VAR_NAME} directly into
 *     the arg list instead of sending it as an env var - some reference MCP
 *     servers (postgres, sqlite) take their connection string as a
 *     positional CLI argument, not an environment variable, and this keeps
 *     that distinction data-driven rather than a per-server special case in
 *     the frontend.
 * @param requiresOAuth true for an entry whose connection is established via
 *     a real browser-redirect OAuth flow (e.g. Atlassian's Dynamic Client
 *     Registration + Authorization Code + PKCE, {@code AtlassianOAuthController})
 *     rather than a credential the person types in - false for every other
 *     entry, including every {@link #dynamicCommand}/{@link #dynamicUrl}
 *     entry that collects a static value via {@link #requiredEnvVars}. The
 *     frontend must check this BEFORE the generic dynamic-connect-form
 *     branch.
 * @param dynamicUrl the fixed remote (SSE / streamable-HTTP) endpoint the
 *     dynamic-connection form submits as {@code url} instead of a subprocess
 *     {@code command} - for a hosted server with one well-known address (e.g.
 *     Atlassian's {@code https://mcp.atlassian.com/v1/mcp}) where the person
 *     only needs to supply credentials, not a launch command. Mutually
 *     exclusive with {@link #dynamicCommand} in practice (the frontend's
 *     connect form picks whichever one is set); null for every STDIO entry.
 *     Without this, a {@code ready=false} entry with no {@link
 *     #dynamicCommand} fell through to the plain one-click Add button (same
 *     path as a {@code ready=true} entry), silently registering a
 *     credential-less, unconfigurable reference to a connection that was
 *     never actually wired anywhere - see the {@code atlassian} entry's
 *     history in {@code McpCatalogService} for the bug this fixes.
 * @param verified marketplace badge: the platform curated and vetted this
 *     entry. Every catalog entry is verified BY CONSTRUCTION - the catalog is
 *     a fixed staff-maintained list ({@code McpCatalogService}), not a live
 *     registry crawl, which is exactly what distinguishes it from the
 *     unvetted {@code /api/mcp/registry/search} results (those require staff
 *     review before a tenant can connect a STDIO server - see {@code
 *     McpReviewStatus}). The field exists so the marketplace surface can
 *     render the badge and so a future community-submitted, NOT-yet-vetted
 *     catalog entry can carry {@code false}.
 * @param oauthProviderId null for every entry except one whose connection is
 *     a one-click OAuth provider connection from the provider registry
 *     ({@code OAuthProviderRegistry}, {@code POST /api/mcp/oauth/{id}/authorize})
 *     rather than Atlassian's hard-wired flow; always paired with
 *     {@link #requiresOAuth} {@code true}. The frontend sends the person
 *     through that provider's flow when this is set.
 */
public record McpCatalogEntry(
    String key,
    String name,
    McpServerType type,
    String description,
    String iconKey,
    String docsUrl,
    boolean ready,
    List<String> requiredEnvVars,
    String yamlSnippet,
    String dynamicCommand,
    List<String> dynamicArgs,
    String dynamicUrl,
    boolean requiresOAuth,
    boolean verified,
    String oauthProviderId
) {
    /** Compatibility constructor for every entry written before the provider registry - not backed by a registry provider. */
    public McpCatalogEntry(
        String key, String name, McpServerType type, String description, String iconKey,
        String docsUrl, boolean ready, List<String> requiredEnvVars, String yamlSnippet,
        String dynamicCommand, List<String> dynamicArgs, String dynamicUrl, boolean requiresOAuth, boolean verified
    ) {
        this(key, name, type, description, iconKey, docsUrl, ready, requiredEnvVars, yamlSnippet,
            dynamicCommand, dynamicArgs, dynamicUrl, requiresOAuth, verified, null);
    }

    /** Compatibility constructor for the pre-#424 6-required-field shape - no dynamic-connect form offered. */
    public McpCatalogEntry(
        String key, String name, McpServerType type, String description, String iconKey,
        String docsUrl, boolean ready, List<String> requiredEnvVars, String yamlSnippet
    ) {
        this(key, name, type, description, iconKey, docsUrl, ready, requiredEnvVars, yamlSnippet, null, null, null, false, true);
    }

    /** Compatibility constructor for the #424 STDIO dynamic-connect shape - no remote URL offered. */
    public McpCatalogEntry(
        String key, String name, McpServerType type, String description, String iconKey,
        String docsUrl, boolean ready, List<String> requiredEnvVars, String yamlSnippet,
        String dynamicCommand, List<String> dynamicArgs
    ) {
        this(key, name, type, description, iconKey, docsUrl, ready, requiredEnvVars, yamlSnippet, dynamicCommand, dynamicArgs, null, false, true);
    }

    /** Convenience constructor for a remote (SSE / streamable-HTTP) dynamic-connect entry - no subprocess command/args, not OAuth-backed. */
    public McpCatalogEntry(
        String key, String name, McpServerType type, String description, String iconKey,
        String docsUrl, boolean ready, List<String> requiredEnvVars, String yamlSnippet,
        String dynamicUrl
    ) {
        this(key, name, type, description, iconKey, docsUrl, ready, requiredEnvVars, yamlSnippet, null, null, dynamicUrl, false, true);
    }
}
