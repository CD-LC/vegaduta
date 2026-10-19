package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.oauth.McpOAuthException;
import com.agenticai.core.mcp.oauth.McpOAuthService;
import com.agenticai.core.mcp.oauth.McpOAuthTokenSource;
import com.agenticai.core.mcp.oauth.OAuthProviderService;
import com.agenticai.core.security.TenantContext;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extract-only from DynamicMcpConnectionManager: builds the HTTP headers for a remote (SSE /
 * streamable HTTP) MCP registration. The manager keeps a thin {@code remoteHeaders} delegate
 * because tests reach it by name.
 *
 * <p>2026-09-28: also owns {@link PerRequestHeaders}, the per-request header customizer the live
 * transports use - see its javadoc for why the SDK's {@code customizeRequest} seam this used to
 * ride on was build-time only.
 */
final class McpRemoteHeaders {

    private static final Logger log = LoggerFactory.getLogger(McpRemoteHeaders.class);

    static final String OAUTH_ATLASSIAN = "ATLASSIAN";

    /**
     * RFC 7230 header-name token - anything else in env would make {@code
     * HttpRequest.Builder#header} throw and kill the connection.
     */
    private static final java.util.regex.Pattern HEADER_TOKEN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

    private McpRemoteHeaders() {}

    /**
     * Snapshot of every header right now (live bearer for OAuth registrations) - used by the
     * one-shot pre-probe.
     */
    static Map<String, String> build(
            McpDynamicConfig config, AtlassianOAuthService atlassianOAuthService) {
        return build(config, atlassianOAuthService, null);
    }

    /**
     * As above, with the provider-registry token source for {@code oauth:<id>} registrations (null
     * = Atlassian only).
     */
    static Map<String, String> build(
            McpDynamicConfig config,
            AtlassianOAuthService atlassianOAuthService,
            McpOAuthTokenSource providerTokens) {
        if (isOAuth(config)) {
            String accessToken = fetchBearer(config, atlassianOAuthService, providerTokens);
            return Map.of("Authorization", "Bearer " + accessToken);
        }
        return staticHeaders(config);
    }

    /** True for the Atlassian marker and for any provider-registry marker ({@code oauth:<id>}). */
    static boolean isOAuth(McpDynamicConfig config) {
        return OAUTH_ATLASSIAN.equals(config.oauthProvider()) || isProviderOAuth(config);
    }

    static boolean isProviderOAuth(McpDynamicConfig config) {
        String marker = config.oauthProvider();
        // oauth:<id> = provider registry; oauth-remote = the generic spec-discovered flow
        // (McpOAuthService).
        return marker != null
                && (marker.startsWith(OAuthProviderService.MARKER_PREFIX)
                        || McpOAuthService.MARKER.equals(marker));
    }

    /**
     * The one place a bearer is minted for an OAuth-backed registration: Atlassian's hard-wired
     * service or - for a provider-registry marker - the generic token source, which also checks
     * that the registration URL is on the provider's own MCP host before it releases a token.
     */
    static String fetchBearer(
            McpDynamicConfig config,
            AtlassianOAuthService atlassianOAuthService,
            McpOAuthTokenSource providerTokens) {
        if (OAUTH_ATLASSIAN.equals(config.oauthProvider())) {
            return atlassianOAuthService.currentAccessToken();
        }
        if (providerTokens != null && providerTokens.handles(config.oauthProvider())) {
            return providerTokens.accessTokenFor(config.oauthProvider(), config.url());
        }
        throw new IllegalStateException(
                "No OAuth token source is available for provider " + config.oauthProvider());
    }

    /**
     * The env-derived headers - constant for a connection's lifetime, safe to compute once. Empty
     * for OAuth registrations.
     */
    static Map<String, String> staticHeaders(McpDynamicConfig config) {
        if (isOAuth(config)) {
            return Map.of();
        }
        Map<String, String> env = config.env() == null ? Map.of() : config.env();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        env.forEach(
                (k, v) -> {
                    if (k != null && v != null && HEADER_TOKEN.matcher(k).matches()) {
                        headers.put(k, v);
                    } else {
                        log.warn(
                                "Skipping remote MCP header with non-token name for registration {}",
                                config.registrationId());
                    }
                });
        return headers;
    }

    /**
     * Per-request header customizer for a live remote MCP transport.
     *
     * <p><b>Why (2026-09-28):</b> the transports were configured with {@code
     * customizeRequest(Consumer<HttpRequest.Builder>)}, which in MCP SDK 0.18.2 runs the consumer
     * exactly ONCE, at builder time, against the template request builder (verified with {@code
     * javap -c}: {@code Builder.customizeRequest} just calls {@code
     * consumer.accept(this.requestBuilder)}). So an OAuth bearer and the "per request" KYA
     * attestation JWT were both frozen at connect time: the bearer expired under a long-lived
     * connection (every call 401s until a reconnect) and the attestation outlived its minutes-long
     * {@code exp}. {@code httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)} is the seam
     * the transport invokes for every outbound request, on a fresh {@code copy()} of the template -
     * this class is that customizer.
     *
     * <p><b>Threading:</b> the SDK calls {@link #customize} on a Reactor/HTTP thread with NO {@link
     * TenantContext}. Both per-request reads here are tenant-scoped ({@code
     * AtlassianOAuthService#currentAccessToken} resolves the tenant from TenantContext; the
     * attestation asserts a tenant), so the tenant/subject are CAPTURED when the transport is built
     * (connect/test run on a thread with the registration's TenantContext) and re-applied around
     * the token fetch. The token fetch is served from AtlassianOAuthService's in-memory cache until
     * near expiry; only a real refresh touches the DB/network.
     *
     * <p><b>Failure posture:</b> a failed per-request token refresh never fails the request by
     * itself - it WARNs and sends the last good bearer (the server's 401, if any, then surfaces
     * through the normal health-check/diagnosis path). The initial fetch in the constructor DOES
     * propagate, preserving connect()'s existing "not connected -> persisted diagnosis" behavior.
     * No resolvable tenant means NO attestation header (WARN once) rather than a token minted with
     * an empty tenant/subject.
     */
    static final class PerRequestHeaders implements McpSyncHttpClientRequestCustomizer {

