package com.agenticai.core.mcp.registry;

import java.util.List;

/**
 * A page of "Browse registry" search results plus the cursor for the next
 * page. The cursor is this platform's own opaque offset into its cached
 * catalog snapshot (see {@link OfficialMcpRegistryClient}) - not the upstream
 * registry's cursor - since matching happens locally; null/blank means this
 * was the last page.
 *
 * <p>{@code unavailable} exists because "the registry returned nothing" and
 * "we couldn't reach the registry" are completely different things to a user
 * and used to be indistinguishable: a fail-open empty page rendered as "No
 * servers match 'youtube'", which is a lie when the truth is a timed-out
 * upstream. When this is true the UI must say the registry is unreachable and
 * offer a retry, not report zero matches.
 */
public record RegistrySearchResponse(
    List<RegistrySearchResult> results,
    String nextCursor,
    boolean unavailable
) {
}
