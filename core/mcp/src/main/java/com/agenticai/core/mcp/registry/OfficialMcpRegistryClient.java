package com.agenticai.core.mcp.registry;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the official public MCP Registry
 * (registry.modelcontextprotocol.io) for the "Browse registry" search, and
 * hands each result to {@link RegistryServerMapper} so the frontend receives
 * launch-ready {@link RegistrySearchResult}s rather than the raw registry
 * schema.
 *
 * <p><b>Searches are served from an in-memory catalog snapshot, not from a
 * live call per keystroke.</b> Two measured facts forced this (2026-07-24):
 * <ul>
 *   <li>The registry is slow and unreliable from a normal deployment - a
 *       single {@code /v0/servers} call took 15-45s or timed out outright,
 *       repeatedly, with DNS/TLS under a second (so it's the registry's own
 *       response time, not the network here). Against the old 8s per-request
 *       timeout that meant most searches returned nothing, and a fail-open
 *       empty page rendered in the UI as "No servers match 'youtube'" -
 *       reporting zero results for a term the registry has a dozen entries
 *       for. That was the bug.</li>
 *   <li>The registry's own {@code ?search=} is substring-on-name-only, so
 *       even when it did answer, anything multi-word or description-shaped
 *       matched nothing. See {@link RegistryQueryMatcher} for what replaced
 *       it.</li>
 * </ul>
 * So the whole catalog is walked in the background (startup, then on TTL
 * expiry) and every search runs locally against that snapshot: instant,
 * wildcard-capable, and completely unaffected by how the registry is feeling.
 *
 * <p><b>Still fail-open, exactly like the rest of the MCP surface.</b> A slow
 * or down registry must degrade the browse feature, never throw into the Tools
 * screen. A stale snapshot is served as-is while a refresh runs behind it. A
 * cold snapshot falls back to one direct upstream call, and if even that
 * fails the response is flagged {@link RegistrySearchResponse#unavailable()}
 * so the UI can say "registry unreachable" instead of lying about zero
 * matches.
 */
@Component
public class OfficialMcpRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialMcpRegistryClient.class);
    private static final RegistrySearchResponse EMPTY = new RegistrySearchResponse(List.of(), null, false);
    private static final RegistrySearchResponse UNAVAILABLE = new RegistrySearchResponse(List.of(), null, true);

    private final RestClient restClient;
    private final RegistryProperties properties;
    private final RegistryServerMapper mapper;

    /** Cold-catalog fallback only - a warm catalog never reads this. */
    private final Map<String, CachedPage> fallbackCache = new ConcurrentHashMap<>();

    private final AtomicReference<Catalog> catalog = new AtomicReference<>(Catalog.cold());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    // Daemon: a half-finished catalog walk must never hold up JVM shutdown.
    private final ExecutorService refreshExecutor =
            TenantAwareExecutors.newSingleThreadExecutor("mcp-registry-catalog", true);

    /** A half-finished catalog walk is abandoned on context close (JAVA-026). */
    @jakarta.annotation.PreDestroy
    void shutdownRefreshExecutor() {
        refreshExecutor.shutdownNow();
    }

    /**
     * <b>An Apache HC5 {@link ClientHttpRequestFactory} via {@link
     * SafeHttpClients}, not {@code SimpleClientHttpRequestFactory}.</b> The
     * original pinning concern (2026-07-29) was {@code HttpURLConnection}'s
     * {@code synchronized} blocking read pinning a virtual-thread carrier on
     * JDK 21: {@code SimpleClientHttpRequestFactory} is still banned here -
     * a single upstream call measured 15-45s and each in-flight call would
     * have held a carrier. Apache HC5's blocking read does not hold a
     * monitor, so virtual threads park cleanly.
     *
     * <p>Timeouts map across unchanged in meaning:
     * {@code timeoutMillis} is still the connect timeout (moved onto the
     * connection manager) and {@code readTimeoutMillis} is still the socket
     * read timeout. Redirects stay on, as with {@code
     * HttpURLConnection}/{@code Redirect.NORMAL} - this talks to a public
     * third-party registry over HTTPS, exactly the kind of endpoint that can
     * move, and each hop is re-resolved through the SSRF guard's DNS
     * resolver.
     *
     * <p>No {@code outputStreaming}/{@code Content-Length} concern here,
     * unlike {@code SidecarClient}: every call this class makes is a
     * bodyless {@code GET} (see {@link #fetchPage}), so the request-body
     * encoding question never arises.
     */
    public OfficialMcpRegistryClient(
        RestClient.Builder restClientBuilder, RegistryProperties properties, RegistryServerMapper mapper
    ) {
        this.properties = properties;
        this.mapper = mapper;
        // Guarded Apache client under the RestClient: redirects on because this
        // is a public third-party registry that can move, and every redirect hop
        // re-resolves through SsrfGuard's dnsResolver (validated address =
        // connected address). The DNS-rebinding window a raw JDK client would
        // leave is closed.
        ClientHttpRequestFactory factory = SafeHttpClients.requestFactory(
            SsrfGuard.defaultGuard(),
            Duration.ofMillis(properties.getTimeoutMillis()),
            Duration.ofMillis(properties.getReadTimeoutMillis()),
            true);
        this.restClient = restClientBuilder
            .baseUrl(properties.getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    /**
     * One page of registry servers matching {@code query} (see {@link
     * RegistryQueryMatcher} for the matching rules; null/blank lists
     * everything), starting at {@code cursor} (null/blank for the first page).
     * Never throws.
     */
    public RegistrySearchResponse search(String query, String cursor) {
        if (!properties.isEnabled()) {
            return EMPTY;
        }
        String q = query == null ? "" : query.trim();
        int offset = parseOffset(cursor);

        Catalog snapshot = catalog.get();
        if (snapshot.isExpired(properties.getCatalogTtlSeconds())) {
            // Kick a refresh and keep going: a stale snapshot answers now,
            // a cold one falls through to the upstream call below.
            triggerRefresh();
        }
        if (!snapshot.servers().isEmpty()) {
            return page(snapshot.servers(), q, offset);
        }
        return coldFallback(q, cursor);
    }

    /**
     * Warms the catalog once the app is up, so the first person to open the
     * browse tab searches an already-populated index instead of paying the
     * cold-fetch cost. Fire-and-forget on a daemon thread - startup never
     * waits on the registry.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (properties.isEnabled() && properties.isWarmOnStartup()) {
            triggerRefresh();
        }
    }

    // --- local search over the snapshot ------------------------------------

    private RegistrySearchResponse page(List<RegistrySearchResult> servers, String q, int offset) {
        List<Pattern> terms = RegistryQueryMatcher.compile(q);
        List<RegistrySearchResult> matched = servers.stream()
            .filter(s -> RegistryQueryMatcher.matches(s, terms))
            .sorted(Comparator
                .comparingInt((RegistrySearchResult s) -> RegistryQueryMatcher.rank(s, terms))
                .thenComparing(RegistryQueryMatcher::sortKey))
            .toList();

        int size = Math.max(1, properties.getPageSize());
        int from = Math.min(offset, matched.size());
        int to = Math.min(from + size, matched.size());
        String nextCursor = to < matched.size() ? String.valueOf(to) : null;
        return new RegistrySearchResponse(List.copyOf(matched.subList(from, to)), nextCursor, false);
    }

    private static int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            // An upstream-shaped cursor left over from a page rendered before
            // this deployment restarted - start over rather than 400.
            return 0;
        }
    }

    // --- cold fallback (catalog not warm yet) -------------------------------

    /**
     * One direct upstream call, used only while the catalog is still cold.
     * Keeps the browse tab usable during the first refresh instead of showing
     * nothing for the minutes a full walk can take. Upstream's name-only
     * {@code search} is all that's available here - the good matching needs
     * the full snapshot.
     */
    private RegistrySearchResponse coldFallback(String q, String cursor) {
        String cur = cursor == null ? "" : cursor.trim();
        String cacheKey = q + " " + cur;
        int ttl = properties.getCacheTtlSeconds();
        if (ttl > 0) {
            CachedPage hit = fallbackCache.get(cacheKey);
            if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
                return hit.page();
            }
        }
        try {
            RegistryApiTypes.ListResponse body = fetchPage(q, cur, properties.getPageSize());
            RegistrySearchResponse page = new RegistrySearchResponse(
                mapAll(body), body.metadata() == null ? null : body.metadata().nextCursor(), false
            );
            if (ttl > 0 && !page.results().isEmpty()) {
                fallbackCache.put(cacheKey, new CachedPage(page, Instant.now().plusSeconds(ttl)));
            }
            return page;
        } catch (Exception e) {
            log.warn(
                "MCP registry unreachable while the catalog is still cold (query='{}') - reporting unavailable, "
                    + "not 'no results'", q, e
            );
            return UNAVAILABLE;
        }
    }

    // --- background catalog refresh ----------------------------------------

    private void triggerRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return; // one walk at a time
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshCatalog();
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RuntimeException e) {
            // Rejected (executor shutting down) - release the guard so a later
            // call can try again rather than latching "refresh in progress".
            refreshing.set(false);
            log.debug("MCP registry catalog refresh not scheduled", e);
        }
    }

    /**
     * Walks every page of the registry and replaces the snapshot. Deduplicates
     * by server name (the registry lists historical versions alongside the
     * current one; {@code version=latest} narrows that but the walk keeps the
     * first entry per name regardless, so a duplicate can't show up twice in
     * the UI).
     *
     * <p><b>Published page by page, not at the end.</b> The registry is big
     * enough and slow enough that a full walk takes many minutes; waiting for
     * it would leave the browse tab cold for that whole time. Each fetched
     * page is merged into the live snapshot immediately, so search becomes
     * useful within seconds of the first page and just keeps getting more
     * complete. It also means a walk that dies at page 7 of 30 keeps its six
     * pages instead of throwing them away - the next refresh starts over.
     */
    private void refreshCatalog() {
        Instant started = Instant.now();
        Map<String, RegistrySearchResult> byName = new LinkedHashMap<>();
        String cursor = "";
        int pages = 0;
        // "Settled", not "every page fetched": running into the page/server
        // cap is a deliberate stopping point and shouldn't put the snapshot on
        // the 5-minute retry cadence forever. Only an actual failure does.
        boolean settled = false;
        try {
            while (pages < properties.getCatalogMaxPages() && byName.size() < properties.getCatalogMaxServers()) {
                RegistryApiTypes.ListResponse body = fetchPage(null, cursor, properties.getCatalogPageSize());
                pages++;
                for (RegistrySearchResult result : mapAll(body)) {
                    byName.putIfAbsent(result.name(), result);
                }
                if (!byName.isEmpty()) {
                    catalog.set(new Catalog(List.copyOf(byName.values()), Instant.now(), false));
                }
                cursor = body.metadata() == null ? null : body.metadata().nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
            }
            settled = true;
        } catch (Exception e) {
            log.warn(
                "MCP registry catalog walk stopped after {} page(s) with {} server(s) - keeping what we have",
                pages, byName.size(), e
            );
        }

        if (byName.isEmpty()) {
            log.warn("MCP registry catalog refresh produced nothing - keeping the previous snapshot");
            return;
        }
        catalog.set(new Catalog(List.copyOf(byName.values()), Instant.now(), settled));
        log.info(
            "MCP registry catalog refreshed: {} server(s) over {} page(s) in {}s ({})",
            byName.size(), pages, Duration.between(started, Instant.now()).toSeconds(),
            settled ? "complete" : "partial - will retry sooner"
        );
    }

    // --- HTTP --------------------------------------------------------------

    private RegistryApiTypes.ListResponse fetchPage(String search, String cursor, int limit) {
        RegistryApiTypes.ListResponse body = restClient.get()
            .uri(uri -> {
                var b = uri.path("/v0/servers")
                    .queryParam("limit", limit)
                    // Only the current version of each server - the registry
                    // otherwise returns every historical version as its own
                    // entry, which is noise in a "which server do I want" list.
                    .queryParam("version", "latest");
                if (search != null && !search.isBlank()) {
                    b.queryParam("search", search);
                }
                if (cursor != null && !cursor.isBlank()) {
                    b.queryParam("cursor", cursor);
                }
                return b.build();
            })
            .retrieve()
            .body(RegistryApiTypes.ListResponse.class);
        return body == null ? new RegistryApiTypes.ListResponse(List.of(), null) : body;
    }

    private List<RegistrySearchResult> mapAll(RegistryApiTypes.ListResponse body) {
        if (body.servers() == null) {
            return List.of();
        }
        List<RegistrySearchResult> results = new ArrayList<>();
        for (RegistryApiTypes.Envelope env : body.servers()) {
            if (env == null || env.server() == null || env.server().name() == null) {
                continue;
            }
            try {
                String status = env.meta() == null || env.meta().official() == null
                    ? null : env.meta().official().status();
                results.add(mapper.map(env.server(), status));
            } catch (RuntimeException perServer) {
                // A single malformed server entry must not drop the whole
                // page - skip it, keep the rest.
                log.debug("Skipping unmappable registry server '{}'", env.server().name(), perServer);
            }
        }
        return results;
    }

    // --- state -------------------------------------------------------------

    /**
     * @param complete false while a walk is still in progress, and after one
     *                 that ended early (timeout, page cap). An incomplete
     *                 snapshot answers searches immediately - it's real data -
     *                 but gets a much shorter TTL, so a walk that died on page
     *                 2 doesn't leave 200 servers standing in for the whole
     *                 registry until tomorrow, quietly answering "no match"
     *                 for things that are simply in the pages it never
     *                 reached.
     */
    private record Catalog(List<RegistrySearchResult> servers, Instant fetchedAt, boolean complete) {

        /** Retry cadence for a snapshot that isn't a full walk yet. */
        private static final int INCOMPLETE_TTL_SECONDS = 300;

        static Catalog cold() {
            return new Catalog(List.of(), Instant.EPOCH, false);
        }

        boolean isExpired(int ttlSeconds) {
            int effective = complete ? Math.max(1, ttlSeconds) : Math.min(Math.max(1, ttlSeconds), INCOMPLETE_TTL_SECONDS);
            return fetchedAt.plusSeconds(effective).isBefore(Instant.now());
        }
    }

    private record CachedPage(RegistrySearchResponse page, Instant expiresAt) {
    }
}
