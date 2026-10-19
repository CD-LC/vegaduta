package com.agenticai.core.agents.tools.web;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SendStage of the {@link HttpRequestExecutor} pipeline: the one network call, over a {@link
 * SafeHttpClients} client (pinned DNS, no proxy, redirects off, response read capped). Owns the
 * pooled client and its lifecycle; the guard is supplied by the executor so a test-installed guard
 * rebuilds the client exactly as before.
 */
final class HttpSendStage {

    private static final Logger log = LoggerFactory.getLogger(HttpSendStage.class);

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<SsrfGuard> guard;
    private final Object clientLock = new Object();
    private SsrfGuard clientGuard;
    private CloseableHttpClient httpClient;

    HttpSendStage(Supplier<SsrfGuard> guard) {
        this.guard = guard;
    }

    HttpRequestExecutor.RawResponse send(
            URI uri, String method, Map<String, String> headers, String body, Duration timeout) {
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        request.setConfig(
                RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(timeout))
                        .setConnectionRequestTimeout(Timeout.of(timeout))
                        .setRedirectsEnabled(false)
                        .build());
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(header.getKey()) && body == null) {
                // Dropped alongside the body on a 303-style redirect.
                continue;
            }
            request.addHeader(header.getKey(), header.getValue());
        }
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), null));
        }
        try (ClassicHttpResponse response = client().executeOpen(null, request, null)) {
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                responseHeaders
                        .computeIfAbsent(
                                h.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(h.getValue());
            }
            HttpEntity entity = response.getEntity();
            String contentType = HttpResponseReader.firstHeader(responseHeaders, "content-type");
            byte[] bytes = new byte[0];
            boolean truncated = false;
            if (entity != null
                    && !HttpResponseReader.isBinary(contentType)
                    && !"HEAD".equals(method)) {
                InputStream in = entity.getContent();
                bytes = readCapped(in);
                truncated = bytes.length >= MAX_RESPONSE_BYTES;
                if (truncated) {
                    // Abort BEFORE closing: closing would drain the rest of an
                    // oversized (possibly endless) stream to reuse the connection.
                    request.cancel();
                }
                closeQuietly(in);
            } else if (entity != null) {
                // Binary / HEAD: never decoded, never drained.
                request.cancel();
            }
            return new HttpRequestExecutor.RawResponse(
                    response.getCode(), responseHeaders, bytes, truncated);
        } catch (SsrfGuard.BlockedAddressException e) {
            // The connect-time DNS check refused the address (e.g. rebinding).
            throw new WebPageFetchException(
                    "the URL points to a private or internal network address");
        } catch (InterruptedIOException e) {
            // Socket/connect/pool timeouts are all InterruptedIOExceptions in
            // HttpClient 5; only a genuinely interrupted thread is reported as such.
            if (Thread.currentThread().isInterrupted()) {
                throw new WebPageFetchException("the request was interrupted");
            }
            throw new WebPageFetchException(
                    "the server did not respond within " + timeout.toSeconds() + " seconds");
        } catch (IOException e) {
            // Connection refused / DNS / TLS - "unreachable" to the person on
            // the other end; the detail goes to the log (masked URL - SEC-049),
            // not the model (an exception message can carry internal hostnames).
            log.debug(
                    "http_request could not reach {}: {}",
                    HttpLoggableUrl.of(uri, Set.of()),
                    e.getClass().getSimpleName());
            throw new WebPageFetchException("the server could not be reached");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new WebPageFetchException("the URL or one of the headers is not valid");
        }
    }

    private CloseableHttpClient client() {
        SsrfGuard current = guard.get();
        synchronized (clientLock) {
            if (httpClient == null || clientGuard != current) {
                // A previous client (built before the operator's guard was
                // installed) is left to in-flight requests rather than closed
                // under them; this happens at most once per guard change.
                httpClient =
                        SafeHttpClients.create(
                                current,
                                CONNECT_TIMEOUT,
                                Duration.ofSeconds(HttpRequestExecutor.MAX_TIMEOUT_SECONDS),
                                false);
                clientGuard = current;
            }
            return httpClient;
        }
    }

    void close() {
        synchronized (clientLock) {
            if (httpClient != null) {
                try {
                    httpClient.close();
                } catch (IOException ignored) {
                    // Shutting down either way.
                }
                httpClient = null;
            }
        }
    }

    /** Reads at most {@link #MAX_RESPONSE_BYTES}; does NOT close {@code in} (see {@link #send}). */
    private static byte[] readCapped(InputStream in) {
        try {
            byte[] buffer = new byte[8_192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() >= MAX_RESPONSE_BYTES) {
                    break;
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new WebPageFetchException("the server stopped responding mid-download");
        }
    }

    static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // Best-effort - the connection is being released or abandoned either way.
        }
    }
}
