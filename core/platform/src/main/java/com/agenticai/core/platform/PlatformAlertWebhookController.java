package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alerting infrastructure pass (2026-07) - the receiving end of {@code
 * deploy/alertmanager/alertmanager.yml}'s webhook receiver. Mounted under {@code /webhook/**} -
 * deliberately NOT {@code /api/platform/**} (the URL the original audit sketched, {@code
 * /api/platform/alerts/webhook}) because that prefix is gated by {@code SecurityConfig}'s {@code
 * .requestMatchers("/api/platform/**") .hasAuthority("ROLE_platform-admin")} rule, which requires a
 * real Keycloak-issued JWT - Alertmanager has no JWT to present. {@code /webhook/**} is already
 * {@code permitAll()} for exactly this reason (see {@code WorkflowWebhookController}/{@code
 * TwilioWebhookController}, all third-party callers that can't do OAuth2 either), so this
 * controller reuses that existing carve-out and does its own lightweight auth instead - same shape
 * as {@code DiscordWebhookController}'s {@code X-Internal-Secret} check, just via the standard
 * {@code Authorization: Bearer <token>} header, since that's what Alertmanager's own {@code
 * webhook_config.http_config.authorization} can natively send (Alertmanager has no mechanism for an
 * arbitrary custom header, only Basic/Bearer auth or a mutual-TLS client cert).
 *
 * <p><b>Fails closed by default</b> - {@code app.platform.alerts.webhook-secret} (env {@code
 * PLATFORM_ALERTS_WEBHOOK_SECRET}) is blank out of the box, and every request is rejected with 401
 * until an operator sets it (both here, via {@code deploy/.env}, and in {@code alertmanager.yml}'s
 * receiver config - see that file's own comment). This is the ONE piece a human must still supply
 * before this pass's alerting can reach anyone at all; see this feature's rollout notes for the
 * full list (real Slack/PagerDuty/email channels are the other piece, configured entirely inside
 * {@code alertmanager.yml}, no code change needed there).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/webhook/alerts")
public class PlatformAlertWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PlatformAlertWebhookController.class);

    private final PlatformAlertWebhookService service;
    private final String webhookSecret;

    public PlatformAlertWebhookController(
            PlatformAlertWebhookService service,
            @Value("${app.platform.alerts.webhook-secret:}") String webhookSecret) {
        this.service = service;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/alertmanager")
    public ResponseEntity<?> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> payload) {
        if (webhookSecret.isBlank()) {
            log.warn(
                    "Alertmanager webhook call rejected - app.platform.alerts.webhook-secret / "
                            + "PLATFORM_ALERTS_WEBHOOK_SECRET is not configured, so this receiver fails closed. "
                            + "Set it in deploy/.env and in alertmanager.yml's receiver http_config.authorization.credentials.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            Map.of(
                                    "error",
                                    "Alertmanager webhook receiver is not configured on this deployment yet."));
        }
        String expected = "Bearer " + webhookSecret;
        // Constant-time: String.equals returns at the first differing byte.
        if (authorization == null
                || !java.security.MessageDigest.isEqual(
                        authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            log.warn(
                    "Rejected Alertmanager webhook call with missing/invalid Authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int recorded = service.recordDelivery(payload);
        return ResponseEntity.ok(Map.of("recorded", recorded));
    }
}
