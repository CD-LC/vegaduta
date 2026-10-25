package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpEdgeEligibilityRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.dto.McpServerToggleRequest;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/registry")
public class McpRegistryController {

    private final McpRegistryService service;
    private final McpCatalogService catalogService;
    private final OfficialMcpRegistryClient officialRegistryClient;
    // Optional - only bound when core/agents' DynamicMcpConnectionManager
    // bean exists (it always does in the full app; ObjectProvider just keeps
    // this module compilable/testable standalone without that dependency).
    // See McpDynamicConnector's javadoc for why this crosses the module
    // boundary this way instead of a hard Maven dependency.
    private final ObjectProvider<McpDynamicConnector> connector;
    // Bug fix (2026-08-15, direct report - a save against a slow/unreachable
    // remote MCP server (Atlassian's mcp.atlassian.com 404ing on a bad
    // credential type) came back as a fully generic "service temporarily
    // unavailable" toast with no way to tell what actually failed): register()
    // and toggle() used to call McpDynamicConnector#connect(UUID) INLINE, on
    // the request thread, before returning their HTTP response. Blocking the
    // HTTP response on a slow remote handshake held the POST/PATCH open long
    // enough to race whatever shorter timeout sits in front of this service
    // (the browser's own fetch ceiling, a reverse proxy, Cloudflare Tunnel) -
    // losing that race produces a raw connection-reset/timeout on the
    // frontend, which api.ts's toJson() legitimately has no way to
    // distinguish from "core process is actually down", hence the misleading
    // blanket message. Every connect() call below now goes through this
    // dispatcher instead: the HTTP response returns immediately with the
    // pre-connect status (exactly what this controller's own javadoc already
    // documented as the intended contract - "frontend re-polls list() to see
    // CONNECTED/ERROR land"). Extracted into its own component
    // (McpConnectDispatcher) so AtlassianOAuthController's callback can reuse
    // the identical tenant-context-propagating logic instead of duplicating it.
    private final McpConnectDispatcher connectDispatcher;

    // Optional (setter-injected so the constructor signature the unit tests build stays unchanged): when
    // present, catalog entries backed by a one-click OAuth provider are listed only while that provider
    // is configured - see #catalog.
    private com.agenticai.core.mcp.oauth.OAuthProviderRegistry oauthProviderRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOAuthProviderRegistry(com.agenticai.core.mcp.oauth.OAuthProviderRegistry registry) {
        this.oauthProviderRegistry = registry;
    }

    // Same setter-injected-optional shape as the field above (keeps the unit-test constructor
    // unchanged): the dead-static-reference repair the explicit-connect path runs first - see
    // #connectNow.
    private McpStaticReferenceUpgrader staticReferenceUpgrader;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStaticReferenceUpgrader(McpStaticReferenceUpgrader upgrader) {
        this.staticReferenceUpgrader = upgrader;
    }

    public McpRegistryController(
        McpRegistryService service,
        McpCatalogService catalogService,
        OfficialMcpRegistryClient officialRegistryClient,
        ObjectProvider<McpDynamicConnector> connector,
        McpConnectDispatcher connectDispatcher
    ) {
        this.service = service;
        this.catalogService = catalogService;
        this.officialRegistryClient = officialRegistryClient;
        this.connector = connector;
        this.connectDispatcher = connectDispatcher;
    }

