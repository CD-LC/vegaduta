package com.agenticai.core.agents.hooks;

import com.agenticai.core.eventbus.webhook.WebhookSignature;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * The {@code webhook} hook action's HTTP client: POSTs one JSON event and reads back a verdict.
 *
 * <p>Everything about it is deliberately narrow:
 *
 * <ul>
 *   <li><b>Allow-listed https only.</b> The URL's host must match an operator or tenant allow-list
 *       entry (exact, or {@code *.suffix} for subdomains only); an empty list allows nothing. Plain
 *       http, credentials in the URL and redirects (never followed) are refused.
 *   <li><b>SSRF-guarded.</b> After the allow-list, the URL goes through the platform's existing
 *       remote-URL guard ({@code McpRemoteUrlGuard#checkResolved}:
 *       private/loopback/link-local/metadata ranges, platform-internal hostnames, and a
 *       DNS-resolution check against rebinding). The connection itself is made by {@code
 *       SafeHttpClients}, whose DNS resolver re-validates every address and connects only to a
 *       validated one, so a hostname that re-resolves to a private address between the check and
 *       the connect cannot reach a socket; redirects being off removes the other variant.
 *   <li><b>Hard time bound.</b> The caller's timeout covers connect, request, and reading the
 *       reply; a receiver that trickles bytes cannot hold the thread past it.
 *   <li><b>Reply can only say allow or deny.</b> The reply must be a JSON object whose {@code
 *       decision} is {@code allow} or {@code deny}, with an optional {@code reason} string. Nothing
 *       else in the reply is read, and it is capped at {@value #MAX_REPLY_BYTES} bytes - a webhook
 *       can never inject a tool name, arguments, a rewritten result or any other instruction.
 *   <li><b>Signed when configured.</b> With {@code app.agent-hooks.webhook.signing-secret} set, the
 *       event carries the same {@code X-VegaDuta-Signature} header outbound tenant webhooks use.
 * </ul>
 */
public class HookWebhookClient {

    static final int MAX_REPLY_BYTES = 4_096;
    static final int MAX_REASON_CHARS = 300;
    static final String EVENT_HEADER = "X-VegaDuta-Hook-Event";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** The verdict a webhook gave. */
    public record Reply(boolean allow, String reason) {}

    /**
     * Why a webhook call produced no verdict. {@link #getMessage()} is operator-facing, never
     * model-facing.
     */
    public static final class WebhookException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public WebhookException(String message) {
            super(message);
        }

        public WebhookException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One HTTP exchange. A seam so tests can stand in for the network. */
    @FunctionalInterface
    interface Transport {
        Response post(URI uri, String body, Map<String, String> headers, Duration timeout)
                throws Exception;
    }

    record Response(int status, String body) {}

    private static final java.util.concurrent.ExecutorService GUARD_EXECUTOR =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("hook-webhook-client-");

    /**
     * Built on first use so it picks up the operator's allow-list once {@code
     * SsrfGuardConfiguration} has installed it. Every connection resolves through {@link
     * SsrfGuard#dnsResolver()}: the address that was checked is the address that is connected to,
     * which closes the DNS-rebinding window the pre-flight {@code urlGuard} leaves.
     */
    private static final class ClientHolder {
        static final CloseableHttpClient CLIENT =
                SafeHttpClients.create(
                        SsrfGuard.defaultGuard(),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        false);
    }

    private final Function<String, Optional<String>> urlGuard;
    private final List<String> platformAllowedHosts;
    private final String signingSecret;
    private final Transport transport;

    /**
     * @param urlGuard the SSRF guard: returns an error message for a URL that must not be called,
     *     empty when it is fine. {@code null} means no guard is available, which refuses every call
     *     (fail closed).
     */
    public HookWebhookClient(
            Function<String, Optional<String>> urlGuard,
            List<String> platformAllowedHosts,
            String signingSecret) {
        this(
                urlGuard,
                platformAllowedHosts,
                signingSecret,
                (uri, body, headers, timeout) ->
                        send(ClientHolder.CLIENT, uri, body, headers, timeout));
    }

    HookWebhookClient(
            Function<String, Optional<String>> urlGuard,
            List<String> platformAllowedHosts,
            String signingSecret,
            Transport transport) {
        this.urlGuard = urlGuard;
        this.platformAllowedHosts =
                platformAllowedHosts == null ? List.of() : List.copyOf(platformAllowedHosts);
        this.signingSecret = signingSecret == null ? "" : signingSecret.trim();
        this.transport = transport;
    }

    /** The operator's own allow-list, for save-time checks. */
    public List<String> platformAllowedHosts() {
        return platformAllowedHosts;
    }

    /**
     * @param extraAllowedHosts the tenant's own allow-list, merged with the operator's
     * @throws WebhookException when the URL is not allowed, the call fails, times out, or the reply
     *     is not a verdict
     */
    public Reply post(
            String url,
            String payloadJson,
            Duration timeout,
            Collection<String> extraAllowedHosts) {
        String refusal = allowListProblem(url, merge(platformAllowedHosts, extraAllowedHosts));
        if (refusal != null) {
            throw new WebhookException(refusal);
        }
        if (urlGuard == null) {
            throw new WebhookException("no SSRF guard is available, so webhooks are refused");
        }
        // The guard does a blocking DNS lookup, so it runs on its own thread inside the caller's
        // deadline; whatever
        // is left of the timeout is what the HTTP call gets.
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Optional<String> blocked = guardWithin(url, timeout);
        if (blocked != null && blocked.isPresent()) {
            throw new WebhookException("the SSRF guard refused the URL: " + blocked.get());
        }
        timeout =
                Duration.ofNanos(
                        Math.max(
                                TimeUnit.MILLISECONDS.toNanos(1),
                                deadlineNanos - System.nanoTime()));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put(EVENT_HEADER, "agent.hook");
        headers.put("User-Agent", "VegaDuta-AgentHooks/1");
        if (!signingSecret.isEmpty()) {
            headers.put(
                    WebhookSignature.HEADER,
                    WebhookSignature.sign(
                            System.currentTimeMillis() / 1000L, signingSecret, payloadJson));
        }
        Response response;
        try {
            response = transport.post(URI.create(url.trim()), payloadJson, headers, timeout);
        } catch (WebhookException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebhookException("interrupted", e);
        } catch (Exception e) {
            throw new WebhookException(
                    "the webhook call failed (" + e.getClass().getSimpleName() + ")", e);
        }
        return parseReply(response);
    }

    private Optional<String> guardWithin(String url, Duration timeout) {
        CompletableFuture<Optional<String>> check =
                CompletableFuture.supplyAsync(() -> urlGuard.apply(url), GUARD_EXECUTOR);
        try {
            return check.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            check.cancel(true);
            throw new WebhookException("the SSRF guard's DNS check timed out", e);
        } catch (InterruptedException e) {
            check.cancel(true);
            Thread.currentThread().interrupt();
            throw new WebhookException("interrupted", e);
        } catch (ExecutionException e) {
            throw new WebhookException(
                    "the SSRF guard failed ("
                            + (e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName())
                            + ")",
                    e);
        }
    }

    /** Pure: turns an HTTP response into a verdict or a {@link WebhookException}. */
    static Reply parseReply(Response response) {
        if (response == null || response.status() < 200 || response.status() >= 300) {
            throw new WebhookException(
                    "the webhook answered HTTP " + (response == null ? "?" : response.status()));
        }
        String body = response.body() == null ? "" : response.body();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_REPLY_BYTES) {
            throw new WebhookException("the webhook reply was too large");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new WebhookException("the webhook reply was not JSON");
        }
        if (node == null
                || !node.isObject()
                || !node.hasNonNull("decision")
                || !node.get("decision").isTextual()) {
            throw new WebhookException("the webhook reply had no decision");
        }
        String decision = node.get("decision").asText().trim().toLowerCase(Locale.ROOT);
        if (!decision.equals("allow") && !decision.equals("deny")) {
            throw new WebhookException("the webhook decision was neither allow nor deny");
        }
        String reason =
                node.hasNonNull("reason") && node.get("reason").isTextual()
                        ? cleanReason(node.get("reason").asText())
                        : null;
        return new Reply(decision.equals("allow"), reason);
    }

    /**
     * Single line, printable, capped: a webhook's reason may reach the model, so it gets no
     * formatting power.
     */
    static String cleanReason(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_REASON_CHARS));
        boolean lastSpace = false;
        for (int i = 0; i < raw.length() && sb.length() < MAX_REASON_CHARS; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            sb.append(c);
            lastSpace = false;
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    // ------------------------------------------------------------- allow-list

    /**
     * Shape + allow-list check, no DNS. Used at save time (so a bad URL is a 400, not a silent
     * no-op) and again at call time.
     *
     * @return an error message, or {@code null} when the URL passes
     */
    public static String allowListProblem(String url, Collection<String> allowedHosts) {
        String shape = HookSchema.structuralUrlProblem(url);
        if (shape != null) {
            return "the webhook URL " + shape;
        }
        String host = URI.create(url.trim()).getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return "no webhook hosts are allow-listed for this workspace";
        }
        return hostAllowed(host, allowedHosts)
                ? null
                : "the host \"" + host + "\" is not on the webhook allow-list";
    }

    /**
     * Exact match, or {@code *.suffix} for subdomains only ({@code *.example.com} never matches
     * {@code example.com}).
     */
    static boolean hostAllowed(String host, Collection<String> allowedHosts) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : allowedHosts) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.startsWith("*.")) {
                if (h.endsWith(e.substring(1))) {
                    return true;
                }
            } else if (h.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> merge(List<String> a, Collection<String> b) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(a);
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }

    // -------------------------------------------------------------- transport

    /**
     * The real exchange over {@code client}; package-private so a test can aim it at a loopback
     * server.
     */
    static Response send(
            CloseableHttpClient client,
            URI uri,
            String body,
            Map<String, String> headers,
            Duration timeout)
            throws Exception {
        HttpPost post = new HttpPost(uri);
        headers.forEach(
                (name, value) -> {
                    if (!name.equalsIgnoreCase("Content-Type")) {
                        post.addHeader(name, value);
                    }
                });
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        post.setConfig(
                RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(timeout))
                        .setConnectionRequestTimeout(Timeout.of(timeout))
                        .build());
        CompletableFuture<Response> future =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return client.execute(
                                        post,
                                        response -> {
                                            HttpEntity entity = response.getEntity();
                                            byte[] reply =
                                                    entity == null
                                                            ? new byte[0]
                                                            : readCapped(entity.getContent());
                                            return new Response(
                                                    response.getCode(),
                                                    new String(reply, StandardCharsets.UTF_8));
                                        });
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        GUARD_EXECUTOR);
        try {
            // The whole exchange, including a receiver that trickles bytes, is bounded by the
            // caller's timeout.
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            post.abort();
            future.cancel(true);
            throw new WebhookException("the webhook timed out", e);
        } catch (ExecutionException e) {
            post.abort();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof UncheckedIOException u && u.getCause() != null) {
                cause = u.getCause();
            }
            if (cause instanceof SocketTimeoutException) {
                throw new WebhookException("the webhook timed out", cause);
            }
            throw new WebhookException("the webhook call failed", cause);
        }
    }

    /**
     * Reads at most {@link #MAX_REPLY_BYTES} + 1 bytes, then stops: an oversize reply is detected,
     * never buffered.
     */
    static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        try (in) {
            int room = MAX_REPLY_BYTES + 1;
            while (room > 0) {
                int n = in.read(chunk, 0, Math.min(chunk.length, room));
                if (n < 0) {
                    break;
                }
                buffer.write(chunk, 0, n);
                room -= n;
            }
        }
        return buffer.toByteArray();
    }
}
