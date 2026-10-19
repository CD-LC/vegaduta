package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.oauth.OAuthProviderClient.RevokeOutcome;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One-click provider OAuth, same split as {@code AtlassianOAuthController}
 * (no class-level mapping; full paths per method):
 *
 * <ul>
 *   <li>{@code GET /api/mcp/oauth/providers} - what the console grid renders (honest
 *       {@code configured}/{@code connected} per provider).</li>
 *   <li>{@code POST /api/mcp/oauth/{provider}/authorize} - returns the consent URL.</li>
 *   <li>{@code DELETE /api/mcp/oauth/{provider}} - revoke (best effort) and forget.</li>
 *   <li>{@code POST /api/mcp/oauth/{provider}/attach/{registrationId}} - let an existing
 *       remote MCP registration use the connection as its bearer.</li>
 *   <li>{@code GET /oauth/mcp/{provider}/callback} - the provider's redirect; public, no
 *       JWT (SecurityConfig permitAll, like the Atlassian callback). Tenant and user come
 *       from the verified encrypted state. Always answers with a 302 back to the console;
 *       the reason for a failure is a fixed code, never provider text.</li>
 * </ul>
 *
 * Management routes are gated by {@code PERM_MANAGE_INTEGRATIONS} in SecurityConfig.
 */
@RestController
public class OAuthProviderController {

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderController.class);
    private static final String REQUESTED_BY_FALLBACK = "system:mcp-oauth-callback";

    private final OAuthProviderService service;
    private final OAuthProviderMcpLinker linker;

    public OAuthProviderController(OAuthProviderService service, OAuthProviderMcpLinker linker) {
        this.service = service;
        this.linker = linker;
    }

    public record ProvidersResponse(boolean enabled, List<OAuthProviderService.ProviderView> providers) {
    }

    public record AuthorizeRequest(String returnTo) {
    }

    public record AuthorizeResponse(String url, String provider) {
    }

    public record DisconnectResponse(boolean disconnected, String providerRevocation) {
    }

    @GetMapping("/api/mcp/oauth/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(service.featureEnabled(), service.listForCurrentTenant());
    }

    @PostMapping("/api/mcp/oauth/{provider}/authorize")
    public AuthorizeResponse authorize(
        @PathVariable("provider") String provider,
        @RequestBody(required = false) AuthorizeRequest request
    ) {
        OAuthProviderService.AuthorizationStart start =
            service.beginAuthorization(provider, request == null ? null : request.returnTo());
        return new AuthorizeResponse(start.authorizationUrl(), start.providerId());
    }

    @DeleteMapping("/api/mcp/oauth/{provider}")
    public DisconnectResponse disconnect(@PathVariable("provider") String provider) {
        Provider p = service.provider(provider)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown OAuth provider."));
        OAuthProviderService.DisconnectResult result = service.disconnect(provider);
        if (result.wasConnected()) {
            linker.dropLiveConnections(p);
        }
        RevokeOutcome outcome = result.providerRevocation();
        return new DisconnectResponse(result.wasConnected(), outcome.name());
    }

    @PostMapping("/api/mcp/oauth/{provider}/attach/{registrationId}")
    public ResponseEntity<Void> attach(
        @PathVariable("provider") String provider,
        @PathVariable("registrationId") UUID registrationId
    ) {
        Provider p = service.provider(provider)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown OAuth provider."));
        linker.attach(p, registrationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/oauth/mcp/{provider}/callback")
    public ResponseEntity<Void> callback(
        @PathVariable("provider") String rawProvider,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error
    ) {
        // The path variable is attacker-controlled: reduce it to the id alphabet before it is logged or echoed.
        String provider = safeId(rawProvider);
        String returnUrl = service.returnUrlFor(provider, state);
        if (error != null) {
            log.info("OAuth consent for provider {} was not completed", provider);
            return redirect(returnUrl, provider, "error", "consent_declined", null);
        }
        if (code == null || state == null) {
            return redirect(returnUrl, provider, "error", "missing_code_or_state", null);
        }

        OAuthProviderService.CompletedConnection completed;
        try {
            completed = service.completeAuthorization(provider, code, state);
        } catch (OAuthStateException e) {
            log.warn("Rejected OAuth callback for provider {}: {}", provider, e.reason());
            return redirect(returnUrl, provider, "error", e.reason(), null);
        } catch (ResponseStatusException e) {
            return redirect(returnUrl, provider, "error", "not_configured", null);
        } catch (RuntimeException e) {
            // McpOAuthException messages never carry a token or a response body (see OAuthProviderClient).
            log.warn("Failed to complete OAuth connection for provider {}: {}", provider, e.getMessage());
            return redirect(returnUrl, provider, "error", "exchange_failed", null);
        }

        String mcpResult = null;
        Optional<Provider> p = service.provider(provider);
        if (p.isPresent() && p.get().hasMcpServer()) {
            String user = completed.userId() == null || completed.userId().isBlank() ? REQUESTED_BY_FALLBACK : completed.userId();
            TenantContext.set(TenantContext.of(completed.tenantId(), user, Set.of()));
            try {
                linker.registerServerFor(p.get());
                mcpResult = "registered";
            } catch (RuntimeException e) {
                log.warn("OAuth provider {} connected but its MCP server could not be registered: {}", provider, e.toString());
                mcpResult = "failed";
            } finally {
                TenantContext.clear();
            }
        }
        return redirect(completed.returnUrl(), provider, "connected", null, mcpResult);
    }

    private static String safeId(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String cleaned = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "_");
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private static ResponseEntity<Void> redirect(String base, String provider, String result, String reason, String mcp) {
        StringBuilder location = new StringBuilder(base == null ? "" : base);
        location.append(location.indexOf("?") >= 0 ? '&' : '?');
        location.append("mcp_oauth=").append(result);
        location.append("&provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
        if (reason != null) {
            location.append("&reason=").append(URLEncoder.encode(reason, StandardCharsets.UTF_8));
        }
        if (mcp != null) {
            location.append("&mcp=").append(mcp);
        }
        return ResponseEntity.status(302).location(URI.create(location.toString())).build();
    }
}
