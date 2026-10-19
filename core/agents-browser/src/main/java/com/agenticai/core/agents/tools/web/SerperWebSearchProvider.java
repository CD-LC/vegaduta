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
 * Serper adapter ({@code POST https://google.serper.dev/search}) - a paid API returning Google
 * results.
 *
 * <p>Verified 2026-10-04 against https://serper.dev/ (the public docs are thin): auth is the {@code
 * X-API-KEY} header; the body takes {@code q} and {@code num}; the response carries {@code
 * organic[]} of {@code title, link, snippet, date} (date only when available). NOT confirmed from
 * the docs: the exact status code for "out of credits" - an unrecognised non-2xx status therefore
 * surfaces as the generic "web search failed (HTTP n)" sentence and, when a fallback is configured,
 * degrades like any other failure.
 */
public class SerperWebSearchProvider extends AbstractKeyedSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SerperWebSearchProvider.class);
    static final String ENDPOINT = "https://google.serper.dev/search";

    public SerperWebSearchProvider(Supplier<String> keySupplier) {
        this(keySupplier, WebSearchHttp.defaultTransport());
    }

    SerperWebSearchProvider(Supplier<String> keySupplier, WebSearchHttp.Transport transport) {
        super("serper", "Serper", ENDPOINT, "Serper API key", keySupplier, transport);
    }

    @Override
    String requestJson(String query, int count) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("q", query);
        body.put("num", count);
        return body.toString();
    }

    @Override
    HttpRequest.Builder authorize(HttpRequest.Builder builder, String key) {
        return builder.header("X-API-KEY", key);
    }

    @Override
    List<BraveWebSearchClient.SearchResult> parse(String body, int count) {
        List<BraveWebSearchClient.SearchResult> out = new ArrayList<>();
        try {
            JsonNode results = MAPPER.readTree(body).path("organic");
            if (!results.isArray()) {
                return out;
            }
            for (JsonNode r : results) {
                String url = text(r, "link");
                if (!WebSearchHttp.isWebUrl(url)) {
                    continue;
                }
                out.add(
                        new BraveWebSearchClient.SearchResult(
                                WebSearchHttp.clip(text(r, "title"), WebSearchHttp.MAX_TITLE_CHARS),
                                url.strip(),
                                WebSearchHttp.clip(
                                        text(r, "snippet"), WebSearchHttp.MAX_SNIPPET_CHARS),
                                WebSearchHttp.shortDate(text(r, "date"))));
                if (out.size() >= count) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Serper response", e);
        }
        return out;
    }
}
