package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.mcp.oauth.McpOAuthSharedStore.CachedToken;
import com.agenticai.core.mcp.oauth.OAuthProviderClient.RevokeOutcome;
import com.agenticai.core.mcp.oauth.OAuthProviderClient.TokenResult;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * One-click OAuth "Connect" for the providers in {@link OAuthProviderRegistry}:
 * authorization-code + PKCE, per-tenant durable token storage, automatic refresh
 * and revocation. The provider-registry generalisation of what
 * {@code AtlassianOAuthService} does for Atlassian and what {@link McpOAuthService}
 * does for spec-discovered MCP servers - same crypto ({@link EncryptionService}),
 * same store ({@link McpOAuthConnectionStore}), same PKCE helper
 * ({@link McpOAuthPkce}).
 *
 * <p><b>Flow.</b> {@link #beginAuthorization} (authenticated console call) mints an
 * encrypted, expiring, single-use {@link OAuthStateCodec state} bound to
 * tenant + user + nonce and returns the provider consent URL.
 * {@link #completeAuthorization} (the public provider redirect - no JWT) verifies
 * and consumes that state, exchanges the code and stores the tokens.
 *
 * <p><b>Storage.</b> One {@link McpOAuthConnection} per tenant and provider under
 * the pseudo resource {@code oauth-provider:<id>}. A refreshable connection
 * persists only the encrypted refresh token; its access token lives in the
 * {@link McpOAuthSharedStore} (per-JVM by default, encrypted in Valkey with
 * {@code app.mcp.oauth.store=redis}). A provider that issues no refresh token (GitHub OAuth apps,
 * Slack without rotation) has its encrypted access token persisted instead - it
 * is the credential. Platform client credentials are never copied per tenant:
 * they are read from configuration at refresh time. Tokens are never logged.
 * The store keys on the tenant, not the user, so connections are per tenant; the
 * user that consented is carried in the state and surfaced in the callback only.
 *
 * <p><b>Refresh.</b> {@link #currentAccessToken} refreshes only when the cached
 * token is absent or within {@value #EXPIRY_SAFETY_MARGIN_SECONDS}s of expiry,
 * single-flight per (tenant, provider): concurrent callers wait on one lock and
 * the first refresh serves them all - essential for providers whose refresh
 * tokens rotate (Zoom, Linear, Notion), where two parallel refreshes would burn
 * the token. The guard is cross-replica with {@code app.mcp.oauth.store=redis},
 * per-JVM otherwise. A permanent rejection
 * ({@code invalid_grant} and friends) clears the stored tokens so the UI shows
 * "reconnect" instead of failing the same way forever; any other failure is
 * transient and propagated untouched.
 *
 * <p>Implements {@link McpOAuthTokenSource}: a remote MCP registration whose
 * {@code oauthProvider} marker is {@code oauth:<id>} gets this connection's token
 * as its bearer (see {@code McpRemoteHeaders} in core/agents) - but only for the
 * provider's own MCP host, so a re-pointed registration cannot receive it.
 */
@Service
public class OAuthProviderService implements McpOAuthTokenSource {

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderService.class);

    /** Prefix of the pseudo resource URL the store is keyed by. */
    static final String RESOURCE_PREFIX = "oauth-provider:";
    /** Prefix of the {@code mcp_server_registration.oauth_provider} marker (column is VARCHAR(32); longest id is 15 chars). */
    public static final String MARKER_PREFIX = "oauth:";

    static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;
    /** Lifetime assumed for an access token that came with a refresh token but no expires_in (Notion). */
    static final long ASSUMED_REFRESHABLE_TTL_SECONDS = 3600;

    private static final Set<String> PERMANENT_REFRESH_ERRORS =
        Set.of("invalid_grant", "invalid_refresh_token", "token_revoked", "bad_refresh_token");

    private final OAuthProviderRegistry registry;
    private final OAuthProviderClient client;
    private final McpOAuthConnectionStore store;
    private final EncryptionService encryption;
    private final McpOAuthSharedStore shared;
    private final OAuthStateCodec stateCodec;
    private final OAuthRedirectPolicy redirectPolicy;
    private final Clock clock;

    @Autowired
    public OAuthProviderService(
        OAuthProviderRegistry registry,
        OAuthProviderClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryption,
        McpOAuthSharedStore shared,
        @Value("${app.mcp.oauth.provider-state-ttl-seconds:600}") long stateTtlSeconds,
        // No localhost default: a browser-facing URL must be configured (the registry then reports no_console_redirect).
        @Value("${app.mcp.oauth.provider-console-redirect-uri:}") String consoleRedirectUri,
        @Value("${app.mcp.oauth.provider-allowed-return-origins:}") String allowedReturnOrigins
    ) {
        this(registry, client, store, encryption, stateTtlSeconds,
            new OAuthRedirectPolicy(consoleRedirectUri, allowedReturnOrigins), Clock.systemUTC(), shared);
    }

    /** Test seam: explicit clock and redirect policy, per-JVM shared store. */
    OAuthProviderService(
        OAuthProviderRegistry registry,
        OAuthProviderClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryption,
        long stateTtlSeconds,
        OAuthRedirectPolicy redirectPolicy,
        Clock clock
    ) {
        // The default store sweeps on the same clock the codec stamps expiries with.
        this(registry, client, store, encryption, stateTtlSeconds, redirectPolicy, clock,
            new InMemoryMcpOAuthSharedStore(clock));
    }

    /** Test seam: the shared store is injectable (several services over one store = replicas). */
    OAuthProviderService(
        OAuthProviderRegistry registry,
        OAuthProviderClient client,
        McpOAuthConnectionStore store,
        EncryptionService encryption,
        long stateTtlSeconds,
        OAuthRedirectPolicy redirectPolicy,
        Clock clock,
        McpOAuthSharedStore shared
    ) {
        this.registry = registry;
        this.client = client;
        this.store = store;
        this.encryption = encryption;
        this.shared = shared;
        this.redirectPolicy = redirectPolicy;
        this.clock = clock;
        this.stateCodec = new OAuthStateCodec(encryption, clock, stateTtlSeconds, shared);
    }

    // ---------------------------------------------------------------- views

    /** What the console renders per provider. */
    public record ProviderView(
        String id,
        String name,
        String family,
        boolean configured,
        String notConfiguredReason,
        boolean connected,
        List<String> scopes,
        boolean revocable,
        boolean registersMcpServer,
        String redirectUri,
        String docsUrl
    ) {
    }

    public record AuthorizationStart(String authorizationUrl, String providerId) {
    }

    public record CompletedConnection(String tenantId, String userId, String providerId, String returnUrl) {
    }

    public record DisconnectResult(boolean wasConnected, RevokeOutcome providerRevocation) {
    }

    public boolean featureEnabled() {
        return registry.featureEnabled();
    }

    public List<ProviderView> listForCurrentTenant() {
        String tenantId = requireTenantId();
        Instant now = clock.instant();
        List<ProviderView> out = new ArrayList<>();
        for (Provider p : registry.all()) {
            boolean connected = registry.featureEnabled()
                && store.find(tenantId, resourceKey(p.id())).filter(c -> usable(c, now)).isPresent();
            out.add(new ProviderView(
                p.id(), p.spec().name(), p.spec().family(), p.configured(), p.notConfiguredReason(), connected,
                p.scopes(), p.spec().revokeStyle() != OAuthProviderSpec.RevokeStyle.NONE, p.hasMcpServer(),
                p.redirectUri(), p.spec().docsUrl()));
        }
        return out;
    }

    public Optional<Provider> provider(String providerId) {
        return registry.find(providerId);
    }

    // ------------------------------------------------------------ authorize

    /**
     * @param requestedReturnUrl console URL to land on afterwards; null for the configured default
     * @throws ResponseStatusException 404 unknown provider, 409 provider not configured
     * @throws IllegalArgumentException {@code requestedReturnUrl} is not on the allow-list (HTTP 400)
     */
    public AuthorizationStart beginAuthorization(String providerId, String requestedReturnUrl) {
        Provider p = requireConfigured(providerId);
        TenantContext ctx = TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("OAuthProviderService called with no ambient TenantContext"));
        String returnUrl = redirectPolicy.resolveReturnUrl(requestedReturnUrl);
        McpOAuthPkce.Pair pkce = McpOAuthPkce.generate();
        String state = stateCodec.encode(p.id(), ctx.getTenantId(), ctx.getSubject(), pkce.verifier(), returnUrl);
        return new AuthorizationStart(client.buildAuthorizationUrl(p, state, pkce.challenge()), p.id());
    }

    /** Callback half: needs no ambient tenant - the verified state carries it. */
    public CompletedConnection completeAuthorization(String providerId, String code, String state) {
        Provider p = requireConfigured(providerId);
        OAuthStateCodec.Payload s = stateCodec.decode(state, p.id());
        stateCodec.consume(s);
        if (code == null || code.isBlank()) {
            throw new McpOAuthException("The provider returned no authorization code.");
        }
        TokenResult tokens = client.exchangeCode(p, code, s.codeVerifier());
        McpOAuthConnection saved = persist(p, s.tenantId(), tokens);
        if (tokens.refreshToken() != null) {
            shared.putToken(s.tenantId(), p.id(), cached(tokens));
        } else {
            shared.removeToken(s.tenantId(), p.id());
        }
        log.info("OAuth provider {} connected for tenant {} (refreshable={})", p.id(), s.tenantId(), saved.encryptedRefreshToken() != null);
        return new CompletedConnection(s.tenantId(), s.userId(), p.id(), s.returnUrl());
    }

    /**
     * Where the callback should send the browser: the return URL inside a VALID state (verified, not consumed),
     * otherwise the configured console default. Never throws - the callback must always have somewhere to land.
     */
    public String returnUrlFor(String providerId, String state) {
        try {
            String id = registry.find(providerId).map(Provider::id).orElse(providerId);
            String url = stateCodec.decode(state, id).returnUrl();
            return url == null || url.isBlank() ? redirectPolicy.defaultReturnUrl() : url;
        } catch (RuntimeException e) {
            return redirectPolicy.defaultReturnUrl();
        }
    }

    private McpOAuthConnection persist(Provider p, String tenantId, TokenResult t) {
        Instant now = clock.instant();
        String encRefresh = t.refreshToken() == null ? null : encryption.encrypt(t.refreshToken());
        String encAccess = null;
        Instant accessExpiry = null;
        if (t.refreshToken() == null) {
            encAccess = encryption.encrypt(t.accessToken());
            accessExpiry = t.expiresInSeconds() == null ? null : now.plusSeconds(t.expiresInSeconds());
        }
        String scope = t.scope() == null || t.scope().isBlank() ? String.join(" ", p.scopes()) : t.scope();
        McpOAuthConnection connection = new McpOAuthConnection(
            tenantId, resourceKey(p.id()), p.id(), p.resolve(p.spec().authorizeUrl()), p.resolve(p.spec().tokenUrl()),
            p.clientId(), null, p.spec().clientAuth().name(), p.redirectUri(), scope, encRefresh, encAccess, accessExpiry);
        store.save(connection);
        return connection;
    }

    // -------------------------------------------------------- access tokens

    /**
     * Live access token for the CURRENT tenant and provider (see class javadoc for the refresh and single-flight rules).
     *
     * @throws McpOAuthException not connected / revoked ({@code oauthErrorCode} {@code not_connected} or the provider's permanent code) / transient failure
     */
    public String currentAccessToken(String providerId) {
        Provider p = registry.find(providerId)
            .orElseThrow(() -> new McpOAuthException("Unknown OAuth provider '" + providerId + "'.", null, "unknown_provider"));
        if (!registry.featureEnabled()) {
            throw new McpOAuthException("Provider OAuth connections are switched off on this deployment.", null, "feature_off");
        }
        String tenantId = requireTenantId();
        Optional<CachedToken> hit = shared.token(tenantId, p.id());
        if (hit.isPresent() && hit.get().validAt(clock.instant())) {
            return hit.get().accessToken();
        }
        return shared.withRefreshLock(tenantId, p.id(), () -> {
            Optional<CachedToken> hitUnderLock = shared.token(tenantId, p.id());
            if (hitUnderLock.isPresent() && hitUnderLock.get().validAt(clock.instant())) {
                return hitUnderLock.get().accessToken();
            }
            McpOAuthConnection connection = store.find(tenantId, resourceKey(p.id()))
                .filter(McpOAuthConnection::isConnected)
                .orElseThrow(() -> new McpOAuthException("Not connected to " + p.spec().name() + " - connect it first.", null, "not_connected"));
            if (connection.encryptedRefreshToken() == null) {
                return storedAccessToken(p, connection, tenantId);
            }
            return refreshAndStore(p, tenantId, connection);
        });
    }

    private String storedAccessToken(Provider p, McpOAuthConnection connection, String tenantId) {
        Instant expiry = connection.accessTokenExpiresAt();
        Instant now = clock.instant();
        if (expiry != null && !now.isBefore(expiry.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))) {
            shared.removeToken(tenantId, p.id());
            throw new McpOAuthException("The " + p.spec().name() + " access token expired and has no refresh token - reconnect required.", null, "expired");
        }
        String token = decryptStored(p, connection.encryptedAccessToken());
        shared.putToken(tenantId, p.id(),
            new CachedToken(token, expiry == null ? null : expiry.minusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS)));
        return token;
    }

    private String refreshAndStore(Provider p, String tenantId, McpOAuthConnection connection) {
        if (!p.configured()) {
            throw new McpOAuthException(p.spec().name() + " is no longer configured on this deployment, so its token cannot be refreshed.", null, "not_configured");
        }
        TokenResult refreshed;
        try {
            refreshed = client.refresh(p, decryptStored(p, connection.encryptedRefreshToken()));
        } catch (McpOAuthException e) {
            if (e.getOauthErrorCode() != null && PERMANENT_REFRESH_ERRORS.contains(e.getOauthErrorCode())) {
                store.save(connection.withTokens(null, null, null));
                shared.removeToken(tenantId, p.id());
                log.info("OAuth provider {} refresh token rejected for tenant {} - connection cleared", p.id(), tenantId);
                throw new McpOAuthException("The connection to " + p.spec().name() + " was revoked - reconnect required.", e, "invalid_grant");
            }
            throw e;
        }
        if (refreshed.refreshToken() != null) {
            String newEncrypted = encryption.encrypt(refreshed.refreshToken());
            if (!newEncrypted.equals(connection.encryptedRefreshToken())) {
                store.save(connection.withRefreshToken(newEncrypted));
            }
        }
        shared.putToken(tenantId, p.id(), cached(refreshed));
        return refreshed.accessToken();
    }

    // ----------------------------------------------------------- disconnect

    /**
     * Revokes at the provider (best effort, only when it is configured and has a
     * revocation endpoint) and ALWAYS forgets the local tokens.
     */
    public DisconnectResult disconnect(String providerId) {
        Provider p = registry.find(providerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown OAuth provider."));
        String tenantId = requireTenantId();
        // Same per-(tenant, provider) lock as the refresh path: an in-flight refresh of a rotating-token provider
        // must not finish AFTER the delete and upsert the row (and the cache) back. Reentrant, so the quiet
        // access-token lookup inside revokeAtProvider can take it again.
        return shared.withRefreshLock(tenantId, p.id(), () -> {
            Optional<McpOAuthConnection> existing = store.find(tenantId, resourceKey(p.id()));
            if (existing.isEmpty()) {
                shared.removeToken(tenantId, p.id());
                return new DisconnectResult(false, RevokeOutcome.NOT_SUPPORTED);
            }
            McpOAuthConnection connection = existing.get();
            RevokeOutcome outcome = RevokeOutcome.NOT_SUPPORTED;
            if (p.spec().revokeStyle() != OAuthProviderSpec.RevokeStyle.NONE && connection.isConnected()) {
                outcome = p.configured() ? revokeAtProvider(p, connection, providerId) : RevokeOutcome.FAILED;
            }
            store.delete(tenantId, resourceKey(p.id()));
            shared.removeToken(tenantId, p.id());
            log.info("OAuth provider {} disconnected for tenant {} (provider revocation: {})", p.id(), tenantId, outcome);
            return new DisconnectResult(true, outcome);
        });
    }

    private RevokeOutcome revokeAtProvider(Provider p, McpOAuthConnection connection, String providerId) {
        // Disconnect must ALWAYS reach store.delete: a ciphertext this deployment cannot decrypt (APP_ENCRYPTION_KEY
        // mismatch) means there is no usable token to revoke, not that the tenant is stuck connected.
        String refresh = null;
        if (connection.encryptedRefreshToken() != null) {
            try {
                refresh = encryption.decrypt(connection.encryptedRefreshToken());
            } catch (RuntimeException e) {
                log.warn("OAuth provider {} refresh token for a disconnecting tenant could not be decrypted - skipping provider revocation", p.id());
                return RevokeOutcome.FAILED;
            }
        }
        String token;
        switch (p.spec().revokeToken()) {
            case REFRESH:
                token = refresh;
                break;
            case PREFER_REFRESH:
                token = refresh != null ? refresh : accessTokenQuietly(providerId);
                break;
            default:
                token = accessTokenQuietly(providerId);
                break;
        }
        return client.revoke(p, token);
    }

    /** Decrypts a stored token; an unreadable ciphertext (key mismatch) becomes a reconnect-required error, not an IllegalStateException/500. */
    private String decryptStored(Provider p, String ciphertext) {
        try {
            return encryption.decrypt(ciphertext);
        } catch (RuntimeException e) {
            throw new McpOAuthException("The stored " + p.spec().name() + " token could not be read - disconnect and reconnect required.", e, "token_unreadable");
        }
    }

    private String accessTokenQuietly(String providerId) {
        try {
            return currentAccessToken(providerId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------ McpOAuthTokenSource

    @Override
    public boolean handles(String oauthProviderMarker) {
        return oauthProviderMarker != null && oauthProviderMarker.startsWith(MARKER_PREFIX);
    }

    @Override
    public String accessTokenFor(String oauthProviderMarker, String serverUrl) {
        String id = providerIdOf(oauthProviderMarker);
        Provider p = registry.find(id)
            .orElseThrow(() -> new McpOAuthException("Unknown OAuth provider '" + id + "'.", null, "unknown_provider"));
        if (!p.allowsMcpServerUrl(serverUrl)) {
            throw new McpOAuthException("The " + p.spec().name() + " token is only sent to " + p.spec().name() + "'s own MCP server host.", null, "host_not_allowed");
        }
        return currentAccessToken(id);
    }

    /** {@code "oauth:github"} for {@code "github"}. */
    public static String markerFor(String providerId) {
        return MARKER_PREFIX + providerId;
    }

    /** The provider id inside a marker, or the input itself when it has no prefix. */
    public static String providerIdOf(String marker) {
        return marker != null && marker.startsWith(MARKER_PREFIX) ? marker.substring(MARKER_PREFIX.length()) : marker;
    }

    // -------------------------------------------------------------- helpers

    private Provider requireConfigured(String providerId) {
        Provider p = registry.find(providerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown OAuth provider."));
        if (!p.configured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                p.spec().name() + " is not configured by your platform admin (" + p.notConfiguredReason() + ").");
        }
        return p;
    }

    private boolean usable(McpOAuthConnection c, Instant now) {
        if (c.encryptedRefreshToken() != null) {
            return true;
        }
        if (c.encryptedAccessToken() == null) {
            return false;
        }
        return c.accessTokenExpiresAt() == null || now.isBefore(c.accessTokenExpiresAt());
    }

    private CachedToken cached(TokenResult t) {
        long ttl = t.expiresInSeconds() != null ? t.expiresInSeconds()
            : (t.refreshToken() != null ? ASSUMED_REFRESHABLE_TTL_SECONDS : -1);
        if (ttl < 0) {
            return new CachedToken(t.accessToken(), null);
        }
        return new CachedToken(t.accessToken(), clock.instant().plusSeconds(Math.max(0, ttl - EXPIRY_SAFETY_MARGIN_SECONDS)));
    }

    private static String resourceKey(String providerId) {
        return RESOURCE_PREFIX + providerId;
    }

    private static String requireTenantId() {
        return TenantContext.current()
            .map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("OAuthProviderService called with no ambient TenantContext"));
    }
}