        private final McpDynamicConfig config;
        private final Map<String, String> staticHeaders;
        private final AtlassianOAuthService atlassianOAuthService;
        private final Supplier<AgentAttestationService> attestation;
        private final TenantContext capturedTenant;
        private final AtomicReference<String> lastBearer = new AtomicReference<>();
        private final AtomicBoolean warnedNoTenant = new AtomicBoolean();

        private final McpOAuthTokenSource providerTokens;

        PerRequestHeaders(
                McpDynamicConfig config,
                AtlassianOAuthService atlassianOAuthService,
                Supplier<AgentAttestationService> attestation,
                TenantContext capturedTenant) {
            this(config, atlassianOAuthService, attestation, capturedTenant, null);
        }

        PerRequestHeaders(
                McpDynamicConfig config,
                AtlassianOAuthService atlassianOAuthService,
                Supplier<AgentAttestationService> attestation,
                TenantContext capturedTenant,
                McpOAuthTokenSource providerTokens) {
            this.config = config;
            this.staticHeaders = staticHeaders(config);
            this.atlassianOAuthService = atlassianOAuthService;
            this.attestation = attestation;
            this.capturedTenant = capturedTenant;
            this.providerTokens = providerTokens;
            if (isOAuth(config)) {
                // Runs on the connect thread (TenantContext present) - failure propagates on
                // purpose.
                lastBearer.set(fetchBearer(config, atlassianOAuthService, providerTokens));
            }
        }

        @Override
        public void customize(
                HttpRequest.Builder builder,
                String method,
                URI endpoint,
                String body,
                McpTransportContext context) {
            apply(builder);
        }

        /** The actual per-request work, independent of the SDK callback's extra arguments. */
        void apply(HttpRequest.Builder builder) {
            staticHeaders.forEach(builder::setHeader);
            if (isOAuth(config)) {
                String bearer = refreshBearer();
                if (bearer != null) {
                    builder.setHeader("Authorization", "Bearer " + bearer);
                }
            }
            applyAttestation(builder);
        }

        private String refreshBearer() {
            try {
                String fresh =
                        withCapturedTenant(
                                () -> fetchBearer(config, atlassianOAuthService, providerTokens));
                if (fresh != null && !fresh.isBlank()) {
                    lastBearer.set(fresh);
                    return fresh;
                }
            } catch (RuntimeException ex) {
                if (ex instanceof McpOAuthException oe
                        && "reauth_required".equals(oe.getOauthErrorCode())) {
                    // Generic MCP OAuth: the authorization server permanently rejected the
                    // connection (it is now
                    // marked needs-reauth and the console says so). The stale bearer is useless -
                    // send none, so the
                    // server's own 401 + challenge surfaces instead of a confusing "invalid token".
                    log.warn(
                            "Remote MCP registration {} needs OAuth re-authorization",
                            config.registrationId());
                    lastBearer.set(null);
                    return null;
                }
                log.warn(
                        "Could not refresh OAuth access token for remote MCP registration {} ({}) - sending the last known token: {}",
                        config.registrationId(),
                        config.oauthProvider(),
                        ex.toString());
            }
            return lastBearer.get();
        }

        private void applyAttestation(HttpRequest.Builder builder) {
            // Never overwrites a caller-set header (a registration's own env may carry one).
            for (String key : staticHeaders.keySet()) {
                if (AgentAttestationService.HEADER_NAME.equalsIgnoreCase(key)) {
                    return;
                }
            }
            AgentAttestationService service = attestation == null ? null : attestation.get();
            if (service == null) {
                return;
            }
            TenantContext tenant =
                    capturedTenant != null ? capturedTenant : TenantContext.current().orElse(null);
            if (tenant == null || tenant.getTenantId() == null || tenant.getTenantId().isBlank()) {
                if (warnedNoTenant.compareAndSet(false, true)) {
                    log.warn(
                            "No tenant resolvable for remote MCP registration {} - sending WITHOUT an {} header rather than "
                                    + "minting one with an empty tenant/subject",
                            config.registrationId(),
                            AgentAttestationService.HEADER_NAME);
                }
                return;
            }
            service.mint(tenant.getTenantId(), tenant.getSubject(), null)
                    .ifPresent(jwt -> builder.setHeader(AgentAttestationService.HEADER_NAME, jwt));
        }

        /**
         * Runs {@code work} with the captured TenantContext when the current thread has none (or
         * another tenant's); always restores.
         */
        private <T> T withCapturedTenant(Supplier<T> work) {
            if (capturedTenant == null) {
                return work.get();
            }
            TenantContext previous = TenantContext.current().orElse(null);
            boolean swap =
                    previous == null
                            || !java.util.Objects.equals(
                                    previous.getTenantId(), capturedTenant.getTenantId());
            if (!swap) {
                return work.get();
            }
            TenantContext.set(capturedTenant);
            try {
                return work.get();
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        }
    }
}
