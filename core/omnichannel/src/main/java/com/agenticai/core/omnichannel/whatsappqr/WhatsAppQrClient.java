package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for the whatsapp-qr bridge ({@code services/whatsapp-qr}) the operator runs: a Node +
 * Baileys service that holds the tenant's linked-device WhatsApp session. Unlike signal-cli, the
 * bridge is a platform component - its URL and shared secret come from {@code
 * app.whatsappqr.bridge-url} / {@code app.whatsappqr.bridge-secret}, not from a per-tenant form.
 * Both blank (the default) means the channel is absent: {@link WhatsAppQrService} then reports the
 * connect action as unavailable instead of pointing users at a service that does not exist.
 *
 * <p>Every call goes through the same address-pinned, redirect-free {@link SsrfGuard} client as
 * {@code SignalApiClient}: a private bridge host works only when the operator lists it in {@code
 * app.security.outbound.allowed-private-hosts}, and the shared secret is only sent over https or
 * to such an allow-listed host - never part of a URL, a log line or an exception message.
 */
@Component
public class WhatsAppQrClient {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppQrClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Supplier<SsrfGuard> guardSupplier;
    private final String bridgeUrl;
    private final String bridgeSecret;
    private CloseableHttpClient client;
    private SsrfGuard clientGuard;

    @Autowired
    public WhatsAppQrClient(
            ObjectMapper objectMapper,
            @Value("${app.whatsappqr.bridge-url:}") String bridgeUrl,
            @Value("${app.whatsappqr.bridge-secret:}") String bridgeSecret) {
        this(objectMapper, SsrfGuard::defaultGuard, bridgeUrl, bridgeSecret);
    }

    /** Test seam: a guard that admits the local fake bridge. */
    WhatsAppQrClient(
            ObjectMapper objectMapper,
            Supplier<SsrfGuard> guardSupplier,
            String bridgeUrl,
            String bridgeSecret) {
        this.objectMapper = objectMapper;
        this.guardSupplier = guardSupplier;
        this.bridgeUrl = bridgeUrl == null ? "" : bridgeUrl.strip();
        this.bridgeSecret = bridgeSecret == null ? "" : bridgeSecret;
    }

    /** True when the operator configured a bridge; false means the whole channel stays hidden. */
    public boolean configured() {
        return !bridgeUrl.isBlank() && !bridgeSecret.isBlank();
    }

    /** One-line liveness for the connection probe - the bridge's own /health, not a synthetic ping. */
    public void health() {
        call("GET", "/health", null, VALIDATE_TIMEOUT);
    }

    /** Idempotent session create/resume - also how a restarted core reattaches a live session. */
    public JsonNode ensureSession(String sessionKey) {
        ObjectNode body = objectMapper.createObjectNode().put("sessionKey", sessionKey);
        return call("POST", "/sessions", body.toString(), REQUEST_TIMEOUT);
    }

    public JsonNode sessionStatus(String sessionKey) {
        return call("GET", "/sessions/" + enc(sessionKey), null, REQUEST_TIMEOUT);
    }

    /**
     * Long-poll for inbound messages plus the session's live status ({@code {status, events[]}}).
     * The bridge answers early on any status transition, so this doubles as the connect/logout
     * notifier for {@link WhatsAppQrReceiveService}.
     */
    public JsonNode receive(String sessionKey, int waitSeconds) {
        String path = "/sessions/" + enc(sessionKey) + "/events?timeout=" + waitSeconds;
        return call("GET", path, null, Duration.ofSeconds(waitSeconds + 10L));
    }

    public void send(String sessionKey, String to, String text) {
        ObjectNode body = objectMapper.createObjectNode().put("to", to).put("text", text);
        call("POST", "/sessions/" + enc(sessionKey) + "/send", body.toString(), REQUEST_TIMEOUT);
    }

    /** Logout + wipe of the linked device. Idempotent at the bridge. */
    public void deleteSession(String sessionKey) {
        call("DELETE", "/sessions/" + enc(sessionKey), null, REQUEST_TIMEOUT);
    }

