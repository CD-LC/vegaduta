package com.agenticai.core.admin;

import com.agenticai.core.admin.dto.SocialProvidersResponse;
import com.agenticai.core.admin.identity.IdentityProviderAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakIdentityProviderRepresentation;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/auth/social-providers} - public (unauthenticated, see {@code SecurityConfig}'s
 * {@code permitAll()} rule for this exact path) so the signup page can ask, before anyone has a
 * token, which social login buttons will actually work right now.
 *
 * <p>Reconciles the frontend/backend drift flagged in an infra audit: {@code
 * web/app/components/signup/SocialLoginRow.tsx} used to render Google/GitHub/Microsoft
 * unconditionally because it had no way to ask Keycloak at build time which brokers were configured
 * - clicking a not-yet-configured one 404s on Keycloak's own login page. This queries Keycloak's
 * REAL, LIVE identity-provider state via {@link IdentityProviderAdminPort#listIdentityProviders()}
 * (the Admin REST API, proxied server-side - deliberately not something the browser could call
 * directly, since that API requires the {@code agentic-ai-core} service account's bearer token)
 * rather than parsing the static {@code agentic-ai-realm.json} import file: {@code enabled} there
 * is always {@code false} for all three brokers (placeholder {@code REPLACE_WITH_..._CLIENT_ID}
 * config) - the actual on/off state this realm runs with today is patched in afterward by {@code
 * deploy/enable-social-idps.sh}/{@code reconcile-keycloak.sh} straight against the running
 * Keycloak, and never written back to that file. Reading the live admin API is the only way this
 * endpoint's answer matches reality.
 *
 * <p><b>Caching, stated honestly:</b> a bare unauthenticated endpoint that's hit on every anonymous
 * visit to /signup would otherwise mint a fresh Keycloak admin token AND make an admin-API call per
 * page load ({@link IdentityProviderAdminPort}'s own javadoc already flags "no token caching" as
 * fine only "at admin-panel usage volumes" - a public signup page is not that). A simple
 * process-local TTL cache (no Redis, no external dependency) is enough here: this data changes only
 * when an operator manually toggles a broker, correctness does not depend on every `core` instance
 * agreeing within the same millisecond, and a stale answer for at most {@link #CACHE_TTL} after
 * such a toggle (worst case: a button that doesn't appear/disappear instantly) is a fully
 * acceptable trade for not hammering Keycloak's admin API on every anonymous page load.
 */
// Keycloak-only: it reads the realm's live IdP instances through IdentityProviderAdminPort,
// which the Auth0 adapter deliberately does not implement (user admin only) - so under
// app.identity.provider=auth0 there is nothing to inject and /api/auth/social-providers 404s.
@RestController
@RequestMapping("/api/auth")
@Profile("security")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "keycloak", matchIfMissing = true)
public class SocialAuthConfigController {

    private static final Logger log = LoggerFactory.getLogger(SocialAuthConfigController.class);

    // The only three aliases SocialLoginRow.tsx knows how to render a
    // button for - see that file's PROVIDERS array. Any other broker
    // enabled in the realm (there are none today) is intentionally not
    // surfaced here; this endpoint answers "which of OUR three buttons
    // should show," not "list every identity provider."
    private static final Set<String> KNOWN_ALIASES = Set.of("google", "github", "microsoft");

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final IdentityProviderAdminPort keycloakAdminClient;
    private final AtomicReference<CachedResult> cache = new AtomicReference<>();

    public SocialAuthConfigController(IdentityProviderAdminPort keycloakAdminClient) {
        this.keycloakAdminClient = keycloakAdminClient;
    }

    @GetMapping("/social-providers")
    public SocialProvidersResponse socialProviders() {
        CachedResult cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAtMillis() < CACHE_TTL.toMillis()) {
            return new SocialProvidersResponse(cached.providers());
        }

        try {
            List<String> enabled =
                    keycloakAdminClient.listIdentityProviders().stream()
                            .filter(
                                    idp ->
                                            idp.alias() != null
                                                    && KNOWN_ALIASES.contains(idp.alias()))
                            .filter(idp -> Boolean.TRUE.equals(idp.enabled()))
                            .map(KeycloakIdentityProviderRepresentation::alias)
                            .toList();
            cache.set(new CachedResult(enabled, now));
            return new SocialProvidersResponse(enabled);
        } catch (RuntimeException e) {
            // Fail closed on the buttons (empty list), never fail the whole
            // signup page - a Keycloak admin-API hiccup here shouldn't take
            // down a page that otherwise works fine without social login.
            // Serve the last known-good cached answer if one exists rather
            // than flashing "no social buttons" for one unlucky request.
            log.warn("Could not refresh social-provider state from Keycloak - {}", e.getMessage());
            if (cached != null) {
                return new SocialProvidersResponse(cached.providers());
            }
            return new SocialProvidersResponse(List.of());
        }
    }

    private record CachedResult(List<String> providers, long fetchedAtMillis) {}
}
