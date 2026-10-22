package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.DependencyUnavailableException;
import com.agenticai.core.resilience.ResilienceGuard;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brave Search API client behind the {@code web_search} built-in tool.
 *
 * <p><b>Why this exists.</b> Until now an agent could {@code fetch_web_page} a URL it was already
 * given, but had no way to FIND one — which means it could read, not research. Meanwhile the
 * shipped help copy has been telling users {@code web_search} is "a real, first-party built-in
 * agent tool" in nine separate recipe files, one of which describes a "Researcher gathers, with
 * citations" pattern built entirely on it. This closes that gap rather than deleting the promise.
 *
 * <p><b>Why Brave.</b> $5/1,000 queries with $5/month of free credit applied automatically, which
 * makes the free tier genuinely free at low volume, and an independent index rather than a reseller
 * of someone else's. Tavily's free allowance is smaller and SearXNG needs us to run and keep an
 * instance healthy — an operational burden for a feature whose entire value is that it works out of
 * the box.
 *
 * <p><b>No SSRF surface.</b> Unlike {@link WebPageFetcher}, the host here is a fixed constant and
 * only the query string is caller-controlled, so there is nothing to guard against a tenant naming
 * an internal address. The results this returns are URLs the model may then hand to {@code
 * fetch_web_page}, which does its own guarding.
 *
 * <p>Never throws for a search-level failure; the caller relays a sentence.
 */
public class BraveWebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(BraveWebSearchClient.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    /** Matches WebPageFetcher's posture: a tool call must never hang a turn. */
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_RESULTS = 10;

    /** Brave caps queries at 400 chars; trimming here gives a clean refusal instead of a 422. */
    private static final int MAX_QUERY_CHARS = 400;

    private final HttpClient http;

    public BraveWebSearchClient() {
        // Guarded transport, redirects still off: a redirect chain is an SSRF primitive even
        // against a fixed host, and a search API has no legitimate reason to redirect.
        this.http = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    }

    /**
     * One result, flattened to what a model can actually use. {@code date} is optional (null/blank
     * = unknown): the Brave path never sets it (behaviour unchanged); the other {@link
     * WebSearchProvider} adapters fill it when the provider reports a publication date.
     */
    public record SearchResult(String title, String url, String description, String date) {

        /**
         * The original three-field shape (no date) - every pre-existing caller and test keeps using
         * it.
         */
        public SearchResult(String title, String url, String description) {
            this(title, url, description, null);
        }
    }

    /**
     * Runs one search. Throws {@link WebSearchException} with a person-readable reason for every
     * failure mode — the tool callback relays that sentence verbatim rather than inventing its own
     * wording.
     */
    public List<SearchResult> search(String query, Integer count, String apiKey) {
        if (query == null || query.isBlank()) {
            throw new WebSearchException("no search query was provided.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new WebSearchException(
                    "web search is not configured on this platform yet. A platform admin needs to add a "
                            + "Brave Search API key under Tool Credentials.");
        }
        String trimmed = query.strip();
        if (trimmed.length() > MAX_QUERY_CHARS) {
            trimmed = trimmed.substring(0, MAX_QUERY_CHARS);
        }
        int n = count == null ? DEFAULT_RESULTS : Math.max(1, Math.min(MAX_RESULTS, count));

        URI uri =
                URI.create(
                        ENDPOINT
                                + "?q="
                                + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
                                + "&count="
                                + n);

        // Circuit breaker (app.resilience.circuit-breaker.enabled, default false -> NOOP guard,
        // nothing
        // changes). This class is self-constructed by AgentIntegrationToolResolver, so it reads the
        // process-wide registry instead of being injected. Open circuit = the same person-readable
        // "unavailable" sentence a failed call already produces; the tool relays it, no 500.
        ResilienceGuard.Call call;
        try {
            call = CircuitBreakerRegistry.shared().guard("brave").begin();
        } catch (DependencyUnavailableException shed) {
            throw new WebSearchException(
                    "web search is temporarily unavailable. Try again shortly, or use a URL directly with fetch_web_page.");
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Accept", "application/json")
                            .header("X-Subscription-Token", apiKey)
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            // Brave answered. Only its own trouble (5xx, 429) counts against the breaker; a
            // rejected key or a
            // malformed query is not an outage.
            if (status >= 500 || status == 429) {
                call.failure(new IllegalStateException("HTTP " + status));
            } else {
                call.success();
            }
            if (status == 401 || status == 403) {
                // Deliberately does NOT echo the upstream body: the caller is a
                // tenant's agent and this is the PLATFORM's key being rejected.
                log.warn("Brave Search rejected the platform key (HTTP {})", status);
                throw new WebSearchException(
                        "web search is temporarily unavailable (the platform's search key was rejected).");
            }
            if (status == 429) {
                throw new WebSearchException(
                        "web search is rate-limited right now. Try again shortly, or use a URL directly "
                                + "with fetch_web_page.");
            }
            if (status < 200 || status >= 300) {
                log.warn("Brave Search returned HTTP {}", status);
                throw new WebSearchException(
                        "web search failed (HTTP " + status + "). Try again shortly.");
            }
            return parse(response.body());
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("web search was interrupted.");
        } catch (Exception e) {
            call.failure(e);
            log.warn("Brave Search call failed", e);
            throw new WebSearchException("could not reach the search service. Try again shortly.");
        } finally {
            call.close(); // neutral when nothing above settled it (e.g. interrupted)
        }
    }

    private static List<SearchResult> parse(String body) {
        List<SearchResult> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode results = root.path("web").path("results");
            if (!results.isArray()) {
                return out;
            }
            for (JsonNode r : results) {
                String url = r.path("url").asText(null);
                if (url == null || url.isBlank()) {
                    continue;
                }
                out.add(
                        new SearchResult(
                                r.path("title").asText(""), url, r.path("description").asText("")));
            }
        } catch (Exception e) {
            // A shape change upstream degrades to "no results", never a thrown
            // turn-killing exception.
            log.warn("Could not parse Brave Search response", e);
        }
        return out;
    }
}
