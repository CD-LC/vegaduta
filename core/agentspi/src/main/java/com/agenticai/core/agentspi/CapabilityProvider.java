package com.agenticai.core.agentspi;

import java.util.Optional;

/**
 * One candidate implementation of a capability (e.g. "can this agent speak text out loud") that the
 * platform tries, in priority order, until one is ready for the current tenant.
 *
 * <p>Introduced 2026-08-09 (see {@code docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md}) to
 * replace the pattern of one large resolution method containing N hand-written {@code if (backend
 * == null) { ... }} blocks, one per provider, in a manually-maintained priority order - see {@code
 * AgentToolProvider#resolveAudioTools}'s pre-refactor history for the shape this replaces. Adding
 * provider N+1 for a capability now means adding one new {@code @Component} bean implementing this
 * interface (via a capability-specific sub-interface, e.g. {@code
 * com.agenticai.core.agentspi.SpeakCapabilityProvider}) with a priority - no edit to any existing
 * resolution method, and no risk of disturbing the existing priority order of other providers.
 *
 * <p>{@link #resolve()} is called per-request, not cached at bean-construction time - readiness is
 * tenant-scoped (schema-per-tenant credential lookups resolve against whichever tenant schema the
 * current request thread is bound to), so a singleton bean's readiness genuinely varies call to
 * call. Implementations should mirror the defensive {@code ObjectProvider<T>.getIfAvailable()}
 * pattern used elsewhere in this package when a dependency module may be absent from a slim test
 * context, so the bean itself always constructs even when the thing it wraps isn't on the classpath
 * - it should just resolve to empty in that case, not fail to become a bean at all.
 *
 * @param <T> what this provider produces once ready (e.g. {@code SpeakBackend}).
 */
public interface CapabilityProvider<T> {

    /**
     * Lower runs first. Ties are broken by declaration order, which is not guaranteed - keep
     * priorities distinct.
     */
    int priority();

    /**
     * Empty if this provider is not ready for the current tenant/request (missing/inactive
     * credential, missing second-step config, etc.).
     */
    Optional<T> resolve();
}
