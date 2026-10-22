package com.agenticai.core.agents.tools.web;

import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP adapter for the {@code web_task} built-in - POSTs the task to a managed-browser provider's
 * {@code /v1/tasks} endpoint (see {@link WebTaskBackend}'s javadoc for the contract).
 *
 * <p><b>Why no SSRF guard here.</b> The base URL is platform-admin config (resolved from {@code
 * PlatformToolCredential}'s {@code extraConfig}), NOT model-controlled input - self-hosted
 * Steel/gateways are a first-class case and legitimately live at private addresses.
 * Model-controlled input is the {@code task} string and {@code startUrl}; only {@code startUrl} is
 * validated (http/https scheme), and the provider is the boundary that decides what the browser may
 * reach. This mirrors how {@code DynamicMcpConnectionManager} trusts tenant-configured endpoints.
 *
 * <p><b>Sync by design.</b> The call blocks up to {@code timeoutSeconds} (default 180, hard cap
 * 600) - matching how every other built-in here returns a tool result in-turn. Providers that
 * outlive that should be fronted by a gateway that keeps the session alive and returns a resumable
 * handle, which is a contract extension, not a different backend.
 */
public class HttpWebTaskBackend implements WebTaskBackend {

    private static final Logger log = LoggerFactory.getLogger(HttpWebTaskBackend.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    static final int MAX_TIMEOUT_SECONDS = 600;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public HttpWebTaskBackend(String baseUrl, String apiKey) {
        this(
                baseUrl,
                apiKey,
                // Raw JDK client on purpose (see class javadoc): the operator-configured
                // backend URL is a trusted target and may be a private address, which a
                // guarded client would refuse unless separately allow-listed.
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Visible for tests - lets a stub transport inject canned responses. */
    HttpWebTaskBackend(String baseUrl, String apiKey, HttpClient http) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("web_task backend baseUrl is required");
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "web_task backend baseUrl must be http or https, got: " + trimmed);
        }
        this.baseUrl = trimmed;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public WebTaskResult run(WebTaskRequest request) throws WebTaskException {
        int timeout = Math.min(Math.max(request.timeoutSeconds(), 5), MAX_TIMEOUT_SECONDS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task", request.task());
        if (StringUtils.hasText(request.startUrl())) {
            body.put("startUrl", request.startUrl());
        }
        if (request.maxSteps() != null) {
            body.put("maxSteps", request.maxSteps());
        }
        body.put("timeoutSeconds", timeout);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new WebTaskException("failed to serialize task request", e);
        }
        HttpRequest.Builder httpReq =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/tasks"))
                        .timeout(Duration.ofSeconds(timeout + 15))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");
        if (StringUtils.hasText(apiKey)) {
            // Authorization:Bearer covers Steel/Browserbase-style APIs; the
            // x-api-key alias is sent too since self-hosted gateways differ.
            httpReq.header("Authorization", "Bearer " + apiKey);
            httpReq.header("x-api-key", apiKey);
        }
        HttpResponse<String> resp;
        try {
            resp =
                    http.send(
                            httpReq.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                            HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebTaskException("interrupted waiting for the browser task", e);
        } catch (IOException e) {
            throw new WebTaskException(
                    "could not reach the browser provider: " + e.getMessage(), e);
        }
        if (resp.statusCode() >= 400) {
            String snippet = resp.body() == null ? "" : resp.body();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new WebTaskException(
                    "provider returned HTTP " + resp.statusCode() + ": " + snippet);
        }
        try {
            JsonNode node = MAPPER.readTree(resp.body());
            String status = node.path("status").asString("success");
            String result = node.path("result").asString("");
            String detail = node.path("detail").asString(null);
            return new WebTaskResult(status, result, detail);
        } catch (JacksonException e) {
            // A provider that replies non-JSON still ran the task - return the
            // raw body rather than failing, so the model sees SOMETHING useful.
            String raw = resp.body() == null ? "" : resp.body();
            log.debug("web_task provider returned non-JSON body ({} chars)", raw.length());
            return new WebTaskResult(
                    "success", raw.length() > 20000 ? raw.substring(0, 20000) : raw, null);
        }
    }
}
