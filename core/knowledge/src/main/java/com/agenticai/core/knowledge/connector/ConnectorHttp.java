package com.agenticai.core.knowledge.connector;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Shared guards for connector outbound calls. Connectors fetch from
 * tenant-supplied URLs - an unrestricted fetcher is a server-side request
 * forgery primitive (a tenant could point a "connector" at the metadata
 * service or an internal host).
 *
 * <p>Two layers (security review SEC-008):
 * <ul>
 *   <li>{@link #requirePublicHttps} - https only, then the shared
 *       {@link SsrfGuard} (every resolved address, the full reserved-range
 *       list, internal host names). Gives the tenant a clear refusal.</li>
 *   <li>{@link #guardedRequestFactory} - the HTTP transport for any
 *       tenant-supplied host. Built on {@link SafeHttpClients}, so the socket
 *       only ever goes to an address the guard validated in the same lookup
 *       (no DNS-rebinding window) and no system proxy is used.</li>
 * </ul>
 *
 * <p><b>Redirects.</b> The guarded transport never follows redirects on its
 * own. {@link WebCrawlConnectorFetcher} follows them by hand (https only,
 * re-validated per hop, capped); {@link ConfluenceConnectorFetcher} sends a
 * credential and does not follow them at all. (Before SEC-008 this javadoc
 * claimed redirects were not followed while the web-crawl client, built on
 * Boot's default {@code RestClient}, followed them - a public URL that 302'd
 * to {@code http://core:8080/actuator} was fetched and indexed.) Fixed-host
 * provider APIs (Notion, Google Drive, SharePoint) keep Boot's client.
 */
final class ConnectorHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    static final int MAX_DOCUMENTS_PER_SYNC = 500;
    static final int MAX_REDIRECTS = 5;

    private ConnectorHttp() {}

    /** Throws {@link ConnectorFetchException} unless the URL is a public https address. */
    static URI requirePublicHttps(String raw, String field) {
        return requirePublicHttps(raw, field, SsrfGuard.defaultGuard(), true);
    }

    /**
     * Throws {@link ConnectorFetchException} unless the URL passes {@code guard}
     * (and is https when {@code httpsOnly}). The message never names the
     * address the host resolved to.
     */
    static URI requirePublicHttps(String raw, String field, SsrfGuard guard, boolean httpsOnly) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ConnectorFetchException(field + " is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly ? !"https".equals(scheme) : !("https".equals(scheme) || "http".equals(scheme))) {
            throw new ConnectorFetchException(field + " must be an https:// URL");
        }
        if (uri.getHost() == null) {
            throw new ConnectorFetchException(field + " must be an https:// URL");
        }
        try {
            guard.validate(uri);
        } catch (SsrfBlockedException e) {
            throw new ConnectorFetchException(field + " is not allowed: " + e.getMessage()
                + " - connectors only fetch public hosts");
        }
        return uri;
    }

    /** Resolves a redirect Location against {@code from} and applies {@link #requirePublicHttps} to the result. */
    static URI requirePublicRedirect(URI from, String location, String field, SsrfGuard guard, boolean httpsOnly) {
        if (location == null || location.isBlank()) {
            throw new ConnectorFetchException(field + " redirected without a Location header");
        }
        URI next;
        try {
            next = from.resolve(URI.create(location.strip()));
        } catch (IllegalArgumentException e) {
            throw new ConnectorFetchException(field + " redirected to an invalid address");
        }
        return requirePublicHttps(next.toString(), field + " (redirect)", guard, httpsOnly);
    }

    /**
     * An address-pinned, proxy-free, redirect-free request factory for
     * tenant-supplied hosts. The caller owns it for the life of its bean.
     */
    static ClientHttpRequestFactory guardedRequestFactory(SsrfGuard guard) {
        return SafeHttpClients.requestFactory(guard, TIMEOUT, TIMEOUT, false);
    }

    /** Reads a response body with a hard byte cap - a connector page must not OOM the JVM. */
    static byte[] readBounded(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESPONSE_BYTES) {
                throw new ConnectorFetchException("Response exceeded the " + MAX_RESPONSE_BYTES + "-byte cap");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
