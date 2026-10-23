package com.agenticai.core.omnichannel.x;

import com.agenticai.core.omnichannel.x.dto.XAppRegistrationRequest;
import com.agenticai.core.omnichannel.x.dto.XConnectionResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.publicurl.PublicWebUrl;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Three surfaces, same split-by-path-prefix reasoning as {@code GoogleOAuthController}:
 *
 * <ul>
 *   <li>{@code POST/GET /api/channels/x/credential} - BYOK app registration (step 1 of 2, see
 *       {@code XCredential}'s javadoc), a normal JWT- authenticated console call.
 *   <li>{@code GET /api/channels/x/authorize-url}, {@code DELETE /api/channels/x/connection} - also
 *       normal JWT-authenticated console calls, managing step 2 (the OAuth connection itself).
 *   <li>{@code GET /oauth/x/callback} - X's own redirect back to this platform after consent,
 *       carrying no JWT at all - tenant identity (and the PKCE verifier) travel through the
 *       encrypted {@code state} value, exactly like {@code GoogleOAuthController#callback}.
 * </ul>
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XOAuthController {

    private static final Logger log = LoggerFactory.getLogger(XOAuthController.class);
    private static final String REQUESTED_BY = "system:x-oauth-callback";

    private final XOAuthService oauthService;
    private final String consoleRedirectUri;

    /**
     * {@code consoleRedirectUri} is the dedicated property when set, else {@code
     * <app.web.public-url>/admin/channels}; blank when neither is configured (no localhost default,
     * AGENTS.md rule 4) - {@link #redirectTo} then refuses with a message naming both.
     */
    public XOAuthController(
            XOAuthService oauthService,
            @Value("${app.x.oauth.console-redirect-uri:}") String consoleRedirectUri,
            @Value("${app.web.public-url:}") String appPublicUrl) {
        this.oauthService = oauthService;
        this.consoleRedirectUri =
                PublicWebUrl.consoleRedirect(consoleRedirectUri, appPublicUrl, "/admin/channels");
    }

    @PostMapping("/api/channels/x/credential")
    public ResponseEntity<XConnectionResponse> registerApp(
            @Valid @RequestBody XAppRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(oauthService.registerApp(request));
    }

    @GetMapping("/api/channels/x/credential")
    public ResponseEntity<XConnectionResponse> current() {
        return oauthService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/channels/x/authorize-url")
    public AuthorizeUrlResponse authorizeUrl(HttpServletResponse response) {
        XOAuthService.AuthorizeRedirect redirect = oauthService.authorizeRedirect();
        // SEC-018 browser binding (HttpOnly, Secure, SameSite=Lax, scoped to /oauth/x/callback).
        if (redirect.bindingCookie() != null && response != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, redirect.bindingCookie().toString());
        }
        return new AuthorizeUrlResponse(redirect.url());
    }

    @DeleteMapping("/api/channels/x/connection")
    public ResponseEntity<Void> disconnect() {
        oauthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/oauth/x/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = "vd_oauth_x", required = false) String bindingCookie) {
        if (error != null) {
            log.info("X OAuth consent was not completed: {}", error);
            return redirectTo("error", "consent_declined");
        }
        if (code == null || state == null) {
            return redirectTo("error", "missing_code_or_state");
        }

        XOAuthService.DecodedState decoded;
        try {
            decoded = oauthService.decodeState(state);
        } catch (XOAuthStateException e) {
            log.warn("Rejected X OAuth callback with an invalid/expired state: {}", e.getMessage());
            return redirectTo("error", "invalid_state");
        }
        try {
            oauthService.verifyBinding(decoded, bindingCookie);
        } catch (XOAuthStateException e) {
            log.warn(
                    "Rejected X OAuth callback whose state is not bound to its connect session: {}",
                    e.getMessage());
            return redirectTo("error", "invalid_state");
        }

        TenantContext.set(TenantContext.of(decoded.tenantSchema(), REQUESTED_BY, Set.of()));
        try {
            oauthService.connect(code, decoded.codeVerifier());
            return redirectTo("connected", null);
        } catch (RuntimeException e) {
            log.warn("Failed to complete X OAuth connection: {}", e.getMessage());
            return redirectTo("error", "exchange_failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<Void> redirectTo(String xParam, String reasonParam) {
        String location =
                PublicWebUrl.require(consoleRedirectUri, "app.x.oauth.console-redirect-uri")
                        + "?x="
                        + xParam
                        + (reasonParam != null ? "&reason=" + reasonParam : "");
        return ResponseEntity.status(302)
                .location(URI.create(location))
                .header(
                        HttpHeaders.SET_COOKIE,
                        OAuthStateBinding.clearCookie(XOAuthService.BINDING_PROVIDER).toString())
                .build();
    }

    public record AuthorizeUrlResponse(String url) {}
}
