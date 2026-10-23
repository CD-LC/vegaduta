package com.agenticai.core.billing.gateway;

import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Handles {@code subscription.pending} events — fired by Razorpay when a subscription payment fails
 * and is retried by Razorpay's retry engine.
 *
 * <p>Actions: 1. Fetch tenant ID by gateway subscription ID (via JdbcTemplate) 2. Mark subscription
 * status as PENDING, carrying the failed-attempt counter (D5 — see below) 3. Track past-due
 * timestamp (first failure wins) 4. D1 — send a payment-failed email with a retry CTA on the first
 * failure, the escalating dunning email on subsequent failures (via the existing {@link
 * BillingEmailService}; skipped for FREE tenants — their signup-time trial subscription has no paid
 * plan to lose) 5. D5 — after {@code app.billing.dunning.max-failed-attempts} failures (default 3),
 * publish {@link SubscriptionPaymentExhaustedEvent}, which core/tenancy's listener turns into the
 * existing {@code Tenant#downgradeToFree} plan transition
 *
 * <p><b>D5 attempt tracking (no new schema)</b>: the counter rides on the tenant row's existing
 * {@code subscription_status} column — {@code "PENDING"} on the first failed attempt, {@code
 * "PENDING:2"}, {@code "PENDING:3"}... on Razorpay's subsequent retries. That column has always
 * held free-form gateway status strings for display/debugging (V11's comment); nothing compares it
 * against exact paid-state values ({@code 'created'} and {@code 'ACTIVE'} are the only literal
 * comparisons in core, both unaffected), and every success path (charged handler's {@code ACTIVE},
 * {@code Tenant#confirmUpgrade}/{@code clearPastDue}'s raw event names) overwrites it, which is
 * exactly the counter reset a recovered payment should perform.
 *
 * <p>Uses JdbcTemplate to avoid importing the Tenant entity and creating a cycle dependency
 * (tenancy -> billing -> tenancy); the downgrade itself is delegated to tenancy via the application
 * event for the same reason.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class SubscriptionPendingHandler implements BillingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPendingHandler.class);

    private static final String PENDING_STATUS = "PENDING";

    private final JdbcTemplate jdbcTemplate;
    private final BillingEmailService emailService;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxFailedAttempts;

    public SubscriptionPendingHandler(
            JdbcTemplate jdbcTemplate,
            BillingEmailService emailService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.billing.dunning.max-failed-attempts:3}") int maxFailedAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
        this.eventPublisher = eventPublisher;
        this.maxFailedAttempts = maxFailedAttempts;
    }

    @Override
    public void handle(BillingEvent event) {
        String gatewaySubscriptionId = event.gatewaySubscriptionId();
        if (gatewaySubscriptionId == null || gatewaySubscriptionId.isBlank()) {
            log.warn("subscription.pending event has no gateway subscription ID - skipping");
            return;
        }

        // Fetch tenant ID by gateway subscription ID, then the name/plan/
        // status the dunning decisions below need.
        String tenantIdStr;
        String tenantName;
        String tenantPlan;
        String previousStatus;
        try {
            tenantIdStr =
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM control_plane.tenant WHERE gateway_subscription_id = ?",
                            String.class,
                            gatewaySubscriptionId);
            Map<String, Object> row =
                    jdbcTemplate.queryForMap(
                            "SELECT name, plan, subscription_status FROM control_plane.tenant WHERE id = ?::uuid",
                            tenantIdStr);
            tenantName = (String) row.get("name");
            tenantPlan = (String) row.get("plan");
            previousStatus = (String) row.get("subscription_status");
        } catch (EmptyResultDataAccessException e) {
            log.warn(
                    "No tenant found for gateway subscription ID '{}' on subscription.pending",
                    LogSafe.clean(gatewaySubscriptionId));
            return;
        } catch (Exception e) {
            // Split out from the no-row case above for the same reason as
            // SubscriptionChargedHandler's lookup: a DB failure here is not "no such tenant",
            // and silently reporting it as one loses the payment-failure signal entirely.
            log.error(
                    "Tenant lookup failed for gateway subscription ID '{}' - subscription.pending dropped",
                    LogSafe.clean(gatewaySubscriptionId),
                    e);
            return;
        }

        UUID tenantId = UUID.fromString(tenantIdStr);

        // D5 - derive this failure's attempt number from the counter the
        // previous pending event left in subscription_status (see class
        // javadoc; any non-PENDING previous value means this is attempt 1).
        int failedAttempts = previousFailedAttempts(previousStatus) + 1;
        String newStatus =
                failedAttempts <= 1 ? PENDING_STATUS : PENDING_STATUS + ":" + failedAttempts;

        // Mark subscription as PENDING (Razorpay is retrying the payment)
        // This typically means: initial charge failed, Razorpay will retry 2-3 times
        try {
            jdbcTemplate.update(
                    "UPDATE control_plane.tenant SET subscription_status = ?, past_due_since = CASE WHEN past_due_since IS NULL THEN ? ELSE past_due_since END WHERE id = ?",
                    newStatus,
                    Instant.now(),
                    tenantId);
            log.info(
                    "Tenant '{}' subscription '{}' payment pending - failed attempt {} of {} (Razorpay retry in progress)",
                    tenantName,
                    LogSafe.clean(gatewaySubscriptionId),
                    failedAttempts,
                    maxFailedAttempts);
        } catch (Exception e) {
            // Stack trace, not just getMessage(): failing to record PENDING means past_due_since
            // is never set and the attempt counter never advances, so the D5 auto-downgrade
            // clock never starts for this tenant and nothing else in the system will notice.
            // Same fix as SubscriptionChargedHandler's status-update catch.
            log.error("Failed to mark subscription as PENDING for tenant {}", tenantId, e);
            // The attempt was not recorded - do not email or count toward the
            // downgrade threshold off a write that didn't happen.
            return;
        }

        if ("FREE".equalsIgnoreCase(tenantPlan)) {
            // The signup-time trial subscription's own lifecycle - a FREE
            // tenant has no paid plan to dun for or downgrade from, the same
            // deliberate hands-off posture RazorpayWebhookService's
            // FREE-tenant guard has always taken.
            log.info(
                    "Tenant '{}' is on FREE - subscription '{}' payment pending recorded, no dunning applies",
                    tenantName,
                    LogSafe.clean(gatewaySubscriptionId));
            return;
        }

        // D1 - payment-failed email with retry CTA on the first failure,
        // the existing escalating dunning template on later ones. Same
        // fire-and-forget posture as the charged handler's receipt email.
        try {
            if (failedAttempts <= 1) {
                // Razorpay's pending webhook carries no decline reason - the
                // template renders without one.
                emailService.sendPaymentFailedEmail(tenantId, tenantName, null);
            } else {
                emailService.sendDunningEmail(tenantId, tenantName, failedAttempts);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to send payment-failure email for tenant {} (subscription '{}')",
                    tenantId,
                    LogSafe.clean(gatewaySubscriptionId),
                    e);
            // Don't fail the handler - the pending state is already recorded
        }

        // D5 - after N failures, auto-downgrade to FREE via the existing
        // Tenant#downgradeToFree path (SubscriptionPaymentExhaustedEvent's
        // listener in core/tenancy).
        if (failedAttempts >= maxFailedAttempts) {
            log.warn(
                    "Tenant '{}' subscription '{}' reached {} failed payment attempts (max {}) - publishing auto-downgrade to FREE",
                    tenantName,
                    LogSafe.clean(gatewaySubscriptionId),
                    failedAttempts,
                    maxFailedAttempts);
            eventPublisher.publishEvent(
                    new SubscriptionPaymentExhaustedEvent(
                            tenantId, tenantName, gatewaySubscriptionId, failedAttempts));
        }
    }

    /**
     * How many failed attempts the previous {@code subscription_status} value records: {@code
     * "PENDING"} is 1, {@code "PENDING:n"} is n, anything else (a healthy status, null, or an
     * unparseable remnant) is 0 - the next failure then counts as attempt 1.
     */
    static int previousFailedAttempts(String subscriptionStatus) {
        if (subscriptionStatus == null) {
            return 0;
        }
        if (PENDING_STATUS.equals(subscriptionStatus)) {
            return 1;
        }
        if (subscriptionStatus.startsWith(PENDING_STATUS + ":")) {
            try {
                return Math.max(
                        1,
                        Integer.parseInt(
                                subscriptionStatus.substring(PENDING_STATUS.length() + 1)));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 0;
    }
}
