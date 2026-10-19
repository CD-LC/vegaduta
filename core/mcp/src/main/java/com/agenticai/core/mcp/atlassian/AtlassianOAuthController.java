package com.agenticai.core.mcp.atlassian;

import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.publicurl.PublicWebUrl;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Three surfaces, same split-by-path-prefix reasoning as {@code XOAuthController}/{@code
 * GoogleOAuthController}:
 *
 * <ul>
 *   <li>{@code GET /api/mcp/registry/atlassian/authorize-url}, {@code DELETE
 *       /api/mcp/registry/atlassian/connection} - normal JWT-authenticated console calls.
 *   <li>{@code GET /oauth/atlassian/callback} - Atlassian's own redirect back to this platform
 *       after consent, carrying no JWT at all - tenant identity (and the PKCE verifier) travel
 *       through the encrypted {@code state} value, exactly like {@code XOAuthController#callback}.
 * </ul>
 *
 * <p>The callback dispatches a real MCP connect attempt via {@link McpConnectDispatcher} AFTER
 * {@link AtlassianOAuthService#persistConnection} returns (and therefore commits) - see that
 * method's javadoc for why dispatching from inside it would be a real transaction-commit race, not
 * just a style preference.
 */
@RestController
public class AtlassianOAuthController {

    private static final Logger log = LoggerFactory.getLogger(AtlassianOAuthController.class);
    private static final String REQUESTED_BY = "system:atlassian-oauth-callback";

    private final AtlassianOAuthService oauthService;
    // Optional - same "keep core/mcp free of a hard core/agents dependency"
    // reasoning as McpRegistryController's identical field.
    private final ObjectProvider<McpDynamicConnector> connector;
    private final McpConnectDispatcher connectDispatcher;
    private final String consoleRedirectUri;

    public AtlassianOAuthController(
            AtlassianOAuthService oauthService,
            ObjectProvider<McpDynamicConnector> connector,
            McpConnectDispatcher connectDispatcher,
            @Value("${app.atlassian.oauth.console-redirect-uri:}") String consoleRedirectUri,
            @Value("${app.web.public-url:}") String appPublicUrl) {
        this.oauthService = oauthService;
        this.connector = connector;
        this.connectDispatcher = connectDispatcher;
        // Dedicated property when set, else <app.web.public-url>/tools; blank when neither is
        // configured (no localhost default, AGENTS.md rule 4) - redirectTo then refuses.
        this.consoleRedirectUri =
                PublicWebUrl.consoleRedirect(consoleRedirectUri, appPublicUrl, "/tools");
    }

    @GetMapping("/api/mcp/registry/atlassian/authorize-url")
    public AuthorizeUrlResponse authorizeUrl(HttpServletResponse response) {
        AtlassianOAuthService.AuthorizeRedirect redirect = oauthService.authorizeRedirect();
        // SEC-018 browser binding (HttpOnly, Secure, SameSite=Lax, scoped to
        // /oauth/atlassian/callback).
        if (redirect.bindingCookie() != null && response != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, redirect.bindingCookie().toString());
        }
        return new AuthorizeUrlResponse(redirect.url());
    }

    @DeleteMapping("/api/mcp/registry/atlassian/connection")
    public ResponseEntity<Void> disconnect() {
        oauthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/oauth/atlassian/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = "vd_oauth_atlassian", required = false) String bindingCookie) {
        if (error != null) {
            log.info("Atlassian OAuth consent was not completed: {}", error);
            return redirectTo("error", "consent_declined");
        }
        if (code == null || state == null) {
            return redirectTo("error", "missing_code_or_state");
        }

        AtlassianOAuthService.DecodedState decoded;
        try {
            decoded = oauthService.decodeState(state);
        } catch (AtlassianOAuthStateException e) {
            log.warn(
                    "Rejected Atlassian OAuth callback with an invalid/expired state: {}",
                    e.getMessage());
            return redirectTo("error", "invalid_state");
        }
        try {
            oauthService.verifyBinding(decoded, bindingCookie);
        } catch (AtlassianOAuthStateException e) {
            log.warn(
                    "Rejected Atlassian OAuth callback whose state is not bound to its connect"
                            + " session: {}",
                    e.getMessage());
            return redirectTo("error", "invalid_state");
        }

        TenantContext.set(TenantContext.of(decoded.tenantId(), REQUESTED_BY, Set.of()));
        UUID registrationId;
        try {
            registrationId = oauthService.persistConnection(code, decoded.codeVerifier());
        } catch (RuntimeException e) {
            log.warn("Failed to complete Atlassian OAuth connection: {}", e.getMessage());
            return redirectTo("error", "exchange_failed");
        } finally {
            TenantContext.clear();
        }

        // Dispatched AFTER persistConnection's transaction has returned
        // (and therefore committed) - see class javadoc. Runs with the same
        // tenant re-applied on its own worker thread by McpConnectDispatcher
        // itself, so TenantContext does not need to still be set here.
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            TenantContext.set(TenantContext.of(decoded.tenantId(), REQUESTED_BY, Set.of()));
            try {
                connectDispatcher.dispatch(c, registrationId);
            } finally {
                TenantContext.clear();
            }
        }
        return redirectTo("connected", null);
    }

    private ResponseEntity<Void> redirectTo(String atlassianParam, String reasonParam) {
        String location =
                PublicWebUrl.require(consoleRedirectUri, "app.atlassian.oauth.console-redirect-uri")
                        + "?atlassian="
                        + atlassianParam
                        + (reasonParam != null ? "&reason=" + reasonParam : "");
        return ResponseEntity.status(302)
                .location(URI.create(location))
                .header(
                        HttpHeaders.SET_COOKIE,
                        OAuthStateBinding.clearCookie(AtlassianOAuthService.BINDING_PROVIDER)
                                .toString())
                .build();
    }

    public record AuthorizeUrlResponse(String url) {}
}
