package com.agenticai.core.mcp.atlassian;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.oauth.OAuthStateBindingException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization Code + PKCE activation for a tenant's own Atlassian
 * (Confluence/Jira) connection, reusing the ONE platform-wide OAuth client
 * {@link AtlassianPlatformClientService} registers via Dynamic Client
 * Registration - see that class's javadoc and {@code AtlassianCredential}'s
 * for why this is a simpler one-step flow than {@code XOAuthService}'s
 * two-step BYOK-then-OAuth (no per-tenant app registration step here at
 * all).
 *
 * <p><b>How {@code state} carries tenant identity AND the PKCE verifier
 * across the redirect:</b> same encrypted-state technique as {@code
 * XOAuthService}/{@code GoogleOAuthService} - {@code
 * tenantId|codeVerifier|issuedAtEpochSeconds}, AES-256-GCM encrypted so a
 * tampered value fails to decrypt at all and the embedded timestamp caps
 * the replay window. Uses {@link TenantContext#getTenantId()} rather than
 * {@code TenantSchemaResolver.currentSchemaName()} (what {@code
 * core/omnichannel}'s OAuth services use) because {@code core/mcp}
 * deliberately carries no dependency on {@code core/tenancy} - see {@code
 * McpRegistryService}'s own class-level comment on the review-queue methods
 * for the same boundary. Every other tenant-scoped read in this module
 * already resolves the ambient tenant the identical way (see {@code
 * McpRegistryService#enforcePlanLimitsForNewConnector}).
 *
 * <p><b>Live connection headers, not a stored token.</b> Unlike a normal
 * dynamic MCP registration's static {@code encryptedEnv}, this connector's
 * {@code Authorization} header is minted fresh on every real connect
 * attempt by {@link #currentAccessToken()} - see {@code
 * DynamicMcpConnectionManager#remoteHeaders}'s {@code oauthProvider}
 * branch. {@link #persistConnection} therefore registers the MCP row with
 * an EMPTY {@code env} and stamps {@link McpRegistryService#setOAuthProvider}
 * so that branch fires.
 */
@Service
public class AtlassianOAuthService {

    private static final long STATE_MAX_AGE_SECONDS = 600; // 10 minutes - ample for a user to complete Atlassian's consent screen.
    // 2026-08-15: mcp.atlassian.com/v1/sse stopped being supported after
    // 2026-06-30 (Atlassian Rovo MCP server docs) - every live connection
    // built from this URL now fails. The modern endpoint speaks
    // streamable-HTTP, not SSE - see the matching McpServerType.STREAMABLE_HTTP
    // change in persistConnection below; changing this URL without also
    // changing that type would still be broken (DynamicMcpConnectionManager#
    // buildRemoteTransport picks its wire protocol off McpServerType, not off
    // the URL string).
    //
    // 2026-08-15 correction, same day: the URL above was also missing the
    // /authv2 path segment - confirmed live (a real post-OAuth-consent
    // connection attempt 404'd: "McpTransportException: Server Not Found.
    // Status code:404") and against Atlassian's own current docs
    // (support.atlassian.com/atlassian-rovo-mcp-server/docs/
    // getting-started-with-the-atlassian-remote-mcp-server/), which give
    // https://mcp.atlassian.com/v1/mcp/authv2 as the OAuth 2.1 endpoint,
    // distinct from the bare /v1/mcp path. The connection manager's own
    // diagnostic heuristic misattributed the resulting failure to an
    // SSE-vs-streamable-HTTP type mismatch (McpConnectionDiagnoser#
    // diagnoseProbe's "looks like an SSE server" branch) - a plausible-looking
    // but wrong diagnosis for this particular 404, worth remembering next
    // time that message shows up for an otherwise-correctly-typed server.
    private static final String ATLASSIAN_MCP_URL = "https://mcp.atlassian.com/v1/mcp/authv2";
    private static final String OAUTH_PROVIDER = "ATLASSIAN";
    /** Provider key for {@link OAuthStateBinding}; also the callback path segment ({@code /oauth/atlassian/callback}). */
    public static final String BINDING_PROVIDER = "atlassian";
    // How much earlier than a token's real expiry to treat the cache entry as
    // stale - a real connect attempt must never hand the SDK a token that
    // expires mid-handshake.
    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    private final AtlassianCredentialRepository repository;
    private final AtlassianOAuthClient client;
    private final AtlassianPlatformClientService platformClientService;
    private final McpRegistryService mcpRegistryService;
    private final EncryptionService encryptionService;
    private final String redirectUri;
    // SEC-018: single-use, TTL-bound, server-side binding of every state this
    // service mints (plus the browser cookie when the web app sends it).
    private final OAuthStateBinding stateBinding;

    // In-process only, per JVM instance - deliberately NOT distributed
    // (Redis, etc.). remoteHeaders() (core/agents) calls currentAccessToken()
    // on every connect attempt AND every 5-minute health-check/retry cycle
    // for a healthy connection (see DynamicMcpConnectionManager's scheduled
    // jobs) - without this cache, a healthy tenant's connection would hit
    // Atlassian's token endpoint far more often than necessary and risk
    // racing a refresh-token rotation against itself. Worst case on a
    // multi-replica deployment is each replica refreshing independently
    // (more calls, never incorrect) - acceptable for the same reason
    // liveClients/liveToolCallbacks in DynamicMcpConnectionManager are also
    // plain in-process maps, not shared state.
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public AtlassianOAuthService(
        AtlassianCredentialRepository repository,
        AtlassianOAuthClient client,
        AtlassianPlatformClientService platformClientService,
        McpRegistryService mcpRegistryService,
        EncryptionService encryptionService,
        @Value("${app.atlassian.oauth.redirect-uri:}") String redirectUri,
        OAuthStateBinding stateBinding
    ) {
        this.stateBinding = stateBinding;
        this.repository = repository;
        this.client = client;
        this.platformClientService = platformClientService;
        this.mcpRegistryService = mcpRegistryService;
        this.encryptionService = encryptionService;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return !redirectUri.isBlank();
    }

    /**
     * Called from an authenticated console request - the current tenant + a
     * fresh PKCE verifier both get encoded into {@code state}. Not itself
     * {@code @Transactional}: {@link AtlassianPlatformClientService#ensureRegistered}
     * carries its own transaction boundary, and wrapping this method too
     * would risk joining that write into an outer read-only transaction.
     */
    public String authorizeUrl() {
        return authorizeRedirect().url();
    }

    /** {@link #authorizeUrl} plus the SEC-018 browser-binding cookie the controller sets on its response. */
    public AuthorizeRedirect authorizeRedirect() {
        if (!isConfigured()) {
            throw new AtlassianApiException("Atlassian OAuth is not configured on this deployment (app.atlassian.oauth.redirect-uri is unset).");
        }
        AtlassianPlatformClientService.ResolvedClient platformClient = platformClientService.ensureRegistered(redirectUri);
        AtlassianOAuthClient.PkcePair pkce = client.newPkcePair();
        String tenantId = requireCurrentTenantId();
        String initiator = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        OAuthStateBinding.Issued issued = stateBinding.issue(BINDING_PROVIDER, tenantId, null, initiator);
        String state = encodeState(tenantId, pkce.verifier(), issued.nonce());
        return new AuthorizeRedirect(
            client.buildAuthorizationUrl(platformClient.clientId(), redirectUri, state, pkce.challenge()),
            issued.cookie());
    }

    /**
     * SEC-018: verify and CONSUME (single use) the server-side binding of a
     * decoded callback state. Must run before {@link #persistConnection}.
     *
     * @throws AtlassianOAuthStateException if the nonce is unknown, used,
     *     expired, bound to another tenant, or the binding cookie (when
     *     present, or when required by config) does not match
     */
    public void verifyBinding(DecodedState decoded, String bindingCookie) {
        try {
            stateBinding.consume(BINDING_PROVIDER, decoded.nonce(), decoded.tenantId(), null, bindingCookie);
        } catch (OAuthStateBindingException e) {
            throw new AtlassianOAuthStateException(
                "This connection attempt is no longer valid - please start connecting again from the console.");
        }
    }

    /** @return the tenant id and PKCE verifier this state was minted for. @throws AtlassianOAuthStateException if tampered, malformed, or expired. */
    public DecodedState decodeState(String state) {
        String decrypted;
        try {
            decrypted = encryptionService.decrypt(state);
        } catch (Exception e) {
            throw new AtlassianOAuthStateException("Could not verify the OAuth state - please try connecting again.");
        }
        // SEC-018: tenant|codeVerifier|bindingNonce|epoch. The pre-SEC-018
        // 3-field form carries no binding and is refused.
        String[] parts = decrypted.split("\\|");
        if (parts.length != 4) {
            throw new AtlassianOAuthStateException("Malformed OAuth state.");
        }
        long issuedAtEpochSeconds;
        try {
            issuedAtEpochSeconds = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new AtlassianOAuthStateException("Malformed OAuth state.");
        }
        if (Instant.now().getEpochSecond() - issuedAtEpochSeconds > STATE_MAX_AGE_SECONDS) {
            throw new AtlassianOAuthStateException("This connection attempt expired - please try connecting again.");
        }
        return new DecodedState(parts[0], parts[1], parts[2]);
    }

    private String encodeState(String tenantId, String codeVerifier, String nonce) {
        String payload = tenantId + "|" + codeVerifier + "|" + nonce + "|" + Instant.now().getEpochSecond();
        return encryptionService.encrypt(payload);
    }

    /**
     * Exchanges the code, persists the tenant's {@link AtlassianCredential},
     * and upserts/marks the tenant's {@code atlassian} MCP registration -
     * must be called with {@link TenantContext} already set to the tenant
     * {@link #decodeState} returned. Deliberately does NOT dispatch a real
     * MCP connect attempt itself - the caller ({@code AtlassianOAuthController})
     * does that AFTER this transaction returns (and therefore commits), via
     * {@code McpConnectDispatcher} - dispatching from inside this method
     * would race the dispatched worker thread against this transaction's
     * own commit, which could read the not-yet-committed registration row.
     *
     * @return the id of the tenant's {@code atlassian} MCP registration.
     */
    @Transactional
    public UUID persistConnection(String code, String codeVerifier) {
        AtlassianPlatformClientService.ResolvedClient platformClient = platformClientService.ensureRegistered(redirectUri);
        AtlassianOAuthClient.TokenResponse tokens = client.exchangeCode(
            platformClient.clientId(), platformClient.clientSecret(), platformClient.tokenEndpointAuthMethod(),
            redirectUri, code, codeVerifier);
        if (tokens.refreshToken() == null) {
            throw new AtlassianApiException("Atlassian did not return a refresh token - offline access may not have been granted.");
        }

        String encryptedRefreshToken = encryptionService.encrypt(tokens.refreshToken());
        AtlassianCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .map(existing -> {
                existing.reconnect(encryptedRefreshToken);
                return existing;
            })
            .orElseGet(() -> new AtlassianCredential(encryptedRefreshToken));
        repository.save(credential);
        // A fresh consent means a fresh token - drop any stale cached entry
        // for this tenant rather than waiting for it to expire naturally.
        tokenCache.remove(requireCurrentTenantId());

        // Registers/updates the tenant's `atlassian` MCP row with an EMPTY
        // env - this connector's live headers come from currentAccessToken()
        // below, never from a stored static value. See class javadoc.
        McpServerRegistrationResponse registration = mcpRegistryService.register(new McpServerRegistrationRequest(
            "atlassian",
            McpServerType.STREAMABLE_HTTP,
            "Confluence and Jira via Atlassian's OAuth-connected remote MCP server",
            true,
            null,
            null,
            Map.of(),
            ATLASSIAN_MCP_URL
        ));
        mcpRegistryService.setOAuthProvider(registration.id(), OAUTH_PROVIDER);
        return registration.id();
    }

    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.revoke();
            repository.save(credential);
        });
        tokenCache.remove(requireCurrentTenantId());
    }

    /**
     * Mints a live access token for the CURRENT tenant, refreshing (and, on
     * a rotated refresh token, re-persisting) only when the cached one is
     * absent or within {@value #EXPIRY_SAFETY_MARGIN_SECONDS}s of expiry -
     * see {@link #tokenCache}'s field javadoc for why a cache exists here
     * at all, unlike {@code XOAuthService#currentAccessToken}'s
     * refresh-on-every-call shape.
     *
     * <p>A token-endpoint {@code invalid_grant} response means Atlassian
     * has permanently rejected this tenant's refresh token (revoked
     * externally, or otherwise dead) - the credential is marked {@link
     * AtlassianCredentialStatus#REVOKED} so the tenant sees an actionable
     * "reconnect" message instead of the same opaque failure retrying every
     * 5 minutes forever. Any OTHER failure (network error, 5xx, an
     * unparseable error body - {@link AtlassianApiException#getOauthErrorCode()}
     * null or anything other than {@code "invalid_grant"}) is treated as
     * transient and simply propagated - {@code DynamicMcpConnectionManager}'s
     * existing bounded-backoff retry already handles that correctly; marking
     * revoked on every failure would force a real reconnect over a passing
     * network blip.
     *
     * @throws AtlassianNotConnectedException if no tenant has completed
     *     consent yet, the connection was revoked, or the refresh token was
     *     just found to be permanently rejected.
     */
    @Transactional
    public String currentAccessToken() {
        String tenantId = requireCurrentTenantId();
        CachedToken cached = tokenCache.get(tenantId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }

        AtlassianCredential credential = repository.findFirstByOrderByCreatedAtAsc().orElseThrow(AtlassianNotConnectedException::new);
        if (credential.getStatus() != AtlassianCredentialStatus.ACTIVE || credential.getEncryptedRefreshToken() == null) {
            throw new AtlassianNotConnectedException();
        }
        AtlassianPlatformClientService.ResolvedClient platformClient = platformClientService.ensureRegistered(redirectUri);
        String refreshToken = encryptionService.decrypt(credential.getEncryptedRefreshToken());

        AtlassianOAuthClient.TokenResponse refreshed;
        try {
            refreshed = client.refreshAccessToken(
                platformClient.clientId(), platformClient.clientSecret(), platformClient.tokenEndpointAuthMethod(), refreshToken);
        } catch (AtlassianApiException e) {
            if ("invalid_grant".equals(e.getOauthErrorCode())) {
                credential.revoke();
                repository.save(credential);
                tokenCache.remove(tenantId);
                throw new AtlassianNotConnectedException(
                    "Atlassian connection was revoked - reconnect via the \"Connect via Atlassian\" button.");
            }
            throw e;
        }
        if (refreshed.refreshToken() != null) {
            // Persist defensively whenever a new one comes back - correct
            // whether or not Atlassian actually rotates on every use (see
            // AtlassianOAuthClient#refreshAccessToken's javadoc).
            credential.rotateRefreshToken(encryptionService.encrypt(refreshed.refreshToken()));
            repository.save(credential);
        }
        tokenCache.put(tenantId, new CachedToken(
            refreshed.accessToken(),
            Instant.now().plusSeconds(Math.max(0, refreshed.expiresInSeconds() - EXPIRY_SAFETY_MARGIN_SECONDS))));
        return refreshed.accessToken();
    }

    private static String requireCurrentTenantId() {
        return TenantContext.current()
            .map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("AtlassianOAuthService called with no ambient TenantContext"));
    }

    public record DecodedState(String tenantId, String codeVerifier, String nonce) {
    }

    /** Authorize URL plus the binding cookie to set on the console response. */
    public record AuthorizeRedirect(String url, ResponseCookie bindingCookie) {
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
