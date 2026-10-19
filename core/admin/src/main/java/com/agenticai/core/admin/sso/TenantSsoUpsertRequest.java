package com.agenticai.core.admin.sso;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /api/tenant/sso} body. Validation of per-protocol required
 * fields lives in {@link TenantSsoService#validate} (they're conditional on
 * {@code providerType}, which bean-validation can't express cleanly).
 *
 * @param clientSecret OIDC client secret - write-only. Blank on update keeps
 *                     the stored secret; the response never contains it.
 */
public record TenantSsoUpsertRequest(
    @NotNull TenantSsoConfig.ProviderType providerType,
    @NotNull @Size(max = 255) String displayName,
    Boolean enabled,
    // OIDC
    @Size(max = 2048) String oidcDiscoveryUrl,
    @Size(max = 2048) String oidcAuthorizationUrl,
    @Size(max = 2048) String oidcTokenUrl,
    @Size(max = 2048) String oidcUserInfoUrl,
    @Size(max = 2048) String oidcJwksUrl,
    @Size(max = 2048) String oidcIssuer,
    @Size(max = 512) String oidcClientId,
    @Size(max = 2048) String clientSecret,
    // SAML
    @Size(max = 2048) String samlEntityId,
    @Size(max = 2048) String samlSsoUrl,
    String samlX509Cert,
    @Size(max = 128) String samlNameIdFormat
) {}
