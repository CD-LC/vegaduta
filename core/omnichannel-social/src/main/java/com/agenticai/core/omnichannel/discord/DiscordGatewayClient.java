package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Talks to the standalone {@code channels/discord} Node service - called exactly once per
 * credential activation/deactivation, never on an outbound send.
 *
 * <p>The reason Java calls the Node service at all, given {@link DiscordChannelAdapter} sends
 * replies directly via {@link DiscordApiClient}: receiving ordinary Discord messages requires a
 * persistent Gateway WebSocket connection (there is no public webhook for this, unlike
 * Interactions/slash-commands), and that connection has to be opened with this tenant's Bot Token
 * by SOME process. This method is that hand-off - "here's the token, open the socket and start
 * forwarding what you hear to {@code /webhook/discord}" - after which Java's involvement in inbound
 * delivery is limited to receiving that forwarded POST.
 *
 * <p>Deliberately best-effort from the caller's perspective ({@link
 * DiscordCredentialService#activate} logs a warning and proceeds rather than failing the whole
 * activation if this call fails) - a tenant's Bot Token is still valid and outbound sends still
 * work even if the Gateway bridge is unreachable or not deployed at all; they simply won't receive
 * inbound messages until it is - the feature degrades gracefully rather than failing the
 * activation.
 */
@Component
public class DiscordGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordGatewayClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String sharedSecret;

    public DiscordGatewayClient(
            ObjectMapper objectMapper,
            @Value("${app.omnichannel.discord.base-url:}") String baseUrl,
            @Value("${app.omnichannel.discord.shared-secret:}") String sharedSecret) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.sharedSecret = sharedSecret;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !sharedSecret.isBlank();
    }

    /**
     * Hands the Node service this tenant's Bot Token so it can open (or replace) that application's
     * Gateway connection.
     */
    public void startBot(String applicationId, String botToken) {
        if (!isConfigured()) {
            log.info(
                    "app.omnichannel.discord.base-url is not set - skipping Gateway bridge start for application ***{}. "
                            + "Outbound sends will still work; inbound messages will not be received.",
                    lastFour(applicationId));
            return;
        }
        try {
            call("POST", "/bots/" + applicationId + "/start", Map.of("botToken", botToken));
        } catch (Exception e) {
            log.warn(
                    "Could not reach the discord Gateway bridge to start application ***{}: {}",
                    lastFour(applicationId),
                    e.getMessage());
        }
    }

    /**
     * Best-effort: tells the Node service to close and forget this application's Gateway
     * connection.
     */
    public void stopBot(String applicationId) {
        if (!isConfigured()) {
            return;
        }
        try {
            call("DELETE", "/bots/" + applicationId, null);
        } catch (Exception e) {
            log.warn(
                    "Could not reach the discord Gateway bridge to stop application ***{}: {}",
                    lastFour(applicationId),
                    e.getMessage());
        }
    }

    private void call(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(TIMEOUT)
                        .header("X-Internal-Secret", sharedSecret)
                        .header("Content-Type", "application/json");
        builder =
                "DELETE".equals(method)
                        ? builder.DELETE()
                        : builder.POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body == null
                                                ? "{}"
                                                : objectMapper.writeValueAsString(body)));
        HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "discord Gateway bridge returned "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }
}
