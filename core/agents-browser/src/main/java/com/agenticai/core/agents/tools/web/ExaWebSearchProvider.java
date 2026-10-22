package com.agenticai.core.agents.tools.web;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exa Search adapter ({@code POST https://api.exa.ai/search}).
 *
 * <p>Verified 2026-10-04 against Exa's API reference (https://exa.ai/docs/reference/search): auth
 * is the {@code x-api-key} header (or {@code Authorization: Bearer}); the body takes {@code query},
 * {@code type} ({@code auto} default), {@code numResults} (1-100) and {@code
 * contents.text|highlights|summary}; the response is {@code results[]} of {@code title, url,
 * publishedDate, text, highlights[], summary}. Errors: 400 validation, 401 bad key, 402 out of
 * credits, 429 rate limit, 500/503 server.
 *
 * <p>Without {@code contents} Exa returns only title and URL, which is not enough for a model to
 * choose a result, so we request {@code contents.highlights} (short query-relevant excerpts) and
 * use them as the snippet. Whether highlights are billed separately from the search is a pricing
 * question to check on Exa's own pricing page - see docs/configuration.md ("Web search").
 */
public class ExaWebSearchProvider extends AbstractKeyedSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(ExaWebSearchProvider.class);
    static final String ENDPOINT = "https://api.exa.ai/search";

    public ExaWebSearchProvider(Supplier<String> keySupplier) {
        this(keySupplier, WebSearchHttp.defaultTransport());
    }

    ExaWebSearchProvider(Supplier<String> keySupplier, WebSearchHttp.Transport transport) {
        super("exa", "Exa", ENDPOINT, "Exa API key", keySupplier, transport);
    }

    @Override
    String requestJson(String query, int count) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.put("type", "auto");
        body.put("numResults", count);
        body.putObject("contents").put("highlights", true);
        return body.toString();
    }

    @Override
    HttpRequest.Builder authorize(HttpRequest.Builder builder, String key) {
        return builder.header("x-api-key", key);
    }

    /** 402 = out of credits (docs above). */
    @Override
    boolean isQuotaStatus(int status) {
        return status == 402;
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
                                WebSearchHttp.clip(snippet(r), WebSearchHttp.MAX_SNIPPET_CHARS),
                                WebSearchHttp.shortDate(text(r, "publishedDate"))));
                if (out.size() >= count) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Exa response", e);
        }
        return out;
    }

    /**
     * Highlights first (query-relevant), then a summary, then the leading page text if that is all
     * there is.
     */
    private static String snippet(JsonNode result) {
        JsonNode highlights = result.path("highlights");
        if (highlights.isArray() && !highlights.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode h : highlights) {
                if (h.isString() && !h.asString().isBlank()) {
                    if (joined.length() > 0) {
                        joined.append(" ... ");
                    }
                    joined.append(h.asString().strip());
                }
            }
            if (joined.length() > 0) {
                return joined.toString();
            }
        }
        String summary = text(result, "summary");
        return summary.isBlank() ? text(result, "text") : summary;
    }
}
