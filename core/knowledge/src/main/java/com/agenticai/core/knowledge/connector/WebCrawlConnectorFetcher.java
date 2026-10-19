package com.agenticai.core.knowledge.connector;

import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Web-crawl connector - fetches an explicit list of public https URLs and
 * ships each page's raw HTML as {@code .html} so the sidecar's extractor
 * (the same one uploaded .html files use) produces the text. Deliberately
 * NOT a recursive crawler: a fixed URL list keeps a sync bounded.
 *
 * <p><b>SSRF (security review SEC-008).</b> Every URL, and every redirect
 * hop, passes {@link ConnectorHttp#requirePublicHttps} (https only + the
 * shared {@link SsrfGuard}). The transport is {@link
 * ConnectorHttp#guardedRequestFactory}: it follows no redirects itself (this
 * class follows at most {@value ConnectorHttp#MAX_REDIRECTS} by hand) and
 * connects only to addresses the guard validated in the same DNS lookup, so
 * neither a 302 to {@code http://core:8080/actuator} nor a DNS answer that
 * changes between check and connect reaches an internal host. A refused
 * redirect fails the sync with the guard's reason, exactly like a refused
 * initial URL.
 *
 * <p><b>Config keys:</b> {@code urls} - comma-separated https URLs (max
 * {@value #MAX_URLS} per connector).
 */
@Component
public class WebCrawlConnectorFetcher implements ConnectorFetcher {

    static final int MAX_URLS = 50;

    private final RestClient restClient;
    private final SsrfGuard guard;
    private final boolean httpsOnly;

    @Autowired
    public WebCrawlConnectorFetcher(RestClient.Builder builder, SsrfGuard guard) {
        this(builder, guard, true);
    }

    /** Test seam: {@code httpsOnly=false} lets a unit test drive a plain-http local server through the same guard. */
    WebCrawlConnectorFetcher(RestClient.Builder builder, SsrfGuard guard, boolean httpsOnly) {
        this.guard = guard;
        this.httpsOnly = httpsOnly;
        this.restClient = builder.clone().requestFactory(ConnectorHttp.guardedRequestFactory(guard)).build();
    }

    @Override
    public ConnectorType getType() {
        return ConnectorType.WEB_CRAWL;
    }

    @Override
    public List<ConnectorDocument> fetch(Map<String, String> config, String cursor) {
        String urls = config.get("urls");
        if (urls == null || urls.isBlank()) {
            throw new ConnectorFetchException("Web-crawl connector missing config: urls");
        }
        List<ConnectorDocument> docs = new ArrayList<>();
        int count = 0;
        for (String raw : urls.split(",")) {
            if (count++ >= MAX_URLS) {
                break;
            }
            URI uri = ConnectorHttp.requirePublicHttps(raw, "urls", guard, httpsOnly);
            try {
                byte[] html = fetchFollowingRedirects(uri);
                if (html == null || html.length == 0) {
                    continue;
                }
                String title = uri.getHost() + uri.getPath();
                docs.add(new ConnectorDocument(
                    uri.toString(), title, uri.toString(),
                    NotionConnectorFetcher.sanitizeFilename(title) + ".html", html));
            } catch (ConnectorFetchException e) {
                throw e;
            } catch (Exception e) {
                // One dead URL must not fail the whole connector - record and
                // continue, the sync reports the count either way.
                docs.add(new ConnectorDocument(
                    uri.toString(), uri.toString(), uri.toString(),
                    "fetch-failed.md",
                    ("# Fetch failed\n\n" + uri + "\n\n" + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
            }
        }
        return docs;
    }

    /** One GET per hop through the guarded client; each Location is re-validated before it is requested. */
    private byte[] fetchFollowingRedirects(URI start) {
        URI current = start;
        for (int hop = 0; ; hop++) {
            Hop result = restClient.get()
                .uri(current)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 300 && status < 400) {
                        return new Hop(null, response.getHeaders().getFirst(HttpHeaders.LOCATION), status);
                    }
                    if (status < 200 || status >= 300) {
                        return new Hop(null, null, status);
                    }
                    return new Hop(ConnectorHttp.readBounded(response.getBody()), null, status);
                });
            if (result == null) {
                return null;
            }
            if (result.body() != null) {
                return result.body();
            }
            if (result.status() < 300 || result.status() >= 400) {
                throw new IllegalStateException("HTTP " + result.status());
            }
            if (hop >= ConnectorHttp.MAX_REDIRECTS) {
                throw new ConnectorFetchException("urls redirected more than " + ConnectorHttp.MAX_REDIRECTS + " times");
            }
            current = ConnectorHttp.requirePublicRedirect(current, result.location(), "urls", guard, httpsOnly);
        }
    }

    private record Hop(byte[] body, String location, int status) {
    }
}
