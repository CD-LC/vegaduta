package com.agenticai.core.platform.ops;

import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only client for Langfuse's public REST API ({@code /api/public/**}), used by the
 * platform-admin trace console ({@link PlatformTraceController}). The browser never holds Langfuse
 * keys and never needs Langfuse's origin.
 *
 * <p><b>Config reuse.</b> Same posture as {@code LangfuseScoreClient} (core/chat): with no explicit
 * {@code app.langfuse.base-url} the base URL is derived from {@code
 * management.otlp.tracing.endpoint} (the OTLP exporter's target, minus Langfuse's documented {@code
 * /api/public/otel/v1/traces} suffix), and {@code app.langfuse.auth-header} defaults to the same
 * {@code LANGFUSE_OTEL_AUTH_HEADER} "Basic base64(pk:sk)" value the exporter already sends -
 * Langfuse's public API accepts that pair on every endpoint. No new required configuration.
 *
 * <p><b>Bounded.</b> Every request has a 5 s connect and response timeout, redirects are not
 * followed, and successful answers are cached in memory for 30 s per URL (at most {@link
 * #MAX_CACHE_ENTRIES} entries), so a busy console cannot hammer Langfuse.
 *
 * <p><b>Never leaks the credential.</b> The auth header is only ever placed on the outgoing
 * request; failure reasons are written here and name the failure class (HTTP status, timeout,
 * connection refused), never the header, response bodies or the upstream URL.
 */
@Component
public class LangfuseApiClient {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    static final int MAX_CACHE_ENTRIES = 200;

    /** Raw HTTP seam so tests never open a socket. */
    @FunctionalInterface
    interface Transport {
        Response get(URI uri, String authHeader) throws IOException, InterruptedException;
    }

    record Response(int status, String body) {}

    /**
     * Langfuse is not configured, or did not give a usable answer. {@code reason} is safe to show
     * an operator.
     */
    public static final class LangfuseUnavailableException extends RuntimeException {
        private final int status;

        LangfuseUnavailableException(String reason, int status) {
            super(reason);
            this.status = status;
        }

        /** Upstream HTTP status, or 0 when there was no HTTP answer. */
        public int status() {
            return status;
        }
    }

    private record Cached(Instant at, JsonNode body) {}

    private static final ObjectMapper JSON = JsonMappers.plain();

    private final String baseUrl;
    private final String authHeader;
    private final Transport transport;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public LangfuseApiClient(
            @Value("${app.langfuse.base-url:}") String explicitBaseUrl,
            @Value("${management.otlp.tracing.endpoint:}") String otlpTracesEndpoint,
            @Value("${app.langfuse.auth-header:${LANGFUSE_OTEL_AUTH_HEADER:}}") String authHeader) {
        this(
                LangfuseEndpoints.baseUrl(explicitBaseUrl, otlpTracesEndpoint),
                authHeader,
                defaultTransport(),
                Clock.systemUTC());
    }

    LangfuseApiClient(String baseUrl, String authHeader, Transport transport, Clock clock) {
        this.baseUrl = baseUrl;
        this.authHeader = authHeader == null ? "" : authHeader.trim();
        this.transport = transport;
        this.clock = clock;
    }

    /** Base URL and credential are both present. Says nothing about reachability. */
    public boolean isConfigured() {
        return baseUrl != null && !authHeader.isEmpty();
    }

    /** Why {@link #isConfigured()} is false, or null when it is true. */
    public String notConfiguredReason() {
        if (baseUrl == null) {
            return "Langfuse is not configured for this environment (no usable Langfuse URL).";
        }
        if (authHeader.isEmpty()) {
            return "Langfuse is not configured for this environment (no Langfuse API credential).";
        }
        return null;
    }

    /** The derived base URL, for the internal health probe only (never sent to a browser). */
    String baseUrl() {
        return baseUrl;
    }

    /**
     * GET {@code path} with the given query parameters (null/blank values are dropped), parsed as
     * JSON, from cache when younger than 30 s.
     *
     * @throws LangfuseUnavailableException when not configured, unreachable, slow, or answering
     *     non-2xx / non-JSON
     */
    public JsonNode get(String path, Map<String, String> query) {
        if (!isConfigured()) {
            throw new LangfuseUnavailableException(notConfiguredReason(), 0);
        }
        String url = baseUrl + path + queryString(query);
        Instant now = clock.instant();
        Cached hit = cache.get(url);
        if (hit != null && now.isBefore(hit.at().plus(CACHE_TTL))) {
            return hit.body();
        }
        Response response;
        try {
            response = transport.get(URI.create(url), authHeader);
        } catch (HttpTimeoutException e) {
            throw new LangfuseUnavailableException(
                    "Langfuse did not answer within " + TIMEOUT.toSeconds() + " s.", 0);
        } catch (ConnectException e) {
            throw new LangfuseUnavailableException(
                    "Langfuse could not be reached (connection refused).", 0);
        } catch (UnknownHostException e) {
            throw new LangfuseUnavailableException(
                    "Langfuse could not be reached (host name not resolved).", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LangfuseUnavailableException("The Langfuse request was interrupted.", 0);
        } catch (IOException | RuntimeException e) {
            throw new LangfuseUnavailableException(
                    "Langfuse could not be reached (" + e.getClass().getSimpleName() + ").", 0);
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new LangfuseUnavailableException(
                    describeStatus(response.status()), response.status());
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body() == null ? "" : response.body());
        } catch (JacksonException e) {
            throw new LangfuseUnavailableException(
                    "Langfuse answered with something that is not JSON.", response.status());
        }
        if (body == null || body.isMissingNode()) {
            throw new LangfuseUnavailableException(
                    "Langfuse answered with an empty body.", response.status());
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(url, new Cached(now, body));
        return body;
    }

    static String describeStatus(int status) {
        if (status == 401 || status == 403) {
            return "Langfuse rejected the configured API credential (HTTP " + status + ").";
        }
        if (status == 404) {
            return "Langfuse answered HTTP 404 (not found, or this API is not available on the"
                    + " deployed Langfuse version).";
        }
        if (status == 429) {
            return "Langfuse is rate limiting this server (HTTP 429); try again shortly.";
        }
        return "Langfuse answered HTTP " + status + ".";
    }

    static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(query).entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            out.append(out.length() == 0 ? '?' : '&')
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Encodes one path segment (a trace id) so it can never add path components. */
    static String pathSegment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Transport defaultTransport() {
        HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return (uri, auth) -> {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("unsupported scheme");
            }
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }
}
