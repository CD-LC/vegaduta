package com.agenticai.core.billing.email;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Sends billing lifecycle emails: receipt (A1), welcome (D1 future), payment failed (D1), dunning
 * (D5), etc.
 *
 * <p>Mirrors {@code NewsletterService} and {@code DemoTranscriptEmailService} pattern: {@link
 * JavaMailSender} is injected via {@link ObjectProvider} rather than a hard constructor dependency.
 * If spring.mail.host is unset, the mail sender is unavailable and emails are logged as skipped
 * (not 5xx).
 *
 * <p>All emails are wrapped in a branded shell (logo, wordmark, footer) via {@link
 * #wrapBranded(String)}, so every mail the platform sends reads as one product.
 */
@Service
public class BillingEmailService {

    private static final Logger log = LoggerFactory.getLogger(BillingEmailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final BillingEmailTemplateService templateService;
    private final JdbcTemplate jdbcTemplate;
    private final String receiptFromAddress;
    private final String paymentFailedFromAddress;
    private final String dunningFromAddress;
    private final String welcomeFromAddress;

    /**
     * Tenant web app origin ({@code app.web.public-url}); substituted for {@link
     * BillingEmailTemplateService#APP_URL_TOKEN}.
     */
    private final String appPublicUrl;

    /**
     * Comp-grant email CTA ({@code billing.email.upgrade-url}); defaults to {@code
     * <app.web.public-url>/billing}.
     */
    private final String upgradeUrl;

    /**
     * {@code app.edition.billing-enabled}. Off: receipts, welcome, dunning, promo and complimentary-plan mails
     * are never sent (there is no subscription to write about). The spend-cap alert is a cost control, not a
     * billing mail, and still goes out when a mail server is configured.
     */
    private final boolean billingEdition;

    /**
     * Four distinct From: addresses, one per email type - all authenticated via the SAME SMTP login
     * ({@code spring.mail.username}, see {@code CORE_SMTP_USER} in deploy/.env), since Spring only
     * configures one {@link JavaMailSender} bean. billing@/collections@ are separate Google
     * Workspace mailboxes (own passwords, unused here) rather than aliases of noreply@ - sending
     * From: them via noreply@'s authenticated session relies on Workspace's same-domain From
     * flexibility, not a configured Gmail "Send As" alias. If a recipient's mail client ever shows
     * "via noreply@<your-domain>" or Workspace rejects the mismatched From, the fix is either a
     * Gmail "Send As" delegation for noreply@ or splitting this into three JavaMailSender beans
     * (one per mailbox).
     */
    public BillingEmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            BillingEmailTemplateService templateService,
            JdbcTemplate jdbcTemplate,
            @Value("${billing.email.receipt-from:billing@example.com}") String receiptFromAddress,
            @Value("${billing.email.payment-failed-from:billing@example.com}")
                    String paymentFailedFromAddress,
            @Value("${billing.email.dunning-from:collections@example.com}")
                    String dunningFromAddress,
            @Value("${billing.email.welcome-from:noreply@example.com}") String welcomeFromAddress,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${billing.email.upgrade-url:}") String upgradeUrl,
            @Value("${app.edition.billing-enabled:true}") boolean billingEdition) {
        this.billingEdition = billingEdition;
        this.appPublicUrl = appPublicUrl.replaceAll("/+$", "");
        this.mailSenderProvider = mailSenderProvider;
        this.templateService = templateService;
        this.jdbcTemplate = jdbcTemplate;
        this.receiptFromAddress = receiptFromAddress;
        this.paymentFailedFromAddress = paymentFailedFromAddress;
        this.dunningFromAddress = dunningFromAddress;
        this.welcomeFromAddress = welcomeFromAddress;
        this.upgradeUrl =
                upgradeUrl == null || upgradeUrl.isBlank()
                        ? this.appPublicUrl + "/billing"
                        : upgradeUrl;
    }

    /**
     * Send receipt email on successful subscription charge (A1). Called by {@link
     * SubscriptionChargedHandler} when {@code subscription.charged} event is received.
     */
    public void sendReceiptEmail(UUID tenantId, String tenantName) {
        if (!billingEdition) {
            return;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping receipt email", tenantName);
            return;
        }

        // Fetch plan from database
        String plan = getPlanFromDb(tenantId);
        String subject = "Your VegaDūta subscription receipt — " + plan;
        String bodyHtml = templateService.renderReceipt(tenantName, plan);

        sendEmail(receiptFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Send welcome email on first successful subscription activation (D1 future). Called after
     * subscription.charged + marked ACTIVE for the first time.
     */
    public void sendWelcomeEmail(UUID tenantId, String tenantName) {
        if (!billingEdition) {
            return;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping welcome email", tenantName);
            return;
        }

        String plan = getPlanFromDb(tenantId);
        String subject = "Welcome to VegaDūta " + plan + "!";
        String bodyHtml = templateService.renderWelcome(tenantName, plan);

        sendEmail(welcomeFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Send payment failed email on subscription.pending (D1 future). Includes retry CTA + option to
     * downgrade to FREE.
     */
    public void sendPaymentFailedEmail(UUID tenantId, String tenantName, String failureReason) {
        if (!billingEdition) {
            return;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping payment-failed email", tenantName);
            return;
        }

        String plan = getPlanFromDb(tenantId);
        String subject = "Your VegaDūta payment could not be processed";
        String bodyHtml = templateService.renderPaymentFailed(tenantName, plan, failureReason);

        sendEmail(paymentFailedFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Send dunning email on prolonged payment failure (D5 future). Sent on day 3, 7, 14 of a failed
     * subscription with escalating urgency.
     */
    public void sendDunningEmail(UUID tenantId, String tenantName, int attemptNumber) {
        if (!billingEdition) {
            return;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping dunning email", tenantName);
            return;
        }

        String plan = getPlanFromDb(tenantId);
        String subject = "Action required: Update your payment method on VegaDūta";
        String bodyHtml = templateService.renderDunning(tenantName, plan, attemptNumber);

        sendEmail(dunningFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Site-wide "try Unlimited free for 2 weeks" promo (2026-08-14) - sent by {@code
     * SiteWidePromoService} once per tenant granted the promo, when that call opts in via {@code
     * sendEmail}. {@code expiresOn} is a pre-formatted human date, passed through as-is to the
     * template (see {@link BillingEmailTemplateService#renderUnlimitedPromo}).
     */
    public void sendUnlimitedPromoEmail(UUID tenantId, String tenantName, String expiresOn) {
        if (!billingEdition) {
            return;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping Unlimited promo email", tenantName);
            return;
        }

        String subject = "You've got Unlimited access for 2 weeks — on us";
        String bodyHtml = templateService.renderUnlimitedPromo(tenantName, expiresOn);

        sendEmail(welcomeFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Pre-expiry reminder for a complimentary-plan grant (see CompGrantReminderJob). Returns
     * whether it was sent so the caller records the reminder only when the customer actually got it
     * (a retry next tick otherwise).
     */
    public boolean sendCompGrantReminderEmail(
            UUID tenantId, String tenantName, String expiresOn, int daysLeft) {
        if (!billingEdition) {
            return false;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn("Tenant '{}' has no admin email - skipping comp-grant reminder", tenantName);
            return false;
        }
        String subject =
                daysLeft <= 1
                        ? "Your free full access ends tomorrow"
                        : "Your free full access ends in " + daysLeft + " days";
        String bodyHtml =
                templateService.renderCompGrantReminder(
                        tenantName, expiresOn, daysLeft, upgradeUrl);
        return sendEmail(
                welcomeFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * "You are now on Free - here is how to get the full plan back", sent when a comp grant lapses
     * to FREE.
     */
    public boolean sendCompGrantEndedEmail(UUID tenantId, String tenantName) {
        if (!billingEdition) {
            return false;
        }
        String recipientEmail = getAdminEmail(tenantId);
        if (recipientEmail == null) {
            log.warn(
                    "Tenant '{}' has no admin email - skipping comp-grant-ended email", tenantName);
            return false;
        }
        String subject = "Your workspace is now on the Free plan";
        String bodyHtml = templateService.renderCompGrantEnded(tenantName, upgradeUrl);
        return sendEmail(
                welcomeFromAddress, recipientEmail, subject, bodyHtml, tenantId.toString());
    }

    /**
     * Monthly hosted-pool spend cap alert (80 or 100 percent) to EVERY tenant admin. Called once
     * per tenant, month and stage (SpendCapService claims the stage first).
     *
     * @return how many admins it was handed to the mail server for
     */
    public int sendSpendCapAlertEmail(
            UUID tenantId,
            String tenantName,
            int stage,
            java.math.BigDecimal monthToDateUsd,
            java.math.BigDecimal capUsd,
            String resetsOn) {
        java.util.List<String> recipients = getAdminEmails(tenantId);
        if (recipients.isEmpty()) {
            log.warn("Tenant '{}' has no admin email - skipping spend-cap alert", tenantName);
            return 0;
        }
        String subject =
                stage >= 100
                        ? "Your workspace has reached its monthly AI spend limit"
                        : "Your workspace has used 80% of its monthly AI spend limit";
        String bodyHtml =
                templateService.renderSpendCapAlert(
                        tenantName, stage, monthToDateUsd, capUsd, resetsOn);
        int sent = 0;
        for (String to : recipients) {
            if (sendEmail(welcomeFromAddress, to, subject, bodyHtml, tenantId.toString())) {
                sent++;
            }
        }
        return sent;
    }

    // ========== Private ==========

    private java.util.List<String> getAdminEmails(UUID tenantId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT email FROM control_plane.app_user WHERE tenant_id = ? AND role"
                            + " = ? AND email IS NOT NULL",
                    String.class,
                    tenantId,
                    "TENANT_ADMIN");
        } catch (Exception e) {
            log.warn("Could not list admin emails for tenant {}: {}", tenantId, e.getMessage());
            return java.util.List.of();
        }
    }

    private String getAdminEmail(UUID tenantId) {
        // Find the first TENANT_ADMIN user's email for this tenant, mirroring
        // NewsletterService's pattern. Returns null if no admin found yet (early signup).
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT email FROM control_plane.app_user WHERE tenant_id = ? AND role = ?"
                            + " ORDER BY created_at ASC LIMIT 1",
                    String.class,
                    tenantId,
                    "TENANT_ADMIN");
        } catch (Exception e) {
            return null; // No admin found (early signup) or query failed
        }
    }

    private String getPlanFromDb(UUID tenantId) {
        // Fetch the tenant's current plan (PREMIUM, UNLIMITED, FREE, etc.)
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT plan FROM control_plane.tenant WHERE id = ?", String.class, tenantId);
        } catch (Exception e) {
            return "Premium"; // Fallback (shouldn't happen if tenantId was valid)
        }
    }

    /**
     * @return true only when the message was actually handed to the mail server (false: no sender /
     *     send failed).
     */
    private boolean sendEmail(
            String fromAddress,
            String toAddress,
            String subject,
            String bodyHtml,
            String tenantIdForLogging) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn(
                    "Mail sender not configured (spring.mail.host unset) - skipping email to {}:"
                            + " '{}'",
                    toAddress,
                    subject);
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(
                    wrapBranded(bodyHtml)
                            .replace(BillingEmailTemplateService.APP_URL_TOKEN, appPublicUrl),
                    true);
            mailSender.send(message);
            log.info(
                    "Sent billing email to {} (tenant: {}, subject: {})",
                    com.agenticai.core.security.LogMasking.email(toAddress),
                    tenantIdForLogging,
                    subject);
            return true;
        } catch (jakarta.mail.MessagingException | MailException e) {
            log.error(
                    "Failed to send billing email to {} (tenant: {}): {}",
                    com.agenticai.core.security.LogMasking.email(toAddress),
                    tenantIdForLogging,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Wrap HTML email body in VegaDūta branded shell (logo, wordmark, footer). Reuses the same look
     * as {@code NewsletterService} and Keycloak email theme so every mail the platform sends reads
     * as one product.
     */
    private String wrapBranded(String bodyHtml) {
        if (bodyHtml.toLowerCase(java.util.Locale.ROOT).contains("<html")) {
            return bodyHtml; // Already a full document
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="margin:0;padding:0;background-color:#f4f3fb;font-family:Arial,Helvetica,sans-serif;">
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f3fb;padding:32px 16px;">
<tr>
<td align="center">
<table role="presentation" width="480" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;width:100%%;background-color:#ffffff;border-radius:12px;border:1px solid #e7e4f7;">
<tr>
<td style="padding:28px 32px 20px 32px;text-align:center;border-bottom:1px solid #efeaff;">
<img src="{{APP_URL}}/logo-mark.png" alt="" width="38" height="28" style="display:inline-block;vertical-align:middle;border:0;margin:0 10px 0 0;">
<img src="{{APP_URL}}/vegaduta-wordmark.png" alt="VegaDūta" width="132" height="35" style="display:inline-block;vertical-align:middle;border:0;height:35px;width:132px;">
</td>
</tr>
<tr>
<td style="padding:28px 32px 24px 32px;color:#33303d;font-size:15px;line-height:1.6;">
%s
</td>
</tr>
<tr>
<td style="padding:20px 32px 28px 32px;border-top:1px solid #efeaff;text-align:center;">
<div style="font-size:12px;color:#a09cae;line-height:1.6;">
<span style="font-family:'Yatra One',Georgia,'Times New Roman',serif;font-weight:400;color:#33303d;letter-spacing:0.2px;">Vega<span style="color:#f97316;">Dūta</span></span> — AI staff for your business<br>
<a href="{{APP_URL}}/account/billing" style="color:#7c5cff;text-decoration:none;">Manage your subscription</a>
</div>
</td>
</tr>
</table>
</td>
</tr>
</table>
</body>
</html>
"""
                .formatted(bodyHtml);
    }
}
