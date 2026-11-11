package com.agenticai.core.security;

import com.agenticai.core.security.SecurityConfig.OidcProvider;


/**
 * Startup guard for the JWT issuer's client-bound checks ({@code azp} allowlist, required
 * audience), extracted from {@link SecurityConfig} to keep that class under the file-size cap.
 * Package-private: the public surface stays {@code SecurityConfig}'s thin delegates.
 */
final class OidcIssuerGuard {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(OidcIssuerGuard.class);

    private OidcIssuerGuard() {}

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * SEC-056 follow-up: a generic-OIDC issuer (Auth0/Entra/Okta) with BOTH client-bound checks
     * empty validates neither {@code azp} nor {@code aud} - the only way such a token was even
     * accepted before was the documented emergency lever of emptying the Keycloak-specific azp
     * allow-list. Keycloak issuers always contain {@code /realms/} (the lever stays available
     * there); any other issuer must configure at least one of the two checks or the boot refuses to
     * start.
     */
    static void requireClientCheckForNonKeycloakIssuer(
            String issuerUri, String allowedAuthorizedParties, String requiredAudience) {
        requireClientCheckForNonKeycloakIssuer(
                "", issuerUri, allowedAuthorizedParties, requiredAudience, false);
    }

    /**
     * The explicit {@code app.security.oidc.provider} value, or, when unset, one derived from the
     * issuer URI (a {@code /realms/} path means Keycloak) with a warning: the URI shape is only a
     * heuristic, so a deployment should say what it runs.
     *
     * @throws IllegalStateException for a value other than {@code keycloak} / {@code generic}
     */
    static OidcProvider resolveProvider(String configured, String issuerUri) {
        if (configured != null && !configured.isBlank()) {
            switch (configured.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "keycloak":
                    return OidcProvider.KEYCLOAK;
                case "generic":
                    return OidcProvider.GENERIC;
                default:
                    throw new IllegalStateException(
                            "app.security.oidc.provider must be 'keycloak' or 'generic', got '"
                                    + configured
                                    + "'");
            }
        }
        OidcProvider derived =
                issuerUri != null && issuerUri.contains("/realms/")
                        ? OidcProvider.KEYCLOAK
                        : OidcProvider.GENERIC;
        LOG.warn(
                "app.security.oidc.provider is not set; derived '{}' from the issuer URI. Set"
                        + " APP_SECURITY_OIDC_PROVIDER=keycloak|generic explicitly - the issuer"
                        + " URI shape is only a heuristic.",
                derived.name().toLowerCase(java.util.Locale.ROOT));
        return derived;
    }

    /**
     * Same guard, driven by the explicit {@code app.security.oidc.provider} (blank derives it from
     * the issuer URI with a warning). {@code allowUnrestricted} downgrades the failure to an ERROR
     * log ({@code app.security.jwt.allow-unrestricted-audience}).
     */
    static void requireClientCheckForNonKeycloakIssuer(
            String provider,
            String issuerUri,
            String allowedAuthorizedParties,
            String requiredAudience,
            boolean allowUnrestricted) {
        boolean keycloakStyleIssuer = resolveProvider(provider, issuerUri) == OidcProvider.KEYCLOAK;
        if (!keycloakStyleIssuer
                && allowUnrestricted
                && isBlank(allowedAuthorizedParties)
                && isBlank(requiredAudience)) {
            LOG.error(
                    "JWT issuer '{}' is a generic OIDC provider with no azp allowlist and no"
                            + " required audience: every token it signs for ANY application is"
                            + " accepted (app.security.jwt.allow-unrestricted-audience=true).",
                    issuerUri);
            return;
        }
        if (!keycloakStyleIssuer
                && isBlank(allowedAuthorizedParties)
                && isBlank(requiredAudience)) {
            throw new IllegalStateException(
                    "issuer-uri '"
                            + issuerUri
                            + "' is not a Keycloak /realms/ issuer, but both"
                            + " app.keycloak.allowed-azp (APP_KEYCLOAK_ALLOWED_AZP) and"
                            + " app.security.jwt.required-audience"
                            + " (APP_SECURITY_JWT_REQUIRED_AUDIENCE) are empty - a generic-OIDC"
                            + " deployment must configure at least one of them, or no token is"
                            + " checked against the client it was issued to.");
        }
    }

    /** {@link #requireClientCheckForNonKeycloakIssuer} behind an explicit opt-out lever. */
    static void checkTokenAcceptanceConfig(
            String issuerUri, String azp, String audience, boolean allowNoClientCheck) {
        if (!allowNoClientCheck) {
            requireClientCheckForNonKeycloakIssuer(issuerUri, azp, audience);
        }
    }
}