    /**
     * Live search over the official public MCP Registry
     * (registry.modelcontextprotocol.io) - the "Browse registry" feature, so a
     * tenant can find any of the thousands of published MCP servers, not just
     * the curated {@link #catalog()}. Each result already carries a resolved,
     * launch-ready command/args/env (or remote URL) mapped from the registry's
     * raw package schema (see {@code RegistryServerMapper}), so "Add" reuses
     * the same {@link #register} path as everything else. Never errors: a
     * slow/down registry yields an empty page flagged {@code unavailable}
     * (which the UI must distinguish from a genuine zero-match), never a
     * broken Tools screen.
     *
     * <p>Matching runs locally over a cached catalog snapshot rather than
     * being forwarded to the registry's own name-only {@code ?search=} - see
     * {@link OfficialMcpRegistryClient} for why (the registry is slow enough
     * that live per-keystroke calls mostly timed out) and {@link
     * com.agenticai.core.mcp.registry.RegistryQueryMatcher} for the rules
     * (multi-term, wildcard-capable, matches description as well as name).
     *
     * @param q      what to search for: space-separated terms, all of which
     *               must match, each a case-insensitive substring of the
     *               server's name/title/description/repo URL, with {@code *}
     *               and {@code ?} honoured as wildcards. Blank lists all
     * @param cursor pagination cursor from a previous page's {@code
     *               nextCursor}; blank for the first page
     */
    @GetMapping("/search")
    public RegistrySearchResponse search(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "cursor", required = false) String cursor
    ) {
        RegistrySearchResponse response = officialRegistryClient.search(q, cursor);
        // RegistryServerMapper is deliberately catalog-blind (pure/stateless,
        // no Spring state - see its class javadoc), so it can only leave a
        // placeholder reviewRequired on STDIO results. Final enrichment pass
        // here, where McpCatalogService is available: a STDIO result whose
        // name matches a curated catalog entry connects immediately
        // (McpReviewStatus.NOT_REQUIRED) exactly like adding it from the
        // catalog itself would - every other STDIO result requires staff
        // review. Remote results are untouched (their mapper output - false -
        // is already final; see RegistrySearchResult#withReviewRequired).
        List<RegistrySearchResult> enriched = response.results().stream()
            .map(r -> r.type() == McpServerType.STDIO
                // SEC-002: exact launch-spec match, never the name alone - a
                // registry server reusing a catalog name with its own package
                // is unvetted. Mirrors McpRegistryService#register's decision.
                ? r.withReviewRequired(!StringUtils.hasText(r.command()) || !catalogService.isStrictCatalogMatch(
                    r.name(), r.type(), r.command(), r.args(),
                    r.envVars() == null ? List.of() : r.envVars().stream()
                        .filter(v -> v != null && v.required() && !v.positional())
                        .map(v -> v.name()).toList(),
                    null))
                : r)
            .toList();
        return new RegistrySearchResponse(enriched, response.nextCursor(), response.unavailable());
    }

    /**
     * The curated "add without typing" gallery - see McpCatalogService's
     * javadoc for why this is a fixed list rather than a live registry
     * fetch. Separate from {@link #list()} (this tenant's actual
     * registrations): the catalog is the same for every tenant, the
     * registration list is per-tenant-schema data.
     */
    @GetMapping("/catalog")
    public List<McpCatalogEntry> catalog() {
        List<McpCatalogEntry> all = catalogService.list();
        if (oauthProviderRegistry == null) {
            return all;
        }
        // An entry whose connection is a one-click OAuth provider flow must never be offered while that
        // provider cannot actually connect (feature off, no client id/secret, no callback base).
        return all.stream()
            .filter(e -> e.oauthProviderId() == null
                || oauthProviderRegistry.find(e.oauthProviderId()).map(p -> p.configured()).orElse(false))
            .toList();
    }

    /**
     * Registers/updates a server. When the request carries a {@code
     * command} (a dynamic connection, not a reference to something already
     * wired in {@code application-mcp.yml}), this also kicks off a real
     * connection attempt immediately - #424/"how client can configure and
     * use it during runtime": saving your Brave API key here is the whole
     * setup, no YAML edit, no restart. The response's {@code
     * connectionStatus} still reads NOT_CONFIGURED/CONNECTING at the moment
     * this call returns (the connect attempt runs synchronously right
     * after saving, but the response body was already built from the
     * pre-connect state) - the frontend re-polls {@link #list()} (or calls
     * {@link #connectNow}) to see CONNECTED/ERROR land.
     */
    @PostMapping
    public ResponseEntity<McpServerRegistrationResponse> register(@Valid @RequestBody McpServerRegistrationRequest request) {
        McpServerRegistrationResponse saved = service.register(request);
        // Decide from the SAVED registration, not the request: a name-only request can land as a
        // dynamic row (McpRegistryService#upgradeUnservedStaticReference turns a "ready" catalog
        // reference whose static server isn't servable into a per-tenant connection) and then the
        // connect must happen here or the row sits NOT_CONFIGURED forever.
        boolean dynamic = saved.dynamic();
        if (dynamic && request.enabled()) {
            McpDynamicConnector c = connector.getIfAvailable();
            if (c != null) {
                connectDispatcher.dispatch(c, saved.id());
            }
        } else if (!dynamic && request.enabled()) {
            // Bug fix (2026-08-07, direct report - "puppeteer enabled, still
            // not available to agents"): a STATIC (YAML-wired) catalog entry
            // added via the "Activate" one-click flow (McpPanel.tsx
            // addFromCatalog - no command/url in the request body) never hit
            // this branch, so of the two places that evict
            // AgentToolProvider's MCP tool cache (see #toggle's 2026-08-06
            // fix and its comment above), first-time Add was the one sibling
            // path that got missed. If the tenant already had another static
            // server enabled (cache warm), the newly-added server stayed
            // invisible to agents for up to mcpDiscoveryCacheTtl (default
            // 20s) with no forced-retry path - evict eagerly here too so a
            // freshly-added static server is picked up on the very next
            // resolveToolsFor call instead of waiting out the TTL.
            McpDynamicConnector c = connector.getIfAvailable();
            if (c != null) {
                c.evictToolCache();
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<McpServerRegistrationResponse> list() {
        return service.listAll();
    }

    @PatchMapping("/{id}")
    public McpServerRegistrationResponse toggle(@PathVariable UUID id, @Valid @RequestBody McpServerToggleRequest request) {
        McpServerRegistrationResponse updated = service.setEnabled(id, request.enabled());
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            if (updated.dynamic()) {
                // connect()/disconnect() evict AgentToolProvider's tool cache
                // themselves on completion - see McpDynamicConnector#evictToolCache.
                if (request.enabled()) {
                    connectDispatcher.dispatch(c, id);
                } else {
                    c.disconnect(id);
                }
            } else {
                // Bug fix (2026-08-06, direct report): a STATIC (YAML-wired)
                // registration is already connected at JVM startup - toggling
                // it has no live connection to (re)establish, so nothing else
                // on this path would ever evict AgentToolProvider's cache.
                // Without this, the toggle took effect in the DB immediately
                // but agents (hosted AND edge - same shared cache) kept seeing
                // the pre-toggle tool list for up to mcpDiscoveryCacheTtl.
                c.evictToolCache();
            }
        }
        return updated;
    }

    /**
     * Edge tool-access consent system (2026-08-06) - the admin/platform tier
     * (layer 1 of 2, see {@code McpServerRegistration#edgeEligible}'s
     * javadoc). The OWNING tenant's own admin decides whether THIS
     * registration's tools may ever be offered to an edge (on-device)
     * session - independent of {@link #toggle}'s hosted-availability flag,
     * and never sufficient on its own: layer 2, the end-user's own per-tool
     * consent, is enforced entirely client-side and has no representation
     * here. Connecting/disconnecting a dynamic registration is unaffected by
     * this call - only which of its ALREADY-connected tools an edge session
     * may see.
     */
    @PatchMapping("/{id}/edge-eligible")
    public McpServerRegistrationResponse setEdgeEligible(
        @PathVariable UUID id, @Valid @RequestBody McpEdgeEligibilityRequest request
    ) {
        return service.setEdgeEligible(id, request.edgeEligible());
    }

    /**
     * Explicit "try connecting again" - for retrying after fixing a bad API
     * key without re-submitting the whole form. Dispatched the same way as
     * {@link #register}/{@link #toggle} (bug fix 2026-08-15, see {@link
     * #connectDispatcher}'s field javadoc) - a slow/unreachable remote
     * server previously blocked this request exactly as it blocked those.
     * The response below reflects the PRE-connect state, same documented
     * "frontend re-polls list()" contract as {@link #register}.
     */
    @PostMapping("/{id}/connect")
    public McpServerRegistrationResponse connectNow(@PathVariable UUID id) {
        // Repair first: a dead static-reference registration (enabled while the deployment's
        // shared static server can't serve it) upgrades to its catalog launch spec here, so the
        // connect below has a real dynamic config to open instead of skipping quietly.
        McpServerRegistrationResponse updated =
            staticReferenceUpgrader == null
                ? service.listAll().stream()
                    .filter(r -> r.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new McpServerRegistrationNotFoundException(id))
                : staticReferenceUpgrader.ensureConnectable(id);
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            connectDispatcher.dispatch(c, id);
        }
        return updated;
    }

    /**
     * Removes a registration outright. Previously there was no way to get rid
     * of a bad/duplicate/unwanted server short of leaving it disabled
     * forever - #edit-delete-gap. Tears down any live connection FIRST (same
     * "disconnect() before the row disappears" ordering {@link #toggle}'s
     * disable branch already uses), so a dynamic registration never leaves an
     * orphaned {@code McpSyncClient} process/tool-callback behind after its
     * row is gone.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            c.disconnect(id);
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
