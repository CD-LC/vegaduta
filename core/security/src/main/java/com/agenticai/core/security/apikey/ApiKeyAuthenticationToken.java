package com.agenticai.core.security.apikey;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authenticated principal for a tenant REST API key (V483): a machine
 * credential minted by a tenant admin for headless callers - CI, partner
 * systems, scripts - that cannot do an OIDC login flow. Deliberately the
 * inverse of {@code DelegationAuthenticationToken}: that one carries a
 * user's authority to an agent; this one carries a key's SCOPED authority,
 * which a user delegated a subset of at issue time.
 *
 * <p>Authorities are exactly {@code PERM_<permission>} for each catalog
 * permission stored on the key (validated against the issuer's own effective
 * permissions at create time, so a key can never carry a scope its issuer
 * did not hold). No {@code ROLE_*} authorities are ever granted - an API
 * key can never mint tenant-admin or platform-admin.
 *
 * <p>{@link #getPrincipal()} is {@code apikey:<keyName>:<shortId>} - a
 * synthetic subject, never a user id, so audit rows and ACL checks can
 * never confuse a key call with the human who minted it.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    /** Authority prefix this token emits - same one the JWT converter uses for stored grants. */
    public static final String PERMISSION_AUTHORITY_PREFIX = "PERM_";

    /** {@code TenantContext#getSubject} value shape, also used by ResourceAccessService's apikey carve-out. */
    public static final String SUBJECT_PREFIX = "apikey:";

    private final String tenantId;
    private final UUID keyId;
    private final String keyName;
    private final Set<String> permissionNames;

    public ApiKeyAuthenticationToken(String tenantId, UUID keyId, String keyName, Set<String> permissionNames) {
        super(permissionNames == null ? List.of() : permissionNames.stream()
            .map(p -> new SimpleGrantedAuthority(PERMISSION_AUTHORITY_PREFIX + p))
            .map(a -> (org.springframework.security.core.GrantedAuthority) a)
            .toList());
        this.tenantId = tenantId;
        this.keyId = keyId;
        this.keyName = keyName;
        this.permissionNames = permissionNames == null ? Set.of() : Set.copyOf(permissionNames);
        setAuthenticated(true);
    }

    /** The synthetic subject - what audit rows and {@code Principal#getName} see. */
    public static String subjectFor(String keyName, UUID keyId) {
        return SUBJECT_PREFIX + keyName + ":" + keyId.toString().substring(0, 8);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return subjectFor(keyName, keyId);
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getKeyId() {
        return keyId;
    }

    public String getKeyName() {
        return keyName;
    }

    /**
     * Catalog permission names on the key WITHOUT the {@code PERM_} prefix
     * (the same shape {@code PermissionAuthoritySource} returns for users).
     */
    public Set<String> getPermissionNames() {
        return permissionNames;
    }
}