    // --------------------------------------------------------------- plumbing

    private SsrfGuard.Target validate() {
        if (bridgeUrl == null || bridgeUrl.isBlank()) {
            // Not a policy refusal: nothing is configured. Say so without the private-host hint.
            throw new WhatsAppQrApiException(
                    WhatsAppQrApiException.REFUSED,
                    "No WhatsApp bridge URL is configured (app.whatsappqr.bridge-url /"
                            + " APP_WHATSAPPQR_BRIDGE_URL).");
        }
        SsrfGuard.Target target;
        try {
            target = guardSupplier.get().validate(bridgeUrl);
        } catch (SsrfBlockedException e) {
            throw new WhatsAppQrApiException(
                    WhatsAppQrApiException.REFUSED,
                    "The WhatsApp bridge URL was refused by the outbound request policy ("
                            + e.getMessage()
                            + "). If the bridge runs on a private network, an operator must list its"
                            + " host in app.security.outbound.allowed-private-hosts.");
        }
        boolean clearText = !"https".equalsIgnoreCase(target.uri().getScheme());
        if (clearText && !target.operatorAllowed()) {
            throw new WhatsAppQrApiException(
                    WhatsAppQrApiException.REFUSED,
                    "The WhatsApp bridge secret is only sent over https (or to a host the operator"
                            + " allow-listed).");
        }
        return target;
    }

    private synchronized CloseableHttpClient httpClient() {
        SsrfGuard guard = guardSupplier.get();
        if (client == null || guard != clientGuard) {
            closeQuietly(client);
            client = SafeHttpClients.create(guard, CONNECT_TIMEOUT, REQUEST_TIMEOUT, false);
            clientGuard = guard;
        }
        return client;
    }

    private record Raw(int status, String body) {}

    private JsonNode call(String method, String path, String jsonBody, Duration timeout) {
        validate();
        HttpUriRequestBase request =
                new HttpUriRequestBase(method, URI.create(trimTrailingSlash(bridgeUrl) + path));
        request.setHeader("Accept", "application/json");
        request.setHeader("Authorization", "Bearer " + bridgeSecret);
        if (jsonBody != null) {
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        }
        request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(timeout)).build());
        Raw raw;
        try {
            raw =
                    httpClient()
                            .execute(
                                    request,
                                    response -> {
                                        String body = "";
                                        if (response.getEntity() != null) {
                                            try (InputStream in = response.getEntity().getContent()) {
                                                byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                                                if (bytes.length > MAX_RESPONSE_BYTES) {
                                                    throw new IOException("response too large");
                                                }
                                                body = new String(bytes, StandardCharsets.UTF_8);
                                            }
                                        }
                                        return new Raw(response.getCode(), body);
                                    });
        } catch (IOException e) {
            throw new WhatsAppQrApiException(
                    WhatsAppQrApiException.IO,
                    "Could not reach the WhatsApp bridge: "
                            + e.getClass().getSimpleName()
                            + describe(e),
                    e);
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            throw new WhatsAppQrApiException(
                    raw.status(),
                    "WhatsApp bridge answered HTTP " + raw.status() + errorText(raw.body()));
        }
        if (raw.body().isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(raw.body());
        } catch (JsonProcessingException e) {
            throw new WhatsAppQrApiException(
                    raw.status(), "WhatsApp bridge sent a response that is not JSON.", e);
        }
    }

    private String errorText(String body) {
        try {
            String error = objectMapper.readTree(body).path("error").asText("");
            if (!error.isBlank()) {
                return ": " + (error.length() > 200 ? error.substring(0, 200) : error);
            }
        } catch (Exception e) {
            // not JSON: say nothing about the body
        }
        return "";
    }

    private static String describe(IOException e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String first = detail.lines().findFirst().orElse("");
        return first.isBlank() ? "" : " - " + (first.length() > 120 ? first.substring(0, 120) : first);
    }

    private static void closeQuietly(CloseableHttpClient c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
