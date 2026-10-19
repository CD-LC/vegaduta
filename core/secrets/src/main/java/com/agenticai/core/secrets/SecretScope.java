package com.agenticai.core.secrets;

/**
 * Who a vault secret belongs to.
 *
 * <p>This distinction is the whole reason the vault is not just another
 * {@code *_credential} table. Every existing credential table in the platform
 * is a tenant singleton, which can only ever express "the workspace owner's
 * key". A consumer vault has to also express "this end user's own password",
 * which must not be readable by anyone else in the same workspace - including
 * the workspace admin.
 */
public enum SecretScope {

    /**
     * Shared across the workspace. Any agent run in the tenant may resolve it.
     * This is the right scope for the business's own API keys.
     */
    TENANT,

    /**
     * Private to one end user ({@code owner_user_id}). Resolvable ONLY when the
     * run was initiated by that same user - see
     * {@link SecretVaultService#resolveForRun}. A tenant admin listing the
     * vault sees that the secret exists, never its value.
     */
    USER
}
