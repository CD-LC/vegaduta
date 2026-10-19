package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OAuth Client ID Metadata Document (CIMD) support: the platform's own client metadata, published at a stable
 * public https URL that doubles as the {@code client_id} when an authorization server advertises
 * {@code client_id_metadata_document_supported} - no per-tenant dynamic registration, nothing to clean up, and the
 * AS shows the user a verifiable client identity (the document's host).
 *
 * <p>OFF by default ({@code app.mcp.oauth.cimd.enabled=false}). When on, the configuration is validated
 * strictly and the feature simply stays inactive (with a logged reason) if anything is off, so a misconfigured
 * deployment falls back to dynamic registration instead of sending an authorization server a {@code client_id}
 * it cannot fetch:
 * <ul>
 *   <li>{@code client-id-url} is https, has a path, no fragment/credentials/dot-segments, and its host is a public
 *       name (never loopback, private, link-local or a platform-internal hostname) - it is browser- and
 *       AS-facing;</li>
 *   <li>its path is the configured {@code path}, which must live under {@value #PATH_PREFIX} (the public route
 *       prefix of this feature), so the document is actually served where the {@code client_id} points;</li>
 *   <li>the {@code redirect-uri} the document lists is https and public, and is the ONLY redirect URI published.</li>
 * </ul>
 */
@Component
public class McpOAuthClientMetadata {

    public static final String PATH_PREFIX = "/oauth/mcp/remote/";
    public static final String DEFAULT_PATH = "/oauth/mcp/remote/client-metadata.json";

    private static final Logger log = LoggerFactory.getLogger(McpOAuthClientMetadata.class);

    private final boolean requested;
    private final String clientIdUrl;
    private final String path;
    private final String clientName;
    private final String clientUri;
    private final String redirectUri;
    private final McpRemoteUrlGuard guard;
    private final String problem;

    @Autowired
    public McpOAuthClientMetadata(
        McpRemoteUrlGuard guard,
        @Value("${app.mcp.oauth.cimd.enabled:false}") boolean enabled,
        @Value("${app.mcp.oauth.cimd.client-id-url:}") String clientIdUrl,
        @Value("${app.mcp.oauth.cimd.path:" + DEFAULT_PATH + "}") String path,
        @Value("${app.mcp.oauth.cimd.client-name:VegaDuta}") String clientName,
        @Value("${app.mcp.oauth.cimd.client-uri:}") String clientUri,
        @Value("${app.mcp.oauth.redirect-uri:}") String redirectUri
    ) {
        this.guard = guard;
        this.requested = enabled;
        this.clientIdUrl = clientIdUrl == null ? "" : clientIdUrl.trim();
        this.path = path == null || path.isBlank() ? DEFAULT_PATH : path.trim();
        this.clientName = clientName == null || clientName.isBlank() ? "VegaDuta" : clientName.trim();
        this.clientUri = clientUri == null ? "" : clientUri.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.problem = enabled ? validate() : "disabled";
        if (enabled && problem != null) {
            log.warn("MCP OAuth client metadata document (CIMD) is enabled but unusable - falling back to dynamic client registration: {}", problem);
        }
    }

    /** True when the document is served and used as the {@code client_id}. */
    public boolean active() {
        return requested && problem == null;
    }

    /** The configured reason CIMD is not active, or empty when it is. Never contains a secret. */
    public Optional<String> inactiveReason() {
        return problem == null ? Optional.empty() : Optional.of(problem);
    }

    /** The {@code client_id}: the URL this document is served at. Only meaningful when {@link #active()}. */
    public String clientIdUrl() {
        return clientIdUrl;
    }

    /** The route the controller serves the document on (always under {@value #PATH_PREFIX} when active). */
    public String path() {
        return path;
    }

    /** The document. Only meaningful when {@link #active()}. */
    public Map<String, Object> document() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("client_id", clientIdUrl);
        doc.put("client_name", clientName);
        if (!clientUri.isEmpty() && isPublicHttps(clientUri)) {
            doc.put("client_uri", clientUri);
        }
        doc.put("redirect_uris", List.of(redirectUri));
        doc.put("grant_types", List.of("authorization_code", "refresh_token"));
        doc.put("response_types", List.of("code"));
        doc.put("token_endpoint_auth_method", "none");
        return doc;
    }

    /** Strict redirect-URI rule shared with the flow: https, public host, no fragment, no credentials, no wildcard. */
    public static boolean isStrictRedirectUri(String uri, McpRemoteUrlGuard guard) {
        if (uri == null || uri.isBlank() || uri.contains("*")) {
            return false;
        }
        URI parsed;
        try {
            parsed = new URI(uri.trim());
        } catch (Exception e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
            || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null) {
            return false;
        }
        return guard.check(uri.trim()).isEmpty();
    }

    private String validate() {
        if (clientIdUrl.isEmpty()) {
            return "app.mcp.oauth.cimd.client-id-url is not set";
        }
        URI id;
        try {
            id = new URI(clientIdUrl);
        } catch (Exception e) {
            return "client-id-url is not a valid URL";
        }
        if (!"https".equalsIgnoreCase(id.getScheme()) || id.getHost() == null) {
            return "client-id-url must be an https URL";
        }
        if (id.getRawUserInfo() != null || id.getRawFragment() != null || id.getRawQuery() != null) {
            return "client-id-url must not contain credentials, a query or a fragment";
        }
        String idPath = id.getRawPath();
        if (idPath == null || idPath.length() < 2 || idPath.contains("/./") || idPath.contains("/../")
            || idPath.endsWith("/.") || idPath.endsWith("/..")) {
            return "client-id-url needs a plain path component";
        }
        if (guard.check(clientIdUrl).isPresent()) {
            return "client-id-url must be a public host (not loopback, private or platform-internal)";
        }
        if (!path.startsWith(PATH_PREFIX) || path.contains("..") || path.contains("//") || path.endsWith("/")) {
            return "app.mcp.oauth.cimd.path must be a file path under " + PATH_PREFIX;
        }
        if (!idPath.equals(path)) {
            return "client-id-url's path (" + idPath + ") must equal app.mcp.oauth.cimd.path (" + path
                + ") - the document would not be served where the client_id points";
        }
        if (!isStrictRedirectUri(redirectUri, guard)) {
            return "app.mcp.oauth.redirect-uri must be a public https URL";
        }
        return null;
    }

    private boolean isPublicHttps(String url) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                && guard.check(url).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
