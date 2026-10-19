package com.agenticai.core.agents.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tavily Search adapter ({@code POST https://api.tavily.com/search}).
 *
 * <p>Verified 2026-10-04 against Tavily's API reference
 * (https://docs.tavily.com/documentation/api-reference/endpoint/search): auth is {@code
 * Authorization: Bearer tvly-...}; the body takes {@code query}, {@code max_results} (0-20), {@code
 * search_depth} ({@code basic} = 1 credit), {@code topic}, {@code include_answer}, {@code
 * include_raw_content}, {@code include_images}, {@code include_published_date}; the response is
 * {@code results[]} of {@code title, url, content, score, published_date}. Errors: 401 bad key, 429
 * rate limit, 432 plan/key limit exceeded, 433 pay-as-you-go limit exceeded.
 *
 * <p>We always ask for the cheapest depth ({@code basic}) and no generated answer or raw page text:
 * the agent reads pages itself with {@code fetch_web_page}, and a Tavily answer would be a second
 * model's claim entering the citation chain unlabelled.
 */
public class TavilyWebSearchProvider extends AbstractKeyedSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchProvider.class);
    static final String ENDPOINT = "https://api.tavily.com/search";

    public TavilyWebSearchProvider(Supplier<String> keySupplier) {
        this(keySupplier, WebSearchHttp.defaultTransport());
    }

    TavilyWebSearchProvider(Supplier<String> keySupplier, WebSearchHttp.Transport transport) {
        super("tavily", "Tavily", ENDPOINT, "Tavily API key", keySupplier, transport);
    }

    @Override
    String requestJson(String query, int count) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.put("max_results", count);
        body.put("search_depth", "basic");
        body.put("topic", "general");
        body.put("include_answer", false);
        body.put("include_raw_content", false);
        body.put("include_images", false);
        body.put("include_published_date", true);
        return body.toString();
    }

    @Override
    HttpRequest.Builder authorize(HttpRequest.Builder builder, String key) {
        return builder.header("Authorization", "Bearer " + key);
    }

    /** 432 = plan/key limit exceeded, 433 = pay-as-you-go limit exceeded (docs above). */
    @Override
    boolean isQuotaStatus(int status) {
        return status == 432 || status == 433;
    }

    @Override
    List<BraveWebSearchClient.SearchResult> parse(String body, int count) {
        List<BraveWebSearchClient.SearchResult> out = new ArrayList<>();
        try {
            JsonNode results = MAPPER.readTree(body).path("results");
            if (!results.isArray()) {
                return out;
            }
            for (JsonNode r : results) {
                String url = text(r, "url");
                if (!WebSearchHttp.isWebUrl(url)) {
                    continue;
                }
                out.add(
                        new BraveWebSearchClient.SearchResult(
                                WebSearchHttp.clip(text(r, "title"), WebSearchHttp.MAX_TITLE_CHARS),
                                url.strip(),
                                WebSearchHttp.clip(
                                        text(r, "content"), WebSearchHttp.MAX_SNIPPET_CHARS),
                                WebSearchHttp.shortDate(text(r, "published_date"))));
                if (out.size() >= count) {
                    break;
                }
            }
        } catch (Exception e) {
            // A shape change upstream degrades to "no results", never a thrown turn-killing
            // exception.
            log.warn("Could not parse Tavily response", e);
        }
        return out;
    }
}
