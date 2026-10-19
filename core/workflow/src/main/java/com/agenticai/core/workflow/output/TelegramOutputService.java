package com.agenticai.core.workflow.output;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.outbound.TenantBotToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Output node "telegram" destination (#154). Outbound-send-only (no inbound webhook, no pairing
 * flow) - deliberately scoped down from the omnichannel module's Twilio pattern because a workflow
 * output notification only needs to *push a message to a known chat*, not receive/route inbound
 * traffic.
 *
 * <p>Since #472 added real per-tenant Telegram Bot connections ({@link TelegramCredentialService},
 * with genuine inbound webhook routing), this prefers a tenant's own bot token when one is active -
 * same "BYOK wins over the shared platform relay" precedent as {@code WhatsAppDispatchService}
 * preferring Meta over Twilio - falling back to the single platform-wide bot token (env var {@code
 * TELEGRAM_BOT_TOKEN} / {@code app.telegram.bot-token}) only when a tenant hasn't connected their
 * own bot.
 */
@Service
public class TelegramOutputService {

    private static final Logger log = LoggerFactory.getLogger(TelegramOutputService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String platformBotToken;
    // The tenant's own connected bot, owned by the Telegram channel package (ADR-0011 rule a).
    private final List<TenantBotToken> tenantBotTokens;
    private final ObjectMapper objectMapper;
    private final WorkflowOutputDedupGuard dedupGuard;
    private final HttpClient httpClient;

    /**
     * Explicitly {@code @Autowired}: this class also carries a package-private test-seam
     * constructor (below) so Spring's implicit "single constructor" autowiring rule doesn't apply
     * here - without this annotation, having two constructors and no {@code @Autowired} left Spring
     * unable to pick one, failing bean creation with a "no default constructor" error at boot
     * rather than at compile time.
     */
    @Autowired
    public TelegramOutputService(
            @Value("${app.telegram.bot-token:}") String platformBotToken,
            List<TenantBotToken> tenantBotTokens,
            ObjectMapper objectMapper,
            WorkflowOutputDedupGuard dedupGuard) {
        this(
                platformBotToken,
                tenantBotTokens,
                objectMapper,
                dedupGuard,
                GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false));
    }

    /** Test seam - lets the suite exercise the send/dedup interplay without a live Telegram API. */
    TelegramOutputService(
            String platformBotToken,
            List<TenantBotToken> tenantBotTokens,
            ObjectMapper objectMapper,
            WorkflowOutputDedupGuard dedupGuard,
            HttpClient httpClient) {
        this.platformBotToken = platformBotToken;
        this.tenantBotTokens = tenantBotTokens;
        this.objectMapper = objectMapper;
        this.dedupGuard = dedupGuard;
        this.httpClient = httpClient;
    }

    /**
     * @param runId the workflow run this send belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     * @return a short, human-readable log line describing what happened, for the run's detailed
     *     trace.
     */
    public String send(String chatId, String text, String runId, String nodeId) {
        String botToken = resolveBotToken();
        if (!StringUtils.hasText(botToken)) {
            throw new ChannelConfigurationException(
                    "telegram",
                    "No Telegram bot configured - connect one at /admin/channels, or set TELEGRAM_BOT_TOKEN.");
        }
        if (!StringUtils.hasText(chatId)) {
            throw new ChannelConfigurationException(
                    "telegram", "Output node is missing a destination chat ID.");
        }
        // At-least-once execution can re-run this send verbatim - claim after
        // config validation, release on delivery failure so a retry of a send
        // that never reached Telegram isn't suppressed. See
        // WorkflowOutputDedupGuard's javadoc for the identity-key's limits.
        String dedupKey = dedupGuard.identityKey("telegram", runId, nodeId, chatId);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info(
                    "Suppressed duplicate workflow-output Telegram send (dedup key already claimed within TTL).");
            return "Skipped duplicate Telegram message to chat "
                    + chatId
                    + " - an identical send already went out moments ago.";
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of("chat_id", chatId, "text", text));
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://api.telegram.org/bot"
                                                    + botToken
                                                    + "/sendMessage"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new ChannelDeliveryException(
                        "telegram",
                        "Telegram API returned " + response.statusCode() + ": " + response.body());
            }
            return "Sent to Telegram chat " + chatId + ".";
        } catch (ChannelDeliveryException | ChannelConfigurationException e) {
            dedupGuard.release(dedupKey);
            throw e;
        } catch (Exception e) {
            dedupGuard.release(dedupKey);
            throw new ChannelDeliveryException(
                    "telegram", "Could not reach Telegram: " + e.getMessage());
        }
    }

    private String resolveBotToken() {
        // No per-tenant bot connected - fall back to the shared platform relay.
        return tenantBotTokens.stream()
                .filter(t -> "telegram".equals(t.channel()))
                .findFirst()
                .flatMap(TenantBotToken::activeBotToken)
                .orElse(platformBotToken);
    }
}
