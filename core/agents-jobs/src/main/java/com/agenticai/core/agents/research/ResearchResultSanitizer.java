package com.agenticai.core.agents.research;

import com.agenticai.core.agents.tools.web.DeepResearchSession;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cleans a research run's structured result before it is shown. The model writes the {@code
 * sources} array itself, so a prompt-injected page (or a hallucination) can put anything in a
 * source's {@code url}: a {@code javascript:} link the report page would render as clickable, or a
 * site that was never read.
 *
 * <p>Always applied: a source whose url is not an absolute http/https link with a host is dropped.
 * Applied when the URLs the session really fetched are known: a source whose url was not among them
 * is dropped too (compared with {@link DeepResearchSession#normalizeUrl}, so tracking parameters
 * and {@code www.} do not matter). Pure and null-safe; mutates the node it is given and returns it.
 */
public final class ResearchResultSanitizer {

    private ResearchResultSanitizer() {}

    /** True for an absolute {@code http}/{@code https} URL with a host. */
    public static boolean isWebUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param fetchedUrls the URLs the research session actually read (e.g. every {@code byId}
     *     source), or {@code null} when that set is not known - then only the scheme rule applies
     */
    public static JsonNode sanitize(JsonNode result, Set<String> fetchedUrls) {
        if (result == null
                || !result.isObject()
                || !(result.get("sources") instanceof ArrayNode sources)) {
            return result;
        }
        Set<String> fetchedKeys = null;
        if (fetchedUrls != null) {
            fetchedKeys = new HashSet<>();
            for (String url : fetchedUrls) {
                fetchedKeys.add(DeepResearchSession.normalizeUrl(url));
            }
        }
        ArrayNode kept = ((ObjectNode) result).arrayNode();
        for (JsonNode source : sources) {
            String url = source.path("url").asString(null);
            if (!isWebUrl(url)) {
                continue;
            }
            if (fetchedKeys != null
                    && !fetchedKeys.contains(DeepResearchSession.normalizeUrl(url))) {
                continue;
            }
            kept.add(source);
        }
        ((ObjectNode) result).set("sources", kept);
        return result;
    }
}
