package com.agenticai.core.mcp.oauth;

import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Connect with OAuth" for a remote MCP server URL the tenant types - the REST face of
 * {@link McpOAuthService}. Same shape as {@link OAuthProviderController} (no class-level mapping; full paths):
 *
 * <ul>
 *   <li>{@code GET /api/mcp/oauth/remote} - capability probe for the console ({@code enabled}); the one route
 *       that answers when the feature is off, so the console can hide the button without a 404 in its log.</li>
 *   <li>{@code POST /api/mcp/oauth/remote/status} {@code {url}} - not-required / needs-signin / connected /
 *       needs-reauth. A POST because it can make one outbound probe: it is admin-gated like starting a connection.</li>
 *   <li>{@code POST /api/mcp/oauth/remote/authorize} {@code {url, scope?, returnTo?}} - the consent URL.</li>
 *   <li>{@code DELETE /api/mcp/oauth/remote?url=} - forget the connection and drop live MCP connections.</li>
 *   <li>{@code GET /oauth/mcp/remote/callback} - the authorization server's redirect; public, no JWT
 *       (SecurityConfig permitAll {@code /oauth/mcp/*}{@code /callback}). Tenant and user come from the verified
 *       encrypted state. Always answers with a 302 back to the console; a failure is a fixed reason code,
 *       never remote text.</li>
 *   <li>{@code GET <app.mcp.oauth.cimd.path>} - the platform's OAuth Client ID Metadata Document (public).</li>
 * </ul>
 *
 * The literal {@code remote} segment wins over {@link OAuthProviderController}'s {@code {provider}} variable, so
 * the two never collide (no provider is ever named {@code remote}). Management routes are gated by
 * {@code PERM_MANAGE_INTEGRATIONS} in SecurityConfig ({@code /api/mcp/oauth/**} POST/DELETE). EVERYTHING except
 * the capability probe is a 404 while {@code app.mcp.oauth.enabled} is off.
 */
@RestController
public class McpOAuthController {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthController.class);
    private static final String REQUESTED_BY_FALLBACK = "system:mcp-oauth-callback";
    private static final int MAX_MESSAGE = 300;

    private final McpOAuthService service;
    private final McpOAuthRegistrationLinker linker;
    private final McpOAuthClientMetadata clientMetadata;

    public McpOAuthController(McpOAuthService service, McpOAuthRegistrationLinker linker, McpOAuthClientMetadata clientMetadata) {
        this.service = service;
        this.linker = linker;
        this.clientMetadata = clientMetadata;
    }

    public record CapabilitiesResponse(boolean enabled, boolean cimd) {
    }

    public record UrlRequest(String url) {
    }

    public record AuthorizeRequest(String url, String scope, String returnTo) {
    }

    public record AuthorizeResponse(String url, String resource, String issuer, String clientMode) {
    }

    public record StatusResponse(String state, String resource, String issuer, String scope) {
    }

    public record DisconnectResponse(boolean disconnected, int liveConnectionsDropped) {
    }

    @GetMapping("/api/mcp/oauth/remote")
    public CapabilitiesResponse capabilities() {
        boolean on = service.isEnabled();
        return new CapabilitiesResponse(on, on && service.cimdActive());
    }

    @PostMapping("/api/mcp/oauth/remote/status")
    public StatusResponse status(@RequestBody UrlRequest request) {
        requireEnabled();
        return guarded(() -> {
            McpOAuthService.Status s = service.status(urlOf(request));
            return new StatusResponse(s.state().name().toLowerCase(java.util.Locale.ROOT), s.resourceUrl(), s.issuer(), s.scope());
        });
    }

    @PostMapping("/api/mcp/oauth/remote/authorize")
    public AuthorizeResponse authorize(@RequestBody AuthorizeRequest request) {
        requireEnabled();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A server URL is required.");
        }
        return guarded(() -> {
            McpOAuthService.AuthorizationStart start =
                service.beginAuthorization(request.url(), request.scope(), request.returnTo());
            return new AuthorizeResponse(start.authorizationUrl(), start.resourceUrl(), start.issuer(), start.clientMode());
        });
    }

    @DeleteMapping("/api/mcp/oauth/remote")
    public DisconnectResponse disconnect(@RequestParam("url") String url) {
        requireEnabled();
        return guarded(() -> {
            List<String> removed = service.disconnect(url);
            int dropped = removed.isEmpty() ? 0 : linker.unlink(removed);
            return new DisconnectResponse(!removed.isEmpty(), dropped);
        });
    }

    @GetMapping("/oauth/mcp/remote/callback")
    public ResponseEntity<Void> callback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @RequestParam(required = false) String iss
    ) {
        requireEnabled();
        String returnUrl = service.returnUrlFor(state);
        if (error != null) {
            log.info("MCP OAuth consent was not completed");
            return redirect(returnUrl, "error", "consent_declined", null, null);
        }
        if (code == null || state == null) {
            return redirect(returnUrl, "error", "missing_code_or_state", null, null);
        }

        McpOAuthService.CompletedConnection completed;
        try {
            completed = service.completeAuthorization(code, state, iss);
        } catch (OAuthStateException e) {
            log.warn("Rejected MCP OAuth callback: {}", e.reason());
            return redirect(returnUrl, "error", e.reason(), null, null);
        } catch (McpOAuthException e) {
            // Messages never carry a token or a response body (see McpOAuthClient).
            log.warn("Failed to complete MCP OAuth connection: {}", e.getMessage());
            return redirect(returnUrl, "error", e instanceof McpOAuthBlockedException ? "blocked" : "exchange_failed", null, null);
        } catch (RuntimeException e) {
            log.warn("Failed to complete MCP OAuth connection: {}", e.getClass().getSimpleName());
            return redirect(returnUrl, "error", "exchange_failed", null, null);
        }

        String mcp;
        Optional<TenantContext> previous = TenantContext.current();
        String user = completed.userId() == null || completed.userId().isBlank() ? REQUESTED_BY_FALLBACK : completed.userId();
        TenantContext.set(TenantContext.of(completed.tenantId(), user, Set.of()));
        try {
            int linked = linker.link(completed.resourceUrl());
            mcp = linked > 0 ? "registered" : "none";
        } catch (RuntimeException e) {
            log.warn("MCP OAuth connected but its MCP registrations could not be linked: {}", e.toString());
            mcp = "failed";
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
        return redirect(completed.returnUrl(), "connected", null, mcp, completed.resourceUrl());
    }

    @GetMapping(value = "${app.mcp.oauth.cimd.path:" + McpOAuthClientMetadata.DEFAULT_PATH + "}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> clientMetadataDocument() {
        if (!service.isEnabled() || !clientMetadata.active()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(clientMetadata.document());
    }

    // ------------------------------------------------------------- helpers

    private void requireEnabled() {
        if (!service.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String urlOf(UrlRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A server URL is required.");
        }
        return request.url();
    }

    /** Maps the flow's exceptions to HTTP; messages are short, never contain a token or a remote body. */
    private static <T> T guarded(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (McpOAuthBlockedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, truncate(e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, truncate(e.getMessage()));
        } catch (McpOAuthException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, truncate(e.getMessage()));
        }
    }

    private static String truncate(String message) {
        String m = message == null ? "Request failed." : message;
        return m.length() > MAX_MESSAGE ? m.substring(0, MAX_MESSAGE) : m;
    }

    private static ResponseEntity<Void> redirect(String base, String result, String reason, String mcp, String server) {
        StringBuilder location = new StringBuilder(base == null ? "" : base);
        location.append(location.indexOf("?") >= 0 ? '&' : '?');
        location.append("mcp_oauth=").append(result);
        location.append("&provider=remote");
        if (reason != null) {
            location.append("&reason=").append(URLEncoder.encode(reason, StandardCharsets.UTF_8));
        }
        if (mcp != null) {
            location.append("&mcp=").append(mcp);
        }
        if (server != null) {
            location.append("&server=").append(URLEncoder.encode(server, StandardCharsets.UTF_8));
        }
        return ResponseEntity.status(302).location(URI.create(location.toString())).build();
    }
}
