package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link AbstractHttpSearchProvider} whose only credential is a platform API key, resolved at
 * CALL time (rotating or removing it takes effect on the next call without a redeploy - same rule
 * as the Brave path). Used by the Tavily, Exa and Serper adapters, which all POST a JSON body to a
 * fixed HTTPS endpoint.
 *
 * <p>No SSRF surface: the host is a constant of the subclass; only the query text (carried inside a
 * JSON body, never in the URL) is caller-controlled.
 */
abstract class AbstractKeyedSearchProvider extends AbstractHttpSearchProvider {

    static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Logger log = LoggerFactory.getLogger(AbstractKeyedSearchProvider.class);

    private final Supplier<String> keySupplier;
    private final String endpoint;
    private final String credentialName;

    protected AbstractKeyedSearchProvider(
            String id,
            String label,
            String endpoint,
            String credentialName,
            Supplier<String> keySupplier,
            WebSearchHttp.Transport transport) {
        super(id, label, transport);
        this.endpoint = endpoint;
        this.credentialName = credentialName;
        this.keySupplier = keySupplier;
    }

    /** Non-throwing: a credential store that cannot decrypt answers "not configured", not a 500. */
    private String key() {
        try {
            String key = keySupplier == null ? null : keySupplier.get();
            return key == null || key.isBlank() ? null : key.strip();
        } catch (RuntimeException e) {
            log.warn("{} credential could not be read", label(), e);
            return null;
        }
    }

    @Override
    public boolean configured() {
        return key() != null;
    }

    @Override
    public String notConfiguredMessage() {
        return "web search is not configured on this platform yet. A platform admin needs to add a "
                + credentialName
                + " under Tool Credentials.";
    }

    /**
     * The JSON body for this provider (built with Jackson, so a query can never break out of its
     * string).
     */
    abstract String requestJson(String query, int count);

    /** Sets the provider's auth header on the request being built. */
    abstract HttpRequest.Builder authorize(HttpRequest.Builder builder, String key);

    @Override
    final HttpRequest buildRequest(String query, int count) {
        String key = key();
        if (key == null) {
            throw new WebSearchException(notConfiguredMessage());
        }
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(WebSearchHttp.TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json");
        return authorize(builder, key)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(query, count)))
                .build();
    }

    /** Tolerant text read: missing/null/non-text -> empty. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText("") : "";
    }
}
