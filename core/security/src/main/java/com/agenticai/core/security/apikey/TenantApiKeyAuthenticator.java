package com.agenticai.core.security.apikey;

import java.util.Optional;

/**
 * SPI bridging {@link ApiKeyAuthFilter} to the tenant-API-key store (which
 * lives in {@code core/tenancy} - this module cannot depend on it, same
 * shape as {@code PermissionAuthoritySource}/{@code
 * DelegatedTokenAuthenticator}). Exactly one implementation exists:
 * {@code TenantApiKeyService} in {@code core-tenancy}.
 *
 * <p>The implementation owns the whole resolution: raw key -> tenant schema
 * via the control-plane link table, then the key row itself under that
 * tenant's {@code TenantContext} (set and cleared internally - this SPI is
 * called before {@code TenantContextFilter} runs and must not rely on or
 * leak a context). Empty means "this key is unknown, disabled or expired" -
 * the caller cannot distinguish which, deliberately (the outside response is
 * the same 401 either way).
 */
public interface TenantApiKeyAuthenticator {

    /**
     * Authenticates a presented {@code vdak_} key. {@code rawKey} includes the
     * prefix. Returns the authenticated token on success, empty on any
     * failure (unknown key, revoked, disabled, expired, unknown tenant).
     */
    Optional<ApiKeyAuthenticationToken> authenticate(String rawKey);
}
