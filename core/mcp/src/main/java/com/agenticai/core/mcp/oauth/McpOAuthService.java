package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.mcp.oauth.McpOAuthDiscovery.AuthServerMetadata;
import com.agenticai.core.mcp.oauth.McpOAuthDiscovery.Discovered;
import com.agenticai.core.mcp.oauth.McpOAuthSharedStore.CachedToken;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generic OAuth connect/refresh orchestration for ANY remote MCP server that
 * implements the MCP authorization spec (RFC 9728 -> RFC 8414 -> client
 * registration -> PKCE auth-code -> refresh). Per-tenant, per-server: each
 * tenant registers its own client with each authorization server (the
 * spec-recommended model; no platform-wide shared client as
 * {@code AtlassianOAuthService} uses).
 *
 * <p><b>Client identity.</b> When the authorization server advertises
 * {@code client_id_metadata_document_supported} AND the platform publishes its
 * client metadata document ({@link McpOAuthClientMetadata}, off by default), the
 * document's URL is the {@code client_id} - no registration call. Otherwise RFC
 * 7591 dynamic registration, whose result is stored and reused.
 *
 * <p>OFF by default ({@code app.mcp.oauth.enabled=false}); every public entry
 * point throws {@link McpOAuthException} when disabled, and
 * {@link McpOAuthController} answers 404. The connect path wires in through
 * {@link CompositeMcpOAuthTokenSource} (a registration stamped with
 * {@link #MARKER} gets {@link #currentAccessToken} as its bearer).
 *
 * <p><b>Security posture</b> (each point has a test):
 * <ul>
 *   <li>SSRF: every discovery / registration / token request goes through
 *       {@link McpOAuthHttp} ({@code McpRemoteUrlGuard}, https only, no
 *       redirects, capped bodies) and every endpoint a metadata document
 *       advertises is vetted at discovery time.</li>
 *   <li>The {@code state} ({@link McpOAuthStateCodec}) is an encrypted, expiring,
 *       single-use blob binding tenant + user + server + PKCE verifier + issuer
 *       + client + redirect URI; a callback seen on another tenant's session is
 *       refused.</li>
 *   <li>Audience: the {@code resource} indicator is the canonical URL of a
 *       resource that covers the server URL the tenant typed
 *       ({@link McpOAuthUrls#covers}); a token is only ever released to a
 *       registration URL that same resource covers.</li>
 *   <li>Mix-up: RFC 9207 {@code iss} is checked against the discovered issuer
 *       and is REQUIRED when the server advertised support.</li>
 *   <li>Scope minimisation: only scopes from the 401 challenge, else the
 *       resource's {@code scopes_supported}; a caller cannot widen them.</li>
 *   <li>Secrets: client secrets, refresh tokens and non-refreshable access
 *       tokens are encrypted ({@link EncryptionService}) before they reach the
 *       store; refreshable access tokens live in the {@link McpOAuthSharedStore}
 *       (per-JVM by default, encrypted in Valkey with {@code
 *       app.mcp.oauth.store=redis}); nothing secret is ever logged.</li>
 * </ul>
 *
 * <p><b>Refresh.</b> Single-flight per (tenant, resource) - cross-replica when
 * {@code app.mcp.oauth.store=redis} - refreshing when the
 * cached token is within {@value #EXPIRY_SAFETY_MARGIN_SECONDS}s of expiry.
 * A permanent rejection ({@code invalid_grant} etc.) strips the tokens and marks
 * the connection {@link McpOAuthConnection#needsReauth() needs-reauth} - the
 * console shows "re-authorize" - rather than failing the same way forever; any
 * other failure is transient and propagated untouched.
 */
@Service
public class McpOAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);

    /** {@code mcp_server_registration.oauth_provider} marker (VARCHAR(32)) of a registration that uses this service's token. */
    public static final String MARKER = "oauth-remote";

    static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;
    private static final long DEFAULT_STATE_TTL_SECONDS = 600;
    private static final String CLIENT_NAME = "VegaDuta";
    private static final String TEST_CONSOLE_REDIRECT = "https://app.example.com/tools";

    /** Token-endpoint errors that mean the connection (or the registered client) is gone for good: re-consent needed. */
    private static final Set<String> PERMANENT_ERRORS =
        Set.of("invalid_grant", "invalid_client", "unauthorized_client", "invalid_refresh_token", "token_revoked");

    private final McpOAuthDiscovery discovery;
    private final McpOAuthClient client;
    private final McpOAuthConnectionStore store;
    private final EncryptionService encryptionService;
    private final McpOAuthClientMetadata clientMetadata;
    private final OAuthRedirectPolicy redirectPolicy;
    private final McpRemoteUrlGuard guard;
    private final Clock clock;
    private final McpOAuthStateCodec stateCodec;
    private final boolean enabled;
    private final String redirectUri;

    private final McpOAuthSharedStore shared;

    @Autowired
    public McpOAuthService(
        McpOAuthDiscovery discovery,
        McpOAuthClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryptionService,
        McpOAuthClientMetadata clientMetadata,
        McpRemoteUrlGuard guard,
        McpOAuthSharedStore shared,
        @Value("${app.mcp.oauth.enabled:false}") boolean enabled,
        @Value("${app.mcp.oauth.redirect-uri:}") String redirectUri,
        // Same console as the provider flow: no localhost default (RULE 0) - blank keeps the feature unusable.
        @Value("${app.mcp.oauth.provider-console-redirect-uri:}") String consoleRedirectUri,
        @Value("${app.mcp.oauth.provider-allowed-return-origins:}") String allowedReturnOrigins,
        @Value("${app.mcp.oauth.provider-state-ttl-seconds:" + DEFAULT_STATE_TTL_SECONDS + "}") long stateTtlSeconds
    ) {
        this(discovery, client, store, encryptionService, clientMetadata, guard, enabled, redirectUri,
            new OAuthRedirectPolicy(consoleRedirectUri, allowedReturnOrigins), Clock.systemUTC(), stateTtlSeconds,
            shared);
    }

    /** Test seam: explicit redirect policy and clock, per-JVM shared store. */
    McpOAuthService(
        McpOAuthDiscovery discovery,
        McpOAuthClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryptionService,
        McpOAuthClientMetadata clientMetadata,
        McpRemoteUrlGuard guard,
        boolean enabled,
        String redirectUri,
        OAuthRedirectPolicy redirectPolicy,
        Clock clock,
        long stateTtlSeconds
    ) {
        // The default store sweeps on the same clock the codec stamps expiries with.
        this(discovery, client, store, encryptionService, clientMetadata, guard, enabled, redirectUri,
            redirectPolicy, clock, stateTtlSeconds, new InMemoryMcpOAuthSharedStore(clock));
    }

    /** Test seam: the shared store is injectable (several services over one store = replicas). */
    McpOAuthService(
        McpOAuthDiscovery discovery,
        McpOAuthClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryptionService,
        McpOAuthClientMetadata clientMetadata,
        McpRemoteUrlGuard guard,
        boolean enabled,
        String redirectUri,
        OAuthRedirectPolicy redirectPolicy,
        Clock clock,
        long stateTtlSeconds,
        McpOAuthSharedStore shared
    ) {
        this.discovery = discovery;
        this.client = client;
        this.store = store;
        this.encryptionService = encryptionService;
        this.clientMetadata = clientMetadata;
        this.guard = guard;
        this.shared = shared;
        this.enabled = enabled;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.redirectPolicy = redirectPolicy;
        this.clock = clock;
        this.stateCodec = new McpOAuthStateCodec(encryptionService, clock, stateTtlSeconds, shared);
    }

    /** Minimal test seam: no CIMD, a fixed console redirect. */
    McpOAuthService(
        McpOAuthDiscovery discovery,
        McpOAuthClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryptionService,
        boolean enabled,
        String redirectUri
    ) {
        this(discovery, client, store, encryptionService, null, new McpRemoteUrlGuard(), enabled, redirectUri,
            new OAuthRedirectPolicy(TEST_CONSOLE_REDIRECT, ""), Clock.systemUTC(), DEFAULT_STATE_TTL_SECONDS);
    }

    /**
     * Switched on AND usable: a strict https public callback URL and a console to return to. Anything else is
     * "not enabled" (the controller 404s) rather than a half-working flow that strands the browser.
     */
    public boolean isEnabled() {
        return enabled
            && McpOAuthClientMetadata.isStrictRedirectUri(redirectUri, guard)
            && !redirectPolicy.defaultReturnUrl().isBlank();
    }

    /** True when the platform's client metadata document would be used as the client_id where the AS supports it. */
    public boolean cimdActive() {
        return clientMetadata != null && clientMetadata.active();
    }

    public record AuthorizationStart(String authorizationUrl, String resourceUrl, String issuer, String clientMode) {
    }

    public record CompletedConnection(String tenantId, String userId, String resourceUrl, String returnUrl) {
    }

    /** Console-facing state of one server URL. */
    public enum State {
        /** The server answers without credentials: no sign-in needed. */
        NOT_REQUIRED,
        /** The server demands OAuth and this tenant has not connected. */
        NEEDS_SIGNIN,
        CONNECTED,
        /** A connection existed but the authorization server permanently rejected it. */
        NEEDS_REAUTH,
        /** The server could not be reached to find out. */
        UNKNOWN
    }

    public record Status(State state, String resourceUrl, String issuer, String scope) {
    }

    // ------------------------------------------------------------ authorize

    /** Same as {@link #beginAuthorization(String, String, String)} with the default console return URL. */
    public AuthorizationStart beginAuthorization(String mcpServerUrl, String requestedScope) {
        return beginAuthorization(mcpServerUrl, requestedScope, null);
    }

    /**
     * Discovers, picks the client identity (CIMD, else a stored/dynamic registration), stores the pending
     * connection and returns the consent URL.
     *
     * @param requestedScope optional narrowing; every scope must be one the server offers
     * @param returnTo       console URL to land on afterwards; must be on the allow-list (IllegalArgumentException otherwise)
     */
    public AuthorizationStart beginAuthorization(String mcpServerUrl, String requestedScope, String returnTo) {
        requireEnabled();
        TenantContext ctx = requireCurrentTenant();
        String tenantId = ctx.getTenantId();
        String serverUrl = McpOAuthUrls.canonical(mcpServerUrl);
        String returnUrl = redirectPolicy.resolveReturnUrl(returnTo);

        Discovered found = discovery.discover(serverUrl);
        String resourceUrl = McpOAuthUrls.canonical(found.resource().resource());
        AuthServerMetadata as = found.authServer();
        String scope = effectiveScope(found.resource().allowedScopes(), requestedScope);

        boolean useCimd = cimdActive() && as.clientIdMetadataDocumentSupported();
        McpOAuthConnection existing = store.find(tenantId, resourceUrl).orElse(null);
        McpOAuthConnection connection;
        String mode;
        if (existing != null && reusable(existing, as, useCimd)) {
            connection = existing;
            mode = "existing";
        } else {
            Instant keep = existing != null && existing.needsReauth() ? McpOAuthConnection.REAUTH_MARKER : null;
            if (useCimd) {
                connection = new McpOAuthConnection(
                    tenantId, resourceUrl, as.issuer(), as.authorizationEndpoint(), as.tokenEndpoint(),
                    clientMetadata.clientIdUrl(), null, "none", redirectUri, scope, null, null, keep);
                mode = "cimd";
            } else {
                McpOAuthClient.Registration reg = client.register(as, redirectUri, CLIENT_NAME);
                connection = new McpOAuthConnection(
                    tenantId, resourceUrl, as.issuer(), as.authorizationEndpoint(), as.tokenEndpoint(),
                    reg.clientId(), reg.clientSecret() == null ? null : encryptionService.encrypt(reg.clientSecret()),
                    reg.tokenEndpointAuthMethod(), redirectUri, scope, null, null, keep);
                mode = "dcr";
            }
        }
        // Endpoints and scope are refreshed from the discovery that just ran; tokens stay as they were.
        connection = new McpOAuthConnection(
            connection.tenantId(), connection.resourceUrl(), as.issuer(), as.authorizationEndpoint(), as.tokenEndpoint(),
            connection.clientId(), connection.encryptedClientSecret(), connection.tokenEndpointAuthMethod(),
            connection.redirectUri(), scope, connection.encryptedRefreshToken(), connection.encryptedAccessToken(),
            connection.accessTokenExpiresAt());
        store.save(connection);

        McpOAuthPkce.Pair pkce = McpOAuthPkce.generate();
        String state = stateCodec.encode(tenantId, ctx.getSubject(), resourceUrl, pkce.verifier(), returnUrl,
            as.issuer(), as.issParameterSupported(), connection.clientId(), connection.redirectUri());
        String url = client.buildAuthorizationUrl(as, connection.clientId(), connection.redirectUri(), state,
            pkce.challenge(), resourceUrl, scope);
        log.info("MCP OAuth authorization started for tenant {} resource {} (client: {})", tenantId, resourceUrl, mode);
        return new AuthorizationStart(url, resourceUrl, as.issuer(), mode);
    }

    private boolean reusable(McpOAuthConnection existing, AuthServerMetadata as, boolean useCimd) {
        if (!existing.issuer().equals(as.issuer()) || !existing.redirectUri().equals(redirectUri)) {
            return false;
        }
        boolean existingIsCimd = existing.encryptedClientSecret() == null
            && existing.clientId().regionMatches(true, 0, "https://", 0, 8);
        return useCimd
            ? existing.clientId().equals(clientMetadata.clientIdUrl())
            : !existingIsCimd;
    }

    /**
     * Scope minimisation: the server's own challenge scope, else its {@code scopes_supported}; a caller may
     * narrow but never widen.
     */
    static String effectiveScope(List<String> allowed, String requested) {
        if (requested != null && !requested.isBlank()) {
            List<String> wanted = new ArrayList<>();
            for (String s : requested.trim().split("\\s+")) {
                if (!allowed.contains(s)) {
                    throw new McpOAuthException("The scope '" + s + "' is not offered by this MCP server.");
                }
                wanted.add(s);
            }
            return String.join(" ", wanted);
        }
        return allowed.isEmpty() ? null : String.join(" ", allowed);
    }

    // ------------------------------------------------------------- callback

    /** Callback half without an RFC 9207 {@code iss} parameter (kept for callers that have none). */
    public CompletedConnection completeAuthorization(String code, String state) {
        return completeAuthorization(code, state, null);
    }

    /**
     * Callback half: needs no ambient tenant - the verified state carries it.
     *
     * @param iss the RFC 9207 {@code iss} response parameter, or null when the AS sent none
     * @throws OAuthStateException state tampered / expired / replayed / for another tenant, or wrong issuer
     */
    public CompletedConnection completeAuthorization(String code, String state, String iss) {
        requireEnabled();
        McpOAuthStateCodec.Payload s = stateCodec.decode(state);
        // An authenticated browser session of ANOTHER tenant must not be able to complete this flow. (The usual
        // callback has no session at all - the redirect from the AS is unauthenticated - and then the state alone
        // identifies the tenant; see McpOAuthStateCodec.)
        Optional<TenantContext> ambient = TenantContext.current();
        if (ambient.isPresent() && !s.tenantId().equals(ambient.get().getTenantId())) {
            throw new OAuthStateException(OAuthStateException.TENANT_MISMATCH,
                "This authorization was started by a different workspace.");
        }
        stateCodec.consume(s);
        // RFC 9207: an AS that said it sends `iss` must send it; any `iss` sent must be the one we discovered.
        if (iss != null ? !iss.equals(s.issuer()) : s.issRequired()) {
            log.warn("MCP OAuth callback rejected for tenant {}: issuer check failed (RFC 9207)", s.tenantId());
            throw new OAuthStateException(OAuthStateException.ISSUER_MISMATCH,
                "The authorization response did not come from the expected authorization server.");
        }
        if (code == null || code.isBlank()) {
            throw new McpOAuthException("The authorization server returned no authorization code.");
        }
        McpOAuthConnection connection = store.find(s.tenantId(), s.resourceUrl())
            .filter(c -> c.clientId().equals(s.clientId()) && c.issuer().equals(s.issuer()))
            .orElseThrow(() -> new OAuthStateException(OAuthStateException.NO_PENDING_CONNECTION,
                "No pending connection for this server - please try connecting again."));
        // The exchange uses exactly the client + redirect URI + resource the authorization request used.
        McpOAuthClient.TokenResponse tokens = client.exchangeCode(
            asMetadata(connection), connection.clientId(), decryptOrNull(connection.encryptedClientSecret()),
            connection.tokenEndpointAuthMethod(), s.redirectUri(), code, s.codeVerifier(), connection.resourceUrl());
        shared.withRefreshLock(s.tenantId(), connection.resourceUrl(), () -> {
            if (tokens.refreshToken() != null) {
                store.save(connection.withTokens(encryptionService.encrypt(tokens.refreshToken()), null, null));
                shared.putToken(s.tenantId(), connection.resourceUrl(), cached(tokens));
            } else {
                // No refresh token: the access token IS the credential - persist it (encrypted) until it expires.
                Instant expiry = clock.instant().plusSeconds(Math.max(1, tokens.expiresInSeconds()));
                store.save(connection.withTokens(null, encryptionService.encrypt(tokens.accessToken()), expiry));
                shared.removeToken(s.tenantId(), connection.resourceUrl());
            }
            return null;
        });
        log.info("MCP OAuth connected for tenant {} resource {} (refreshable={})",
            s.tenantId(), connection.resourceUrl(), tokens.refreshToken() != null);
        return new CompletedConnection(s.tenantId(), s.userId(), connection.resourceUrl(), s.returnUrl());
    }

    /**
     * Where the callback should send the browser: the return URL inside a VALID state (verified, not consumed),
     * otherwise the configured console default. Never throws - the callback must always have somewhere to land.
     */
    public String returnUrlFor(String state) {
        try {
            String url = stateCodec.decode(state).returnUrl();
            return url == null || url.isBlank() ? redirectPolicy.defaultReturnUrl() : url;
        } catch (RuntimeException e) {
            return redirectPolicy.defaultReturnUrl();
        }
    }

    // --------------------------------------------------------- access token

    /**
     * Live access token for the CURRENT tenant and the MCP server at {@code serverUrl}, refreshing only when the
     * cached one is absent or near expiry. The connection is the one whose resource COVERS {@code serverUrl}
     * ({@link McpOAuthUrls#candidateResources}); a URL no connection covers never gets a token.
     *
     * @throws McpOAuthException {@code not_connected}, {@code reauth_required} (permanently rejected), or a
     *                           transient failure (propagated untouched)
     */
    public String currentAccessToken(String serverUrl) {
        requireEnabled();
        String tenantId = requireCurrentTenant().getTenantId();
        List<String> candidates = McpOAuthUrls.candidateResources(serverUrl);
        Instant now = clock.instant();
        for (String candidate : candidates) {
            Optional<CachedToken> hit = shared.token(tenantId, candidate);
            if (hit.isPresent() && hit.get().validAt(now)) {
                return hit.get().accessToken();
            }
        }
        for (String candidate : candidates) {
            Optional<McpOAuthConnection> found = store.find(tenantId, candidate);
            if (found.isPresent()) {
                return tokenFor(tenantId, found.get());
            }
        }
        throw new McpOAuthException("Not connected to " + serverUrl + " - connect it first.", null, "not_connected");
    }

    private String tokenFor(String tenantId, McpOAuthConnection initial) {
        return shared.withRefreshLock(tenantId, initial.resourceUrl(), () -> {
            Optional<CachedToken> hit = shared.token(tenantId, initial.resourceUrl());
            if (hit.isPresent() && hit.get().validAt(clock.instant())) {
                return hit.get().accessToken();
            }
            // Re-read under the lock: a concurrent refresh may have rotated the refresh token.
            McpOAuthConnection connection = store.find(tenantId, initial.resourceUrl()).orElse(initial);
            if (connection.needsReauth()) {
                throw new McpOAuthException("The connection to " + connection.resourceUrl()
                    + " needs to be authorized again.", null, "reauth_required");
            }
            if (!connection.isConnected()) {
                throw new McpOAuthException("Not connected to " + connection.resourceUrl() + " - connect it first.", null, "not_connected");
            }
            if (connection.encryptedRefreshToken() == null) {
                return storedAccessToken(tenantId, connection);
            }
            return refreshAndStore(tenantId, connection);
        });
    }

    private String storedAccessToken(String tenantId, McpOAuthConnection connection) {
        Instant expiry = connection.accessTokenExpiresAt();
        Instant now = clock.instant();
        if (expiry != null && !now.isBefore(expiry.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))) {
            store.save(connection.markedForReauth());
            shared.removeToken(tenantId, connection.resourceUrl());
            log.info("MCP OAuth access token expired with no refresh token for tenant {} resource {} - marked needs-reauth",
                tenantId, connection.resourceUrl());
            throw new McpOAuthException("The access token for " + connection.resourceUrl()
                + " expired and cannot be refreshed - authorize again.", null, "reauth_required");
        }
        String token = decryptStored(connection.encryptedAccessToken());
        shared.putToken(tenantId, connection.resourceUrl(),
            new CachedToken(token, expiry == null ? null : expiry.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS)));
        return token;
    }

    private String refreshAndStore(String tenantId, McpOAuthConnection connection) {
        McpOAuthClient.TokenResponse refreshed;
        try {
            refreshed = client.refresh(
                asMetadata(connection), connection.clientId(), decryptOrNull(connection.encryptedClientSecret()),
                connection.tokenEndpointAuthMethod(), decryptStored(connection.encryptedRefreshToken()),
                connection.resourceUrl());
        } catch (McpOAuthException e) {
            if (e.getOauthErrorCode() != null && PERMANENT_ERRORS.contains(e.getOauthErrorCode())) {
                // Not silent: the row says "needs re-authorization" (console + status endpoint) and the cache is cleared.
                store.save(connection.markedForReauth());
                shared.removeToken(tenantId, connection.resourceUrl());
                log.warn("MCP OAuth refresh rejected ({}) for tenant {} resource {} - connection marked needs-reauth",
                    e.getOauthErrorCode(), tenantId, connection.resourceUrl());
                throw new McpOAuthException("The connection to " + connection.resourceUrl()
                    + " was revoked or expired - authorize again.", e, "reauth_required");
            }
            throw e;
        }
        if (refreshed.refreshToken() != null) {
            String rotated = encryptionService.encrypt(refreshed.refreshToken());
            store.save(connection.withRefreshToken(rotated));
        }
        shared.putToken(tenantId, connection.resourceUrl(), cached(refreshed));
        return refreshed.accessToken();
    }

    // --------------------------------------------------------------- status

    /**
     * What the console shows for {@code serverUrl}: a stored connection answers without any network call;
     * otherwise one SSRF-guarded unauthenticated probe tells "no sign-in needed" from "needs sign-in".
     */
    public Status status(String serverUrl) {
        requireEnabled();
        String tenantId = requireCurrentTenant().getTenantId();
        String canonical = McpOAuthUrls.canonical(serverUrl);
        Instant now = clock.instant();
        for (String candidate : McpOAuthUrls.candidateResources(canonical)) {
            Optional<McpOAuthConnection> found = store.find(tenantId, candidate);
            if (found.isEmpty()) {
                continue;
            }
            McpOAuthConnection c = found.get();
            if (c.needsReauth()) {
                return new Status(State.NEEDS_REAUTH, c.resourceUrl(), c.issuer(), c.scope());
            }
            if (usable(c, now)) {
                return new Status(State.CONNECTED, c.resourceUrl(), c.issuer(), c.scope());
            }
            // A pending (registered, not yet consented) row: fall through to the probe.
        }
        McpOAuthDiscovery.Probe probe = discovery.probe(canonical);
        if (probe.status() == -1) {
            return new Status(State.UNKNOWN, canonical, null, null);
        }
        return new Status(probe.authRequired() ? State.NEEDS_SIGNIN : State.NOT_REQUIRED, canonical, null, null);
    }

    private static boolean usable(McpOAuthConnection c, Instant now) {
        if (c.encryptedRefreshToken() != null) {
            return true;
        }
        return c.encryptedAccessToken() != null && (c.accessTokenExpiresAt() == null || now.isBefore(c.accessTokenExpiresAt()));
    }

    // ----------------------------------------------------------- disconnect

    /**
     * Forgets the connection(s) covering {@code serverUrl} for the current tenant. Same per-resource lock as the
     * refresh path, so an in-flight refresh cannot write the row back after the delete.
     *
     * @return the resource URLs that were removed (empty = nothing was connected)
     */
    public List<String> disconnect(String serverUrl) {
        requireEnabled();
        String tenantId = requireCurrentTenant().getTenantId();
        List<String> removed = new ArrayList<>();
        for (String candidate : McpOAuthUrls.candidateResources(serverUrl)) {
            boolean removedCandidate = shared.withRefreshLock(tenantId, candidate, () -> {
                boolean present = store.find(tenantId, candidate).isPresent();
                if (present) {
                    store.delete(tenantId, candidate);
                }
                shared.removeToken(tenantId, candidate);
                return present;
            });
            if (removedCandidate) {
                removed.add(candidate);
            }
        }
        if (!removed.isEmpty()) {
            log.info("MCP OAuth disconnected for tenant {} resources {}", tenantId, removed);
        }
        return removed;
    }

    // -------------------------------------------------------------- helpers

    private static AuthServerMetadata asMetadata(McpOAuthConnection c) {
        return new AuthServerMetadata(c.issuer(), c.authorizationEndpoint(), c.tokenEndpoint(), null, List.of(), List.of());
    }

    private String decryptOrNull(String encrypted) {
        return encrypted == null ? null : decryptStored(encrypted);
    }

    /** An unreadable ciphertext (APP_ENCRYPTION_KEY mismatch) is a reconnect-required error, not a 500. */
    private String decryptStored(String ciphertext) {
        try {
            return encryptionService.decrypt(ciphertext);
        } catch (RuntimeException e) {
            throw new McpOAuthException("The stored credential could not be read - disconnect and connect again.", e, "token_unreadable");
        }
    }

    private CachedToken cached(McpOAuthClient.TokenResponse t) {
        return new CachedToken(t.accessToken(),
            clock.instant().plusSeconds(Math.max(0, t.expiresInSeconds() - EXPIRY_SAFETY_MARGIN_SECONDS)));
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new McpOAuthException("Generic MCP OAuth is not enabled on this deployment "
                + "(app.mcp.oauth.enabled / app.mcp.oauth.redirect-uri / app.mcp.oauth.provider-console-redirect-uri).",
                null, "feature_off");
        }
    }

    private static TenantContext requireCurrentTenant() {
        return TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("McpOAuthService called with no ambient TenantContext"));
    }
}
