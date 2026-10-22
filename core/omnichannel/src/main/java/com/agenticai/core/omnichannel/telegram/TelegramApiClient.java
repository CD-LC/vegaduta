package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Plain {@link HttpClient} wrapper for Telegram's Bot API - a real REST API with
 * always-200-with-an-"ok"-field responses (closer to Slack's contract than Discord's), and, unlike
 * Discord, a genuine {@code setWebhook} call that lets Telegram push updates straight to this
 * backend over plain HTTPS - no persistent Gateway/Node microservice needed for inbound at all.
 */
@Component
public class TelegramApiClient {

    private static final String DEFAULT_API_BASE = "https://api.telegram.org/bot";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Bot Token validation runs while the tenant waits on a spinner (SE-69: a junk token took up to
     * 7.7 s), and a healthy getMe answers in well under a second, so it gets a much tighter budget.
     */
    static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(4);

    /** Overridable for tests only; see the package-private constructor. */
    Duration validateTimeout = VALIDATE_TIMEOUT;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBase;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramApiClient(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_API_BASE);
    }

    /** Test seam: point the client at a local stub instead of api.telegram.org. */
    TelegramApiClient(ObjectMapper objectMapper, String apiBase) {
        this(objectMapper, apiBase, GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false));
    }

    /**
     * Test seam: a raw transport for loopback fixture servers, which the SSRF guard rightly
     * refuses.
     */
    TelegramApiClient(ObjectMapper objectMapper, String apiBase, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.httpClient = httpClient;
    }

    /**
     * Validates a Bot Token with a real {@code getMe} call - "prove it before persisting", same as
     * every other credential here.
     */
    public BotInfo fetchBotInfo(String botToken) {
        JsonNode result =
                call(botToken, "getMe", null, "Telegram rejected this Bot Token", validateTimeout);
        return new BotInfo(result.path("id").asText(null), result.path("username").asText(null));
    }

    /**
     * Points Telegram's servers at this backend's public webhook URL for this bot, verified on
     * every future call via {@code secretToken}.
     */
    public void setWebhook(String botToken, String webhookUrl, String secretToken) {
        try {
            String body =
                    objectMapper.writeValueAsString(
                            Map.of("url", webhookUrl, "secret_token", secretToken));
            call(botToken, "setWebhook", body, "Telegram rejected the webhook URL");
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "telegram", "Could not register the Telegram webhook: " + e.getMessage());
        }
    }

    /**
     * Tenant-initiated disconnect ({@code TelegramCredentialService#disconnect}) - drops the
     * webhook registration so Telegram stops pushing updates to this backend at all. Without this
     * the bot stays subscribed after "disconnect": the unlink means inbound payloads no longer
     * resolve a tenant, but Telegram would keep delivering (and retrying) them forever. {@code
     * drop_pending_updates=false} keeps anything already queued on Telegram's side - a tenant who
     * reconnects later gets those back rather than silently losing messages.
     */
    public void deleteWebhook(String botToken) {
        call(
                botToken,
                "deleteWebhook",
                "{\"drop_pending_updates\":false}",
                "Telegram rejected deleteWebhook");
    }

    /**
     * Long-poll for new updates (no webhook registered). Blocks up to {@code waitSeconds} on
     * Telegram's side and returns as soon as one arrives; the HTTP timeout is longer so a quiet
     * poll is not mistaken for a dead connection. {@code offset} is the last handled {@code
     * update_id} plus one, which also acknowledges everything before it.
     */
    public JsonNode getUpdates(String botToken, long offset, int waitSeconds) {
        try {
            String body =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "offset",
                                    offset,
                                    "timeout",
                                    waitSeconds,
                                    "allowed_updates",
                                    java.util.List.of("message")));
            return call(
                    botToken,
                    "getUpdates",
                    body,
                    "Telegram getUpdates failed",
                    Duration.ofSeconds(waitSeconds + 10L));
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "telegram", "Could not poll Telegram: " + e.getMessage());
        }
    }

    /**
     * @return a short, human-readable log line describing what happened.
     */
    public String sendMessage(String botToken, String chatId, String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("chat_id", chatId, "text", text));
            call(botToken, "sendMessage", body, "Telegram send failed");
            return "Sent Telegram message to chat " + chatId + ".";
        } catch (ChannelConfigurationException e) {
            throw new ChannelDeliveryException("telegram", e.getMessage());
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "telegram", "Could not reach the Telegram API: " + e.getMessage());
        }
    }

    /**
     * Master-Roadmap-2026.md item #1 (DM/inbox automation quality), gap (b)#1: every inbound reply
     * used to be fully blocking with zero feedback until the complete answer was ready - often
     * 5-15s+ for a tool-calling or CAREFUL/PLANNED turn. Telegram's Bot API has a real {@code
     * sendChatAction} endpoint (unlike Twilio/WhatsApp, which has no typing-indicator API at all) -
     * firing this immediately after an inbound message is accepted shows the native "typing..."
     * indicator in the user's chat for a few seconds, exactly the same UX a human replying from
     * their phone would produce. Deliberately swallows every failure rather than throwing - this is
     * a pure UX nicety, never a reason to fail or delay the actual reply.
     */
    public void sendTypingIndicator(String botToken, String chatId) {
        try {
            String body =
                    objectMapper.writeValueAsString(Map.of("chat_id", chatId, "action", "typing"));
            call(botToken, "sendChatAction", body, "Telegram typing indicator failed");
        } catch (Exception e) {
            // Best-effort only - see javadoc above.
        }
    }

    private JsonNode call(String botToken, String method, String jsonBody, String failureMessage) {
        return call(botToken, method, jsonBody, failureMessage, TIMEOUT);
    }

    private JsonNode call(
            String botToken,
            String method,
            String jsonBody,
            String failureMessage,
            Duration timeout) {
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(apiBase + botToken + "/" + method))
                            .timeout(timeout)
                            .header("Content-Type", "application/json; charset=utf-8");
            builder =
                    jsonBody != null
                            ? builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            : builder.POST(HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            try {
                node = objectMapper.readTree(response.body());
            } catch (Exception e) {
                node = objectMapper.nullNode();
            }
            if (response.statusCode() < 200
                    || response.statusCode() >= 300
                    || !node.path("ok").asBoolean(false)) {
                throw new ChannelConfigurationException(
                        "telegram",
                        failureMessage + ": " + node.path("description").asText(response.body()));
            }
            return node.path("result");
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ChannelConfigurationException(
                    "telegram",
                    "Telegram did not answer within "
                            + timeout.toSeconds()
                            + " seconds. Check the Bot Token and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelConfigurationException(
                    "telegram", "Telegram validation was interrupted. Try again.");
        } catch (Exception e) {
            throw new ChannelConfigurationException(
                    "telegram", "Could not reach the Telegram API: " + e.getMessage());
        }
    }

    public record BotInfo(String id, String username) {}
}
