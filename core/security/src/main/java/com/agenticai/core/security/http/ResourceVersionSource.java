package com.agenticai.core.security.http;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam for conditional requests ({@code ETag} / {@code If-Match}, RFC 9110 section 13): a module
 * that owns a top-level resource collection reports when one of its resources last changed, and
 * {@code ConditionalRequestFilter} in core/app turns that into a strong entity tag. Keeps the filter
 * free of a dependency on every feature module.
 *
 * <p>The answer must move on every change a client can make to the resource, which is why it is the
 * entity's {@code updatedAt} (set by every mutator) rather than a field some writes skip.
 */
public interface ResourceVersionSource {

    /** The collection path the resource ids sit under, e.g. {@code /api/agents}. */
    String collectionPath();

    /**
     * When the resource last changed, in the current tenant; empty when there is no such resource
     * (the request then proceeds and the handler answers 404 as usual).
     */
    Optional<Instant> lastModified(UUID id);
}
