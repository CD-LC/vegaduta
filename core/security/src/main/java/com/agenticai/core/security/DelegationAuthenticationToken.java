package com.agenticai.core.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authenticated principal for a delegation token: an agent acting for a user.
 *
 * <p>{@link #getPrincipal()} is the <b>delegating user</b> (the {@code act}
 * claim), so the existing per-user sharing/ACL checks
 * ({@code AgentService#requireAccess} and friends) evaluate exactly as if the
 * user called directly - the agent inherits only what the user may do.
 * Agent provenance is carried separately ({@link #getAgentId()}, {@link
 * #getJti()}) for audit, and token scopes arrive as {@code SCOPE_*}
 * authorities so endpoints can {@code hasAuthority("SCOPE_...")} if they
 * want tighter-than-user gating. No platform {@code ROLE_*} authorities are
 * ever granted - a delegation token can never mint admin.
 */
public class DelegationAuthenticationToken extends AbstractAuthenticationToken {

    private final String delegator;
    private final String tenantId;
    private final UUID agentId;
    private final String audience;
    private final String jti;

    public DelegationAuthenticationToken(
        String tenantId, String delegator, UUID agentId, String audience,
        List<String> scopes, String jti
    ) {
        super(scopes == null ? List.of() : scopes.stream()
            .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
            .toList());
        this.tenantId = tenantId;
        this.delegator = delegator;
        this.agentId = agentId;
        this.audience = audience;
        this.jti = jti;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    /** The delegating user's subject - what ACL checks see. */
    @Override
    public Object getPrincipal() {
        return delegator;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getAudience() {
        return audience;
    }

    public String getJti() {
        return jti;
    }

    @Override
    public Collection<GrantedAuthority> getAuthorities() {
        return super.getAuthorities();
    }
}
