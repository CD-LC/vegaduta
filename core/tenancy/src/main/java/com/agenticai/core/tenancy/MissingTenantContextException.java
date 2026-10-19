package com.agenticai.core.tenancy;

/**
 * F-14 - thrown by {@link TenantSchemaResolver} instead of silently falling
 * back to the {@code public} schema, but ONLY when both of these hold:
 *
 * <ul>
 *   <li>{@code app.tenancy.fail-on-missing-context=true} (default
 *       {@code false} - see the resolver's javadoc for why this flag must
 *       stay off until staging metrics prove the suspicious set empty), and
 *   <li>the fallback fired on the one path the resolver can classify as
 *       reliably suspicious: the current thread still carries an
 *       authenticated JWT WITH a {@code tenant_id} claim, yet
 *       {@link com.agenticai.core.security.TenantContext} is empty -
 *       meaning {@code TenantContextFilter} should have populated it and
 *       either filter ordering regressed or an async hop propagated the
 *       Spring Security context without propagating {@code TenantContext}.
 * </ul>
 *
 * <p>Extends {@link IllegalStateException} because that is what every
 * tenant-scoped controller in this codebase already throws for
 * "SecurityConfig should have blocked this" conditions - callers that
 * already handle that failure shape keep working. A dedicated type (rather
 * than a raw {@code IllegalStateException}) so the eventual staging rollout
 * can alert on exactly this class name.
 */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
