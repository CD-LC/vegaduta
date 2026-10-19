package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production {@link McpOAuthHttp}. SSRF posture for a flow whose URLs are
 * ultimately derived from a tenant-typed MCP server URL AND from documents that
 * server controls (protected-resource metadata, then authorization-server
 * metadata, then endpoints inside those):
 * <ul>
 *   <li>EVERY request URL goes through {@link McpRemoteUrlGuard#checkResolved}
 *       (literal-IP, denylist and DNS-resolution checks), so a malicious
 *       metadata document cannot point this platform at postgres, cloud
 *       metadata, or loopback.</li>
 *   <li>https only - discovery and token traffic carries credentials.</li>
 *   <li>Redirects are NEVER followed ({@link HttpClient.Redirect#NEVER}); a 3xx
 *       is returned as-is and treated as a failure by callers. Following a
 *       redirect would bypass the guard for the redirect target.</li>
 *   <li>Response bodies are capped ({@code app.mcp.oauth.max-response-bytes}).</li>
 * </ul>
 * Same residual DNS-rebinding window as {@link McpRemoteUrlGuard}'s own
 * documentation describes (the JDK client offers no address-pinning hook).
 */
@Component
public class JdkMcpOAuthHttp implements McpOAuthHttp {

    private final McpRemoteUrlGuard guard;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int maxResponseBytes;

    @Autowired
    public JdkMcpOAuthHttp(
        McpRemoteUrlGuard guard,
        @Value("${app.mcp.oauth.timeout-seconds:10}") int timeoutSeconds,
        @Value("${app.mcp.oauth.max-response-bytes:262144}") int maxResponseBytes
    ) {
        this.guard = guard;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxResponseBytes = Math.max(1024, maxResponseBytes);
        this.httpClient = newClient(this.timeout);
    }

    /** Test seam: inject the client (e.g. to prove redirects are not followed). */
    JdkMcpOAuthHttp(McpRemoteUrlGuard guard, HttpClient httpClient, Duration timeout, int maxResponseBytes) {
        this.guard = guard;
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    HttpClient.Redirect clientRedirectPolicy() {
        return httpClient.followRedirects();
    }

    /**
     * The one place the client is configured; redirects are never followed (a hop
     * would bypass the guard). The transport is {@link GuardedJdkHttpClient}, so
     * the DNS answer {@link McpRemoteUrlGuard#checkResolved} approved is the
     * answer the socket uses - the DNS-rebinding window this class documented as
     * a residual is closed at connect time.
     */
    static HttpClient newClient(Duration timeout) {
        // defaults() resolves the process-wide guard lazily at each DNS lookup, so a
        // client built before SsrfGuardConfiguration installs the allow-list still
        // picks it up.
        return GuardedJdkHttpClient.defaults(timeout, timeout, false);
    }

    @Override
    public java.util.Optional<String> checkUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            return java.util.Optional.of("MCP OAuth only talks to https URLs.");
        }
        return guard.check(url);
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, String body) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            throw new McpOAuthBlockedException("MCP OAuth only talks to https URLs (refused: " + url + ").");
        }
        guard.checkResolved(url).ifPresent(reason -> {
            throw new McpOAuthBlockedException("MCP OAuth request blocked: " + reason);
        });
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
        } catch (IllegalArgumentException e) {
            throw new McpOAuthException("Not a valid URL: " + url, e);
        }
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("DELETE".equals(method)) {
            // Token revocation at GitHub and HubSpot is a DELETE (GitHub with a JSON body).
            builder.method("DELETE", body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String text = readBounded(response.body(), url, maxResponseBytes, timeout);
            Map<String, String> responseHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    responseHeaders.put(k.toLowerCase(Locale.ROOT), v.get(0));
                }
            });
            return new Response(response.statusCode(), responseHeaders, text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpOAuthException("Interrupted calling " + url, e);
        } catch (IOException e) {
            throw new McpOAuthException("Could not reach " + url + ": " + e.getMessage(), e);
        }
    }

    /** Runs the bounded body reads: a virtual thread each, never the JVM-wide common pool (ADR-0024). */
    private static final java.util.concurrent.ExecutorService BODY_READER =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("mcp-oauth-body-reader-");

    /**
     * Reads at most {@code max} bytes within {@code timeout}. The request timeout only covers
     * headers, so the body read is bounded here too: a never-ending stream (SSE) cannot pin the
     * thread. The stream is closed on every path, which aborts a stalled read.
     */
    static String readBounded(InputStream in, String url, int max, Duration timeout) {
        java.util.concurrent.CompletableFuture<byte[]> read = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return in.readNBytes(max + 1);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, BODY_READER);
        try {
            byte[] bytes = read.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (bytes.length > max) {
                throw new McpOAuthException("MCP OAuth response from " + url + " exceeded " + max + " bytes.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            throw new McpOAuthException("Timed out or failed reading the response from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpOAuthException("Interrupted reading " + url, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
