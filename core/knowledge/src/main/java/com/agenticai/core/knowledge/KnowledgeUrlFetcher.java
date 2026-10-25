package com.agenticai.core.knowledge;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Downloads a document or page for {@link KnowledgeUrlIngester} - the same posture {@code
 * WebPageFetcher} (agents-browser) takes for fetch_web_page:
 *
 * <ul>
 *   <li>http/https only, every hop (initial URL AND each redirect target) goes through {@link
 *       SsrfGuard#validate} so a public page can never bounce the fetch into link-local/internal
 *       infrastructure.
 *   <li>The socket itself is resolved+validated again by {@link GuardedJdkHttpClient} at connect
 *       time (the DNS-rebinding window a pre-check alone leaves open).
 *   <li>Redirects are followed manually ({@link #MAX_REDIRECTS} hops) - the JDK {@code NORMAL}
 *       policy would let the underlying client chase a Location without re-validating.
 *   <li>The body is read with a hard byte cap ({@link #MAX_DOWNLOAD_BYTES}, the same 5MB the HTTP
 *       upload path enforces) - a too-large response is an error, never a truncated silent
 *       half-document.
 * </ul>
 *
 * <p>Not a bean: constructed by {@link KnowledgeUrlIngester} (replaceable in tests via its
 * package-private seam).
 */
final class KnowledgeUrlFetcher {

    /** Same bound as {@code KnowledgeController#MAX_UPLOAD_BYTES} - one fetched document, one rule. */
    static final long MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024;

    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final SsrfGuard guard;
    private final HttpClient client;

    KnowledgeUrlFetcher() {
        this(SsrfGuard.defaultGuard(),
            GuardedJdkHttpClient.defaults(CONNECT_TIMEOUT, RESPONSE_TIMEOUT, false));
    }

    /** Test seam: a stubbed guard/client pair. */
    KnowledgeUrlFetcher(SsrfGuard guard, HttpClient client) {
        this.guard = guard;
        this.client = client;
    }

    record FetchedUrl(URI uri, String contentType, byte[] body) {}

    /**
     * GETs {@code rawUrl} (following redirects) and returns the final URI, the response's declared
     * Content-Type (sans parameters, lower-cased; empty when absent) and the body bytes.
     *
     * @throws SsrfBlockedException on a private/blocked destination (message is caller-safe)
     * @throws UrlFetchException on every other failure (bad URL, too many redirects, non-2xx,
     *     oversized/empty body, IO failure) - the message is caller-safe, the URL is included
     */
    FetchedUrl fetch(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UrlFetchException("url is required");
        }
        SsrfGuard.Target target = guard.validate(rawUrl.strip());
        URI current = target.uri();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = send(current);
            int status = response.statusCode();
            if (isRedirect(status)) {
                URI next = redirectTarget(current, response);
                // Re-validate the hop: the redirect target is a fresh URL decision.
                current = guard.validate(next.toString()).uri();
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new UrlFetchException("The URL returned HTTP " + status + " (" + current + ")");
            }
            String contentType = response.headers()
                .firstValue("Content-Type")
                .map(ct -> ct.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .orElse("");
            byte[] body;
            try (InputStream in = response.body()) {
                body = readBounded(in, MAX_DOWNLOAD_BYTES);
            } catch (IOException e) {
                throw new UrlFetchException("Could not download " + current + ": " + e.getMessage());
            }
            if (body.length == 0) {
                throw new UrlFetchException("The URL returned an empty document (" + current + ")");
            }
            return new FetchedUrl(current, contentType, body);
        }
        throw new UrlFetchException(
            "Too many redirects (more than " + MAX_REDIRECTS + ") fetching " + current);
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(RESPONSE_TIMEOUT)
            .header("User-Agent", "VegaDuta-KnowledgeIngest/1.0")
            .header("Accept",
                "application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document, "
                    + "text/html, text/markdown, text/plain, application/json;q=0.8, */*;q=0.5")
            .GET()
            .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UrlFetchException("Interrupted while downloading " + uri);
        } catch (IOException | IllegalArgumentException e) {
            throw new UrlFetchException("Could not download " + uri + ": " + e.getMessage());
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI redirectTarget(URI from, HttpResponse<?> response) {
        String location = response.headers().firstValue("Location")
            .orElseThrow(() -> new UrlFetchException(
                "The URL redirected without a Location header (" + from + ")"));
        try {
            return from.resolve(location);
        } catch (IllegalArgumentException e) {
            throw new UrlFetchException("The URL redirected to an invalid location: " + location);
        }
    }

    /** Reads at most {@code cap} bytes; {@link UrlFetchException} when the stream exceeds it. */
    private static byte[] readBounded(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
            total += read;
            if (total > cap) {
                throw new UrlFetchException(
                    "The downloaded document exceeds the " + (cap / 1024 / 1024) + "MB limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * A caller-safe fetch failure - an {@link InvalidKnowledgeUploadException} so {@code
     * POST /api/knowledge/ingest-url} reports 400 (the shared handler already maps it), and the
     * agent tool's catch reports the message verbatim. {@link SsrfBlockedException} is wrapped into
     * this same shape by {@link KnowledgeUrlIngester}.
     */
    static final class UrlFetchException extends InvalidKnowledgeUploadException {
        UrlFetchException(String message) {
            super(message);
        }
    }
}
