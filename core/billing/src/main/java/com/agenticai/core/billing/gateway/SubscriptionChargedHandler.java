package com.agenticai.core.billing.gateway;

import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Handles {@code subscription.charged} events — fired by Razorpay when a subscription payment is
 * successfully collected.
 *
 * <p>Actions: 1. Fetch tenant ID by gateway subscription ID (via JdbcTemplate) 2. Update
 * subscription status to ACTIVE in the tenant table 3. Queue receipt email send (via
 * BillingEmailService) 4. Log the transition
 *
 * <p>Uses JdbcTemplate to avoid importing the Tenant entity and creating a cycle dependency
 * (tenancy -> billing -> tenancy). Email service is injected as optional (ObjectProvider) so it's
 * skipped if mail isn't configured, same as NewsletterService pattern.
 *
 * <p>If the tenant subscription is not found, log a warning and continue — this can happen if a
 * webhook is replayed for an old subscription.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class SubscriptionChargedHandler implements BillingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionChargedHandler.class);

    private final JdbcTemplate jdbcTemplate;
    private final BillingEmailService emailService;

    public SubscriptionChargedHandler(JdbcTemplate jdbcTemplate, BillingEmailService emailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.emailService = emailService;
    }

    @Override
    public void handle(BillingEvent event) {
        String gatewaySubscriptionId = event.gatewaySubscriptionId();
        if (gatewaySubscriptionId == null || gatewaySubscriptionId.isBlank()) {
            log.warn("subscription.charged event has no gateway subscription ID - skipping");
            return;
        }

        // 1. Fetch tenant ID by gateway subscription ID
        String tenantIdStr;
        try {
            tenantIdStr =
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM control_plane.tenant WHERE gateway_subscription_id = ?",
                            String.class,
                            gatewaySubscriptionId);
        } catch (EmptyResultDataAccessException e) {
            log.warn(
                    "No tenant found for gateway subscription ID '{}' - webhook may be replayed",
                    LogSafe.clean(gatewaySubscriptionId));
            return;
        } catch (Exception e) {
            // Split out from the no-row case above: connection refused, pool exhaustion or
            // schema drift are NOT a replayed webhook, and logging them as one buries a
            // tenant who has PAID and will never reach ACTIVE. The gateway's own retries are
            // the only recovery, so this needs a findable ERROR with the stack, not a WARN
            // asserting a cause we never checked.
            log.error(
                    "Tenant lookup failed for gateway subscription ID '{}' - subscription.charged dropped, "
                            + "the tenant stays un-activated until the gateway retries",
                    LogSafe.clean(gatewaySubscriptionId),
                    e);
            return;
        }

        UUID tenantId = UUID.fromString(tenantIdStr);
        String tenantName;

        // 2. Update subscription status to ACTIVE (idempotent)
        try {
            int updated =
                    jdbcTemplate.update(
                            "UPDATE control_plane.tenant SET subscription_status = ? WHERE id = ? AND (subscription_status = ? OR subscription_status != ?)",
                            "ACTIVE",
                            tenantId,
                            "PENDING",
                            "ACTIVE");
            if (updated > 0) {
                log.info(
                        "Tenant {} subscription '{}' transitioned to ACTIVE",
                        tenantId,
                        LogSafe.clean(gatewaySubscriptionId));
            } else {
                log.info(
                        "Tenant {} subscription '{}' already ACTIVE (charged event is idempotent)",
                        tenantId,
                        LogSafe.clean(gatewaySubscriptionId));
            }

            tenantName =
                    jdbcTemplate.queryForObject(
                            "SELECT name FROM control_plane.tenant WHERE id = ?",
                            String.class,
                            tenantId);
        } catch (Exception e) {
            // Pass the throwable, not just getMessage(): a tenant has paid and did not get
            // ACTIVE, and a bare Spring JDBC message ("could not extract ResultSet") never
            // says which statement or constraint failed. The receipt-email catch below
            // already logs with the stack - this one was the outlier, not the pattern.
            log.error("Failed to update subscription status for tenant {}", tenantId, e);
            return;
        }

        // 3. Send receipt email (async, failures logged but not re-raised)
        try {
            emailService.sendReceiptEmail(tenantId, tenantName);
        } catch (Exception e) {
            log.error(
                    "Failed to send receipt email for tenant {} (subscription '{}')",
                    tenantId,
                    LogSafe.clean(gatewaySubscriptionId),
                    e);
            // Don't fail the handler — subscription is already marked ACTIVE
        }
    }
}
