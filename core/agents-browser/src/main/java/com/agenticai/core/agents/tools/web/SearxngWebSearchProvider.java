package com.agenticai.core.agents.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SearXNG adapter - the free-to-run option: a metasearch instance the OPERATOR runs (see the {@code
 * search} compose profile in deploy/compose/docker-compose.search.yml and docs/configuration.md).
 *
 * <p>API verified 2026-10-04 against https://docs.searxng.org/dev/search_api.html: {@code GET
 * /search?q=...&format=json} with optional {@code categories}, {@code engines}, {@code language},
 * {@code pageno}, {@code time_range}; the JSON body carries {@code results[]} of {@code title, url,
 * content, publishedDate}. The {@code json} format must be listed under {@code search.formats} in
 * the instance's settings.yml; an instance that has it disabled answers <b>403 Forbidden</b> (same
 * page), which this adapter reports as a configuration problem rather than a bad key. SearXNG has
 * no result-count parameter (it returns a page of ~10-20), so the count is applied here.
 *
 * <p><b>SSRF exemption, scoped to ONE host.</b> {@link WebPageFetcher} refuses every private
 * address, and must keep doing so. A SearXNG instance is normally on the compose network ({@code
 * http://searxng:8080}) or loopback, so this adapter is the one place that talks to a non-public
 * address on purpose. That is safe only because of what is NOT variable here:
 *
 * <ul>
 *   <li>the base URL comes from operator configuration ({@code app.web-search.searxng.base-url}),
 *       never from a tool argument, a tenant, or a search result;
 *   <li>the request is built only from that base's scheme/host/port plus the fixed path {@code
 *       /search}; the model controls nothing but the URL-encoded query values, and {@link
 *       #isConfiguredOrigin} is re-checked on the finished URI before it is sent;
 *   <li>redirects are never followed ({@link WebSearchHttp#defaultTransport()}), so the instance
 *       cannot bounce this client to another internal host;
 *   <li>the exemption does NOT extend to results: every result URL a model later opens still goes
 *       through {@code fetch_web_page}'s full private-address check, so a poisoned result pointing
 *       at the SearXNG host (or anything else internal) is refused there. Results with a
 *       non-http(s) scheme are dropped here, before the model sees them.
 * </ul>
 *
 * A base URL carrying credentials, a query/fragment, a non-http scheme, or pointing at a cloud
 * instance-metadata address is rejected at construction ({@link #parseBaseUrl}) and the provider is
 * then simply "not configured".
 */
public class SearxngWebSearchProvider extends AbstractHttpSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SearxngWebSearchProvider.class);
    private static final int MAX_FIELD_CHARS = 200;

    /**
     * The validated operator-configured base (no trailing slash in its path), or null = not
     * configured.
     */
    private final URI base;

    private final String engines;
    private final String categories;
    private final String language;
    private final int maxResults;

    public SearxngWebSearchProvider(
            String baseUrl, String engines, String categories, String language, int maxResults) {
        this(baseUrl, engines, categories, language, maxResults, WebSearchHttp.defaultTransport());
    }

    SearxngWebSearchProvider(
            String baseUrl,
            String engines,
            String categories,
            String language,
            int maxResults,
            WebSearchHttp.Transport transport) {
        super("searxng", "SearXNG", transport);
        this.base = parseBaseUrl(baseUrl);
        this.engines = sanitizeList(engines);
        this.categories = sanitizeList(categories);
        this.language = sanitizeList(language);
        this.maxResults = Math.max(1, Math.min(WebSearchHttp.MAX_RESULTS, maxResults));
    }

    @Override
    public boolean configured() {
        return base != null;
    }

    @Override
    public String notConfiguredMessage() {
        return "web search is not configured on this platform yet. An operator needs to set "
                + "app.web-search.searxng.base-url (WEB_SEARCH_SEARXNG_BASE_URL) to a SearXNG instance.";
    }

    @Override
    String rejectedMessage() {
        return "web search is temporarily unavailable (the SearXNG instance refused the request - its JSON "
                + "output format may be disabled).";
    }

    /**
     * Validates the operator-supplied base URL: http/https, a host, no userinfo, no query or
     * fragment, and not a cloud instance-metadata address. Returns null (after a warning) for
     * anything else, so a typo makes the provider "not configured" instead of crashing boot or
     * widening what it will talk to. Deliberately does NOT require a public address - that is the
     * point of the exemption.
     */
    static URI parseBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || host == null
                    || host.isBlank()) {
                log.warn(
                        "app.web-search.searxng.base-url ignored: it must be an http(s) URL with a host");
                return null;
            }
            if (uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                log.warn(
                        "app.web-search.searxng.base-url ignored: credentials, query and fragment are not allowed");
                return null;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (lowerHost.startsWith("169.254.")
                    || lowerHost.equals("metadata.google.internal")
                    || lowerHost.equals("[fd00:ec2::254]")) {
                log.warn(
                        "app.web-search.searxng.base-url ignored: cloud instance-metadata addresses are refused");
                return null;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            // Operators often paste the full search URL; the base is the instance root, "/search"
            // is appended below.
            if (path.endsWith("/search")) {
                path = path.substring(0, path.length() - "/search".length());
            }
            return new URI(
                    scheme, null, host, uri.getPort(), path.isEmpty() ? null : path, null, null);
        } catch (URISyntaxException e) {
            log.warn("app.web-search.searxng.base-url ignored: not a valid URL");
            return null;
        }
    }

    /**
     * True when {@code uri} has exactly the configured scheme, host and port - the whole exemption
     * in one check.
     */
    boolean isConfiguredOrigin(URI uri) {
        return base != null
                && uri != null
                && base.getScheme().equalsIgnoreCase(uri.getScheme())
                && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort()
                && uri.getRawUserInfo() == null;
    }

    @Override
    HttpRequest buildRequest(String query, int count) {
        if (base == null) {
            throw new WebSearchException(notConfiguredMessage());
        }
        StringBuilder url =
                new StringBuilder(base.getScheme()).append("://").append(base.getRawAuthority());
        if (base.getRawPath() != null) {
            url.append(base.getRawPath());
        }
        url.append("/search?q=").append(enc(query)).append("&format=json");
        if (!categories.isEmpty()) {
            url.append("&categories=").append(enc(categories));
        }
        if (!engines.isEmpty()) {
            url.append("&engines=").append(enc(engines));
        }
        if (!language.isEmpty()) {
            url.append("&language=").append(enc(language));
        }
        URI uri = URI.create(url.toString());
        if (!isConfiguredOrigin(uri)) {
            // Cannot happen by construction; refusing is the safe answer if it ever does.
            throw new WebSearchException(
                    "web search is misconfigured (refused to contact an unexpected host).");
        }
        return HttpRequest.newBuilder(uri)
                .timeout(WebSearchHttp.TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    @Override
    List<BraveWebSearchClient.SearchResult> parse(String body, int count) {
        List<BraveWebSearchClient.SearchResult> out = new ArrayList<>();
        try {
            JsonNode results = AbstractKeyedSearchProvider.MAPPER.readTree(body).path("results");
            if (!results.isArray()) {
                return out;
            }
            int limit = Math.min(count, maxResults);
            for (JsonNode r : results) {
                String url = AbstractKeyedSearchProvider.text(r, "url");
                if (!WebSearchHttp.isWebUrl(url)) {
                    continue;
                }
                out.add(
                        new BraveWebSearchClient.SearchResult(
                                WebSearchHttp.clip(
                                        AbstractKeyedSearchProvider.text(r, "title"),
                                        WebSearchHttp.MAX_TITLE_CHARS),
                                url.strip(),
                                WebSearchHttp.clip(
                                        AbstractKeyedSearchProvider.text(r, "content"),
                                        WebSearchHttp.MAX_SNIPPET_CHARS),
                                WebSearchHttp.shortDate(
                                        AbstractKeyedSearchProvider.text(r, "publishedDate"))));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse SearXNG response", e);
        }
        return out;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Operator-supplied comma lists (engines/categories/language): trimmed and clipped; encoding
     * does the rest.
     */
    private static String sanitizeList(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        return s.length() > MAX_FIELD_CHARS ? s.substring(0, MAX_FIELD_CHARS) : s;
    }
}
