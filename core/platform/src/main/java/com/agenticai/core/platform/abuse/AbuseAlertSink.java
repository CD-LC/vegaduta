package com.agenticai.core.platform.abuse;

/**
 * Phase P5 (Fraud & Anomaly) - a pluggable alert destination. The design
 * intent (governance doc §7): a no-op default that ships doing nothing, plus
 * webhook/email sinks an operator opts into by configuration. {@link
 * AbuseSignalService} injects {@code List<AbuseAlertSink>} and fans every
 * alert out to all of them, so adding a sink is purely additive - register a
 * bean, no change to the scorer.
 *
 * <p>Every implementation MUST be fail-open: an alert-delivery failure (a dead
 * webhook, an SMTP timeout) must be swallowed and logged, never propagated -
 * abuse alerting is a safeguard layered on top of the primary action
 * (throttle/suspend, which have already happened by the time an alert fires),
 * so a broken alert channel can never become a second failure or block the
 * request path. The {@code PlatformIssueService} fingerprinted-issue record is
 * always written regardless of sinks, so an operator is never blind even with
 * zero sinks configured.
 */
public interface AbuseAlertSink {

    /** Deliver one alert. Implementations must not throw. */
    void send(AbuseAlert alert);
}
