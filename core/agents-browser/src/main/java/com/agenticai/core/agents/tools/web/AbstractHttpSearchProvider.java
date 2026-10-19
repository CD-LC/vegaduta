package com.agenticai.core.agents.tools.web;

import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.DependencyUnavailableException;
import com.agenticai.core.resilience.ResilienceGuard;
import java.net.http.HttpRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared call/error/limit semantics of the HTTP search adapters (SearXNG, Tavily, Exa, Serper),
 * written to mirror {@link BraveWebSearchClient#search} so a model sees the same sentences
 * whichever provider answered: 401/403 = credential rejected (never echoing the upstream body), 429
 * = rate-limited, a provider's own "quota used up" status = quota sentence, any other non-2xx =
 * "failed (HTTP n)", transport trouble = "could not reach the search service". Only 5xx / 429 /
 * transport trouble count against the circuit breaker ({@code
 * app.resilience.circuit-breaker.enabled}, default off = no-op guard).
 *
 * <p>Subclasses only build the request and parse the body. Never throws for a search-level failure
 * other than {@link WebSearchException}.
 */
abstract class AbstractHttpSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpSearchProvider.class);

    private final String id;
    private final String label;
    private final WebSearchHttp.Transport transport;

    protected AbstractHttpSearchProvider(
            String id, String label, WebSearchHttp.Transport transport) {
        this.id = id;
        this.label = label;
        this.transport = transport;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String label() {
        return label;
    }

    /**
     * Builds the request for an already-validated query and result count. Must throw {@link
     * WebSearchException} (typically {@link #notConfiguredMessage()}) when the provider has no
     * credential/base URL - this runs BEFORE the circuit breaker is consulted.
     */
    abstract HttpRequest buildRequest(String query, int count);

    /**
     * Maps a 2xx body to results (at most {@code count}). A shape change degrades to an empty list,
     * never a throw.
     */
    abstract List<BraveWebSearchClient.SearchResult> parse(String body, int count);

    /** A provider-specific "plan/credits used up" status (Tavily 432/433, Exa 402). */
    boolean isQuotaStatus(int status) {
        return false;
    }

    /** The sentence for 401/403. Never mentions the key itself. */
    String rejectedMessage() {
        return "web search is temporarily unavailable (the platform's "
                + label
                + " key was rejected).";
    }

    @Override
    public final List<BraveWebSearchClient.SearchResult> search(String query, Integer count) {
        String q = WebSearchHttp.requireQuery(query);
        int n = WebSearchHttp.clampCount(count);
        HttpRequest request = buildRequest(q, n);

        ResilienceGuard.Call call;
        try {
            call = CircuitBreakerRegistry.shared().guard("web-search-" + id).begin();
        } catch (DependencyUnavailableException shed) {
            throw new WebSearchException(
                    "web search is temporarily unavailable. Try again shortly, or use a URL directly with fetch_web_page.");
        }
        try {
            WebSearchHttp.Reply reply = transport.send(request);
            int status = reply.status();
            if (status >= 500 || status == 429) {
                call.failure(new IllegalStateException("HTTP " + status));
            } else {
                call.success();
            }
            if (status == 401 || status == 403) {
                log.warn(
                        "{} rejected the configured credential or request (HTTP {})",
                        label,
                        status);
                throw new WebSearchException(rejectedMessage());
            }
            if (status == 429) {
                throw new WebSearchException(
                        "web search is rate-limited right now. Try again shortly, or use a URL directly "
                                + "with fetch_web_page.");
            }
            if (isQuotaStatus(status)) {
                log.warn("{} reports its plan quota is used up (HTTP {})", label, status);
                throw new WebSearchException(
                        "web search is temporarily unavailable (the "
                                + label
                                + " plan's quota is used up).");
            }
            if (status < 200 || status >= 300) {
                log.warn("{} returned HTTP {}", label, status);
                throw new WebSearchException(
                        "web search failed (HTTP " + status + "). Try again shortly.");
            }
            return parse(reply.body(), n);
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("web search was interrupted.");
        } catch (Exception e) {
            call.failure(e);
            log.warn("{} call failed", label, e);
            throw new WebSearchException("could not reach the search service. Try again shortly.");
        } finally {
            call.close(); // neutral when nothing above settled it (e.g. interrupted)
        }
    }
}
