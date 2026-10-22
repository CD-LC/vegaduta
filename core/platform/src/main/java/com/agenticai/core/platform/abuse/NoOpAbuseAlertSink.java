package com.agenticai.core.platform.abuse;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase P5 (Fraud & Anomaly) - the always-present default alert sink. Ships
 * doing nothing beyond a debug log line, so a fresh deployment raises
 * fingerprinted {@code ABUSE_ANOMALY} issues (via {@code PlatformIssueService})
 * but sends no external alert until an operator configures one (e.g. sets
 * {@code abuse.alert.webhook.url} to activate {@link WebhookAbuseAlertSink}).
 * Being an ordinary bean, it coexists with any opt-in sinks - {@code
 * AbuseSignalService} fans out to the whole {@code List<AbuseAlertSink>}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class NoOpAbuseAlertSink implements AbuseAlertSink {

    private static final Logger log = LoggerFactory.getLogger(NoOpAbuseAlertSink.class);

    @Override
    public void send(AbuseAlert alert) {
        log.debug(
            "Abuse alert (no external sink configured): tenant={} user={} score={} tier={} actionApplied={}",
            alert.tenantId(), alert.userId(), alert.score(), alert.tier(), alert.actionApplied()
        );
    }
}
