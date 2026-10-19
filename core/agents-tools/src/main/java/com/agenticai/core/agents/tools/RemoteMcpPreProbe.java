package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ONE cheap HTTP request against a remote MCP registration's URL, sent with the registration's
 * env-headers, BEFORE the SDK handshake (MCP-DOCTOR, 2026-07-25) - because the SDK wraps every
 * remote failure in the same opaque "Client failed to initialize by explicit API call" Reactor
 * timeout, hiding whether the real cause was 401 (missing credentials), 404 (wrong URL), a
 * transport-type mismatch (SSE server registered as STREAMABLE_HTTP), or plain unreachability.
 *
 * <p>Strictly best-effort: any failure here is captured as data for the diagnosis mapper ({@link
 * McpConnectionDiagnoser}), and the real connect attempt always proceeds exactly as it would have
 * without the probe.
 *
 * <p>Shape of the request matches what the server type expects so the status code is meaningful:
 * STREAMABLE_HTTP servers speak POSTed JSON-RPC (a GET typically 405s even on a healthy server),
 * SSE servers speak GET with {@code Accept: text/event-stream}.
 *
 * <p><b>Address pinning (security review):</b> the request goes through a {@link SafeHttpClients}
 * client, not a raw {@code java.net.http.HttpClient}. The caller's {@code McpRemoteUrlGuard} check
 * resolved the host once; a second, independent resolution here would reopen a DNS-rebinding window
 * between "checked safe" and "socket opened". The guarded client resolves through {@link
 * SsrfGuard#dnsResolver()}, so every address is validated and the socket goes only to a validated
 * address. Redirects stay off, as before, and the registration's headers never follow a 3xx.
 */
final class RemoteMcpPreProbe {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

    /** RFC 7230 header-name token - same tolerance as the real connect path's header filter. */
    private static final Pattern HEADER_TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

    /**
     * Minimal, spec-shaped initialize request - enough for the server to answer with SOMETHING
     * classifiable; never awaited beyond one response.
     */
    private static final String PROBE_INITIALIZE_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":\"vegaduta-probe\",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"vegaduta-connection-doctor\",\"version\":\"1.0\"}}}";

    enum FailureKind {
        NONE,
        UNKNOWN_HOST,
        CONNECT_REFUSED,
        TIMEOUT,
        TLS,
        OTHER
    }

    /**
     * @param performed false when the probe never ran (bad URL shape etc.) - treat as "no probe
     *     data"
     * @param statusCode HTTP status, null when no response arrived
     * @param contentType lowercased Content-Type header, or null
     * @param failureKind why no response arrived, NONE when one did
     * @param detail short human-readable transport failure text, or null
     */
    record ProbeResult(
            boolean performed,
            Integer statusCode,
            String contentType,
            FailureKind failureKind,
            String detail) {
        static ProbeResult skipped() {
            return new ProbeResult(false, null, null, FailureKind.NONE, null);
        }
    }

    private RemoteMcpPreProbe() {}

    /** Probes through the process-wide {@link SsrfGuard#defaultGuard()}. */
    static ProbeResult probe(String url, Map<String, String> headers, McpServerType type) {
        return probe(url, headers, type, SsrfGuard.defaultGuard());
    }

    /**
     * Probes through a client whose connections are pinned to the addresses {@code guard}
     * validates. Package-private seam so tests can supply a guard with a stub resolver.
     */
    static ProbeResult probe(
            String url, Map<String, String> headers, McpServerType type, SsrfGuard guard) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return ProbeResult.skipped();
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            // The guarded client needs an absolute http(s) URL; anything else is "no probe data"
            // exactly as a malformed URL was before.
            return ProbeResult.skipped();
        }
        HttpUriRequestBase request = buildRequest(uri, headers, type);
        try (CloseableHttpClient client =
                SafeHttpClients.create(guard, PROBE_TIMEOUT, PROBE_TIMEOUT, false)) {
            // executeOpen, not a response handler: a handler would drain the body on return, and
            // an SSE endpoint streams forever. Only status + Content-Type matter, so the exchange
            // is cancelled (connection dropped) as soon as the headers are in.
            ClassicHttpResponse response = client.executeOpen(null, request, null);
            try {
                int status = response.getCode();
                Header header = response.getFirstHeader("Content-Type");
                String contentType =
                        header == null || header.getValue() == null
                                ? null
                                : header.getValue().toLowerCase(Locale.ROOT);
                return new ProbeResult(true, status, contentType, FailureKind.NONE, null);
            } finally {
                request.cancel();
                closeQuietly(response);
            }
        } catch (InterruptedIOException e) {
            // Apache's ConnectTimeoutException and java.net.SocketTimeoutException both extend
            // InterruptedIOException: connect or first-byte timeout.
            return new ProbeResult(
                    true,
                    null,
                    null,
                    FailureKind.TIMEOUT,
                    "No HTTP response within " + PROBE_TIMEOUT.toSeconds() + "s");
        } catch (SsrfGuard.BlockedAddressException e) {
            // The guard's resolver refused at connect time (private/internal address, or a DNS
            // answer that changed since the caller's check). Reported as unreachable - the
            // platform must not connect there, and the diagnoser's hint says exactly that.
            return new ProbeResult(
                    true,
                    null,
                    null,
                    FailureKind.CONNECT_REFUSED,
                    "Connection refused by the platform's outbound-address policy");
        } catch (ConnectException e) {
            return new ProbeResult(
                    true,
                    null,
                    null,
                    FailureKind.CONNECT_REFUSED,
                    "Connection refused/unreachable");
        } catch (UnknownHostException e) {
            return new ProbeResult(
                    true, null, null, FailureKind.UNKNOWN_HOST, "Hostname does not resolve");
        } catch (javax.net.ssl.SSLException e) {
            return new ProbeResult(
                    true, null, null, FailureKind.TLS, "TLS handshake failed: " + e.getMessage());
        } catch (Exception e) {
            String kind =
                    e.getCause() instanceof UnknownHostException
                            ? "Hostname does not resolve"
                            : e.getMessage();
            return new ProbeResult(
                    true,
                    null,
                    null,
                    e.getCause() instanceof UnknownHostException
                            ? FailureKind.UNKNOWN_HOST
                            : FailureKind.OTHER,
                    kind == null ? e.getClass().getSimpleName() : kind);
        }
    }

    private static HttpUriRequestBase buildRequest(
            URI uri, Map<String, String> headers, McpServerType type) {
        HttpUriRequestBase request;
        if (type == McpServerType.SSE) {
            request = new HttpUriRequestBase("GET", uri);
            request.addHeader("Accept", "text/event-stream");
        } else {
            request = new HttpUriRequestBase("POST", uri);
            // The entity carries Content-Type: application/json.
            request.setEntity(
                    new StringEntity(PROBE_INITIALIZE_BODY, ContentType.APPLICATION_JSON));
            request.addHeader("Accept", "application/json, text/event-stream");
        }
        if (headers != null) {
            headers.forEach(
                    (k, v) -> {
                        // Non-token header names (and values with line breaks) are already
                        // skipped-with-warning on the real connect path - same tolerance here.
                        if (k != null
                                && v != null
                                && HEADER_TOKEN.matcher(k).matches()
                                && v.indexOf('\r') < 0
                                && v.indexOf('\n') < 0) {
                            request.addHeader(k, v);
                        }
                    });
        }
        return request;
    }

    private static void closeQuietly(ClassicHttpResponse response) {
        try {
            response.close();
        } catch (IOException | RuntimeException ignored) {
            // best effort - the connection is being abandoned either way
        }
    }
}
