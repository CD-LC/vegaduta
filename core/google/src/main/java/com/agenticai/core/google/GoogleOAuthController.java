package com.agenticai.core.google;

import com.agenticai.core.google.dto.GoogleConnectionResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.publicurl.PublicWebUrl;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Two distinct surfaces, deliberately split by path prefix the same way Twilio/Meta split their
 * credential-management API from their inbound webhook:
 *
 * <ul>
 *   <li>{@code /api/channels/google/*} - normal JWT-authenticated console calls ({@link
 *       #authorizeUrl}, {@link #status}, {@link #disconnect}).
 *   <li>{@code /oauth/google/callback} - Google's own redirect back to this platform after the user
 *       consents, which (like any third-party OAuth redirect or webhook) carries no JWT at all.
 *       Tenant identity instead travels through the encrypted {@code state} value {@link
 *       GoogleOAuthService#decodeConnectState} restores - see that class's javadoc.
 * </ul>
 *
 * <p><b>Authorization tiers (V215)</b> - two deliberately different gates, both enforced in {@code
 * SecurityConfig} without a new rule for the second:
 *
 * <ul>
 *   <li>Tenant-wide connect/disconnect ({@link #authorizeUrl}, {@link #disconnect}) - {@code
 *       ROLE_tenant-admin}, via the two exact-path {@code requestMatchers} lines SecurityConfig has
 *       always had for them. Connecting the account every member falls back to is an admin
 *       decision.
 *   <li>Personal connect/disconnect ({@link #personalAuthorizeUrl}, {@link #disconnectPersonal}) -
 *       any authenticated tenant member. The {@code /api/channels/google/personal/*} paths do not
 *       match the admin rules (those matchers are exact paths, not prefixes) and so fall through to
 *       SecurityConfig's terminal {@code .anyRequest().authenticated()} - which is precisely the
 *       intended gate, chosen so this workstream did not have to touch SecurityConfig (a live merge
 *       hotspot) at all. Connecting YOUR OWN Google account must not require being an admin -
 *       requiring it was the structural half of the pre-V215 problem.
 * </ul>
 */
@RestController
public class GoogleOAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthController.class);
    private static final String REQUESTED_BY = "system:google-oauth-callback";

    private final GoogleOAuthService oauthService;
    private final String consoleRedirectUri;

    /**
     * {@code consoleRedirectUri} is the dedicated property when set, else {@code
     * <app.web.public-url>/admin/integrations}; blank when neither is configured (no localhost
     * default, AGENTS.md rule 4) - {@link #redirectTo} then refuses with a message naming both.
     */
    public GoogleOAuthController(
            GoogleOAuthService oauthService,
            @Value("${app.google.oauth.console-redirect-uri:}") String consoleRedirectUri,
            @Value("${app.web.public-url:}") String appPublicUrl) {
        this.oauthService = oauthService;
        this.consoleRedirectUri =
                PublicWebUrl.consoleRedirect(
                        consoleRedirectUri, appPublicUrl, "/admin/integrations");
    }

    /**
     * Tenant-wide connect (admin-gated) - mints the consent URL for the {@code subject_key IS NULL}
     * fallback credential.
     */
    @GetMapping("/api/channels/google/authorize-url")
    public AuthorizeUrlResponse authorizeUrl(HttpServletResponse response) {
        return withBindingCookie(oauthService.authorizeRedirect(), response);
    }

    /**
     * Personal connect (any authenticated member - see the class javadoc's authorization-tiers
     * note) - mints a consent URL whose encrypted state carries the caller's own Keycloak {@code
     * sub}, so the JWT-less callback can attribute the resulting credential to them.
     */
    @GetMapping("/api/channels/google/personal/authorize-url")
    public AuthorizeUrlResponse personalAuthorizeUrl(HttpServletResponse response) {
        return withBindingCookie(oauthService.personalAuthorizeRedirect(), response);
    }

    /**
     * Tenant-wide, admin-gated - the separate "Enable YouTube publishing" consent, additive to an
     * existing Drive/Gmail/Calendar/Sheets connection. See {@link
     * GoogleOAuthService#youtubeUploadAuthorizeUrl} and {@link GoogleScopes#YOUTUBE_UPLOAD} for why
     * this is its own scope and its own button rather than folded into {@link #authorizeUrl}.
     */
    @GetMapping("/api/channels/google/youtube-upload/authorize-url")
    public AuthorizeUrlResponse youtubeUploadAuthorizeUrl(HttpServletResponse response) {
        return withBindingCookie(oauthService.youtubeUploadAuthorizeRedirect(), response);
    }

    /**
     * SEC-018: set the browser-binding cookie (HttpOnly, Secure, SameSite=Lax, callback-path
     * scoped).
     */
    private static AuthorizeUrlResponse withBindingCookie(
            GoogleOAuthService.AuthorizeRedirect redirect, HttpServletResponse response) {
        if (redirect.bindingCookie() != null && response != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, redirect.bindingCookie().toString());
        }
        return new AuthorizeUrlResponse(redirect.url());
    }

    /**
     * The EFFECTIVE connection for the caller - their own if they connected one, else the
     * tenant-wide account, with {@code connectionScope} in the response saying which (that field is
     * what keeps this endpoint's V215 semantics honest without breaking the existing Integrations
     * panel: members of a long-connected tenant still see "connected", not a data-loss-shaped empty
     * state, and callers that need "mine vs the tenant's" now have it). 204 only when NEITHER
     * exists.
     */
    @GetMapping("/api/channels/google/status")
    public ResponseEntity<GoogleConnectionResponse> status() {
        Optional<GoogleCredential> credential = oauthService.status();
        return credential
                .map(c -> ResponseEntity.ok(GoogleConnectionResponse.from(c)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Whether this deployment has a Google OAuth client at all. The console uses it to explain how
     * to set one up instead of offering a button that can only fail. No secret leaves here: the
     * redirect URI is the public callback the operator registers with Google.
     */
    @GetMapping("/api/channels/google/oauth-config")
    public java.util.Map<String, Object> oauthConfig() {
        return java.util.Map.of(
                "configured", oauthService.isConfigured(),
                "redirectUri", oauthService.redirectUri(),
                "mode", oauthService.loopbackMode() ? "loopback" : "web",
                "problem", oauthService.redirectNotLoopback() ? "redirect_not_loopback" : "");
    }

    /**
     * Tenant-wide disconnect (admin-gated) - strictly scoped to the {@code subject_key IS NULL}
     * row; never touches members' personal rows.
     */
    @DeleteMapping("/api/channels/google/connection")
    public ResponseEntity<Void> disconnect() {
        oauthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    /**
     * Personal disconnect (any authenticated member) - revokes ONLY the caller's own row, resolved
     * from their JWT subject; a no-op (still 204, idempotent like {@link #disconnect}) when they
     * never connected one.
     */
    @DeleteMapping("/api/channels/google/personal/connection")
    public ResponseEntity<Void> disconnectPersonal() {
        oauthService.disconnectPersonal();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/oauth/google/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = "vd_oauth_google", required = false) String bindingCookie) {
        // Decode state up front (best-effort): the consent's PURPOSE is what
        // tells an incremental-scope callback apart from a base connect, and
        // it matters even on the error path - a declined youtube.readonly
        // consent still leaves a working base connection behind, so it must
        // land on "connected", not a connect-failure banner.
        GoogleOAuthService.DecodedState decoded = null;
        if (state != null) {
            try {
                decoded = oauthService.decodeConnectState(state);
            } catch (GoogleOAuthStateException e) {
                if (error == null) {
                    log.warn(
                            "Rejected Google OAuth callback with an invalid/expired state: {}",
                            e.getMessage());
                    return redirectTo("error", "invalid_state");
                }
            }
        }
        if (error != null) {
            log.info("Google OAuth consent was not completed: {}", error);
            if (decoded != null
                    && GoogleOAuthService.PURPOSE_YOUTUBE_READONLY.equals(decoded.purpose())) {
                return redirectTo("connected", "youtube_skipped");
            }
            return redirectTo("error", "consent_declined");
        }
        if (code == null || decoded == null) {
            return redirectTo(
                    "error",
                    decoded == null && state != null ? "invalid_state" : "missing_code_or_state");
        }
        // SEC-018: the state must be bound (single use, unexpired, same tenant/subject, and - when
        // the
        // browser carries it - the same browser) to the authenticated /authorize-url call that
        // minted it.
        try {
            oauthService.verifyBinding(decoded, bindingCookie);
        } catch (GoogleOAuthStateException e) {
            log.warn(
                    "Rejected Google OAuth callback whose state is not bound to its connect"
                            + " session: {}",
                    e.getMessage());
            return redirectTo("error", "invalid_state");
        }

        // The synthetic REQUESTED_BY stays the TenantContext subject even for
        // a personal connect: TenantContext here exists to route the schema,
        // while WHO consented travels as an explicit argument straight from
        // the cryptographically-verified state - deliberately not through the
        // ambient context, so the attribution can never be confused with (or
        // spoofed by) whatever subject happens to be on the thread.
        TenantContext.set(TenantContext.of(decoded.schema(), REQUESTED_BY, Set.of()));
        try {
            GoogleCredential credential =
                    oauthService.connect(
                            code, decoded.subjectKey(), decoded.purpose(), decoded.nonce());
            if (decoded.purpose() == null && !credential.hasScope(GoogleScopes.YOUTUBE_READONLY)) {
                // Base connect succeeded - chain straight into the
                // youtube.readonly incremental consent instead of landing back
                // on the console. Google forbids drive.file+youtube.readonly
                // in ONE request (see GoogleScopes#YOUTUBE_READONLY), so this
                // second hop is how the connect flow still ends with the same
                // grant set the old bundled REQUESTED produced. login_hint
                // pre-selects the just-connected Google account, and connect()
                // refuses to save if a different account completes it anyway.
                GoogleOAuthService.AuthorizeRedirect chained =
                        oauthService.chainedYoutubeReadonlyAuthorizeRedirect(
                                decoded.schema(),
                                decoded.subjectKey(),
                                credential.getConnectedEmail());
                return ResponseEntity.status(302)
                        .location(URI.create(chained.url()))
                        .header(HttpHeaders.SET_COOKIE, chained.bindingCookie().toString())
                        .build();
            }
            return redirectTo("connected", null);
        } catch (RuntimeException e) {
            log.warn("Failed to complete Google OAuth connection: {}", e.getMessage());
            if (GoogleOAuthService.PURPOSE_YOUTUBE_READONLY.equals(decoded.purpose())) {
                // The base connection is already stored and usable - a failed
                // incremental leg must not surface as a connect failure.
                return redirectTo("connected", "youtube_skipped");
            }
            return redirectTo("error", "exchange_failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<Void> redirectTo(String googleParam, String reasonParam) {
        String location =
                PublicWebUrl.require(consoleRedirectUri, "app.google.oauth.console-redirect-uri")
                        + "?google="
                        + googleParam
                        + (reasonParam != null ? "&reason=" + reasonParam : "");
        return ResponseEntity.status(302)
                .location(URI.create(location))
                .header(
                        HttpHeaders.SET_COOKIE,
                        OAuthStateBinding.clearCookie(GoogleOAuthService.BINDING_PROVIDER)
                                .toString())
                .build();
    }

    public record AuthorizeUrlResponse(String url) {}
}
