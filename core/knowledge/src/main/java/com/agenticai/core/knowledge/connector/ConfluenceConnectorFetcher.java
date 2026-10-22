package com.agenticai.core.knowledge.connector;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confluence connector - syncs a space's pages via the REST content API. {@code body.storage}
 * (Confluence's storage-format HTML) ships as {@code .html} so the shared ingest path routes it
 * through the sidecar's HTML extractor - same pipeline an uploaded .html file takes, no second
 * text-extraction implementation to keep honest.
 *
 * <p><b>Config keys:</b> {@code baseUrl} (the tenant's site, e.g. {@code
 * https://acme.atlassian.net/wiki}), {@code email} + {@code apiToken} (basic auth - Atlassian's
 * documented PAT scheme), {@code spaceKey}.
 */
@Component
public class ConfluenceConnectorFetcher implements ConnectorFetcher {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final SsrfGuard guard;

    /**
     * The base URL is tenant-supplied and the request carries the tenant's Atlassian credential, so
     * the transport is the guarded, address-pinned, redirect-free client (security review SEC-008):
     * a 3xx is a failure, not a hop that would carry the Authorization header somewhere else.
     */
    public ConfluenceConnectorFetcher(RestClient.Builder builder, SsrfGuard guard) {
        this.guard = guard;
        this.restClient =
                builder.clone().requestFactory(ConnectorHttp.guardedRequestFactory(guard)).build();
    }

    @Override
    public ConnectorType getType() {
        return ConnectorType.CONFLUENCE;
    }

    @Override
    public List<ConnectorDocument> fetch(Map<String, String> config, String cursor) {
        String baseUrl =
                ConnectorHttp.requirePublicHttps(require(config, "baseUrl"), "baseUrl", guard, true)
                        .toString()
                        .replaceAll("/+$", "");
        String email = require(config, "email");
        String apiToken = require(config, "apiToken");
        String spaceKey = require(config, "spaceKey");
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));

        List<ConnectorDocument> docs = new ArrayList<>();
        int start = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        // One page of results per sync run keeps a giant space from turning
        // one sync into an unbounded job; the cursor resumes next run.
        String url =
                baseUrl
                        + "/rest/api/content?spaceKey="
                        + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8)
                        + "&type=page&status=current&expand=body.storage,version&start="
                        + start
                        + "&limit="
                        + PAGE_SIZE;
        try {
            JsonNode resp =
                    MAPPER.readTree(
                            restClient
                                    .get()
                                    .uri(url)
                                    .header("Authorization", auth)
                                    .retrieve()
                                    .body(String.class));
            for (JsonNode page : resp.path("results")) {
                String id = page.path("id").asString();
                String title = page.path("title").asString("untitled");
                String html = page.path("body").path("storage").path("value").asString("");
                if (id.isBlank() || html.isBlank()) {
                    continue;
                }
                String link = baseUrl + "/pages/viewpage.action?pageId=" + id;
                docs.add(
                        new ConnectorDocument(
                                id,
                                title,
                                link,
                                NotionConnectorFetcher.sanitizeFilename(title) + ".html",
                                html.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (ConnectorFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorFetchException("Confluence fetch failed: " + e.getMessage(), e);
        }
        return docs;
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new ConnectorFetchException("Confluence connector missing config: " + key);
        }
        return v;
    }
}
