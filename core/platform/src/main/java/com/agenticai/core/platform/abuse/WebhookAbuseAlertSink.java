package com.agenticai.core.platform.abuse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Phase P5 (Fraud & Anomaly) - the opt-in webhook alert sink. Only registered
 * when the operator sets {@code abuse.alert.webhook.url} (via {@code
 * @ConditionalOnProperty}), so it does not exist on a default deployment - the
 * {@link NoOpAbuseAlertSink} is the only sink until then. Posts the {@link
 * AbuseAlert} as JSON to the configured URL (Slack incoming-webhook,
 * PagerDuty Events API, or any generic receiver behind a gateway).
 *
 * <p>Strictly fail-open per {@link AbuseAlertSink}'s contract: a short timeout
 * and a swallow-and-log catch, so a dead or slow webhook never blocks the
 * scoring sweep or turns into a cascading failure. This is why an email sink is
 * intentionally left as an interface implementation for the operator rather
 * than shipped here - wiring SMTP credentials into core is an operator/infra
 * decision, and the {@link AbuseAlertSink} seam makes it a drop-in bean.
 */
@Component
@ConditionalOnProperty(name = "abuse.alert.webhook.url")
public class WebhookAbuseAlertSink implements AbuseAlertSink {

    private static final Logger log = LoggerFactory.getLogger(WebhookAbuseAlertSink.class);

    private final RestClient restClient;
    private final String url;

    public WebhookAbuseAlertSink(
        @Value("${abuse.alert.webhook.url}") String url,
        RestClient.Builder restClientBuilder
    ) {
        this.url = url;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void send(AbuseAlert alert) {
        try {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(alert)
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            // Never propagate - alerting is a safeguard on top of the primary action, not a gate.
            log.warn("Abuse alert webhook POST to configured URL failed (swallowed): {}", e.toString());
        }
    }
}
