package com.agenticai.core.billing.email;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders HTML email templates for billing lifecycle events. Each template method returns an HTML
 * fragment (no full document) — the fragment is wrapped in a branded shell by {@link
 * BillingEmailService#wrapBranded}.
 *
 * <p>All templates use table-based markup and inline CSS only (no external stylesheets, no remote
 * assets) for maximum email client compatibility.
 */
@Service
public class BillingEmailTemplateService {

    /**
     * Placeholder for the tenant web app origin in rendered templates; {@link BillingEmailService}
     * replaces it with {@code app.web.public-url} when it sends the mail.
     */
    public static final String APP_URL_TOKEN = "{{APP_URL}}";

    /**
     * Receipt email on successful subscription charge (A1). Sent immediately after payment is
     * collected.
     */
    public String renderReceipt(String tenantName, String plan) {
        String planName = "PREMIUM".equalsIgnoreCase(plan) ? "Premium" : "Unlimited";
        String subscriptionId =
                "—"; // Don't include subscription ID in email (not part of public scope)

        return String.format(
                """
<h2>Your subscription receipt</h2>
<p>Thank you for upgrading to <strong>%s</strong>!</p>
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:20px 0;border:1px solid #e7e4f7;border-radius:8px;overflow:hidden;">
<tr style="background-color:#fafaf8;">
<td style="padding:12px 16px;font-weight:600;color:#33303d;">Plan</td>
<td style="padding:12px 16px;text-align:right;">%s</td>
</tr>
<tr>
<td style="padding:12px 16px;font-weight:600;color:#33303d;">Subscription ID</td>
<td style="padding:12px 16px;text-align:right;font-size:13px;color:#6b6775;font-family:monospace;">%s</td>
</tr>
<tr style="background-color:#fafaf8;">
<td style="padding:12px 16px;font-weight:600;color:#33303d;">Status</td>
<td style="padding:12px 16px;text-align:right;"><span style="display:inline-block;padding:4px 8px;background-color:#d4fce4;color:#065f38;border-radius:4px;font-size:13px;">Active</span></td>
</tr>
</table>
<p style="margin:20px 0 16px 0;font-size:14px;color:#6b6775;">Your subscription is now active and your workspace has access to all %s features.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="{{APP_URL}}/account/billing" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Manage subscription</a>
</td>
</tr>
</table>
""",
                planName, planName, subscriptionId, planName);
    }

    /**
     * Welcome email on first subscription activation (D1 future). Sent to highlight key features
     * and benefits of the new tier.
     */
    public String renderWelcome(String tenantName, String plan) {
        String planName = "PREMIUM".equalsIgnoreCase(plan) ? "Premium" : "Unlimited";
        String benefits =
                "PREMIUM".equalsIgnoreCase(plan)
                        ? "<li>Unlimited agents & tasks</li><li>Advanced MCP ecosystem"
                                + " access</li><li>WhatsApp Business + Email channels</li>"
                        : "<li>Everything in Premium</li><li>Priority support</li><li>Advanced"
                                + " workflow orchestration</li>";

        // Brand name renders two-tone in the body copy too, not just the
        // wrapBranded() shell header (direct instruction 2026-08-12: "ensure
        // everywhere brand name is represented using the brandcolour") - same
        // #7c5cff accent BillingEmailService#wrapBranded already uses for
        // "Dūta" in this email system, kept consistent rather than
        // introducing the web app's separate #f97316 --brand-accent into
        // transactional mail.
        return String.format(
                """
<h2>Welcome to Vega<span style="color:#7c5cff;">Dūta</span> %s!</h2>
<p>Your workspace has been upgraded. Here's what you can now do:</p>
<ul style="margin:16px 0;padding-left:20px;color:#33303d;font-size:14px;line-height:1.8;">
%s
</ul>
<p style="margin:20px 0;font-size:14px;color:#6b6775;">Start building right away at <a href="{{APP_URL}}/" style="color:#7c5cff;text-decoration:none;">{{APP_URL}}</a></p>
""",
                planName, benefits);
    }

    /**
     * Payment failed email on subscription.pending (D1 future). Includes retry CTA and option to
     * update payment method or downgrade.
     */
    public String renderPaymentFailed(String tenantName, String plan, String failureReason) {
        return String.format(
                """
<h2>Your payment could not be processed</h2>
<p>We attempted to charge your payment method for your %s subscription, but the charge was declined.</p>
<p><strong>What happens next?</strong><br>
Razorpay will retry the payment automatically. If retries fail, your workspace will be downgraded to the FREE plan.</p>
<p><strong>What you can do now:</strong></p>
<ol style="margin:16px 0;padding-left:20px;color:#33303d;font-size:14px;line-height:1.8;">
<li><a href="{{APP_URL}}/account/billing" style="color:#7c5cff;text-decoration:none;">Update your payment method</a> to keep your subscription active</li>
<li>Or <a href="{{APP_URL}}/account/billing" style="color:#7c5cff;text-decoration:none;">downgrade to FREE</a> to avoid charges</li>
</ol>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="{{APP_URL}}/account/billing" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Fix payment</a>
</td>
</tr>
</table>
""",
                "PREMIUM".equalsIgnoreCase(plan) ? "Premium" : "Unlimited");
    }

    /**
     * Dunning email on prolonged payment failure (D5 future). Sent on day 3, 7, 14 with escalating
     * urgency. attemptNumber = 1 (day 3), 2 (day 7), 3+ (day 14+)
     */
    public String renderDunning(String tenantName, String plan, int attemptNumber) {
        String urgency =
                switch (attemptNumber) {
                    case 1 -> "Your payment method needs attention soon.";
                    case 2 ->
                            "Your subscription will be downgraded in 7 days if this isn't"
                                    + " resolved.";
                    default ->
                            "Your subscription will be downgraded immediately if this isn't"
                                    + " resolved.";
                };

        String planName = "PREMIUM".equalsIgnoreCase(plan) ? "Premium" : "Unlimited";
        return String.format(
                """
<h2>Action required: Fix your payment method</h2>
<p>%s</p>
<p>We've been unable to charge your payment method for your %s subscription. Razorpay has attempted automatic retries without success.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:20px 0;border:1px solid #fdbab9;border-radius:8px;background-color:#fff5f4;padding:16px;">
<tr>
<td style="color:#a4221d;font-weight:600;">⚠ Immediate action required</td>
</tr>
<tr>
<td style="padding-top:8px;color:#6b6775;font-size:14px;">Update your payment method now to keep your workspace on %s without interruption.</td>
</tr>
</table>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="{{APP_URL}}/account/billing" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Update payment method</a>
</td>
</tr>
</table>
""",
                urgency, planName, planName);
    }

    /**
     * Site-wide "try Unlimited free for 2 weeks" promo (2026-08-14). Sent once per tenant when
     * {@code SiteWidePromoService} issues the bulk comp grant; {@code expiresOn} is a pre-formatted
     * human date (e.g. "28 Aug 2026"), not a raw {@code Instant}, so this class stays free of
     * date-formatting/timezone concerns like every other render* method here.
     */
    public String renderUnlimitedPromo(String tenantName, String expiresOn) {
        return String.format(
                """
<h2>Your workspace now has full <span style="color:#7c5cff;">Unlimited</span> access</h2>
<p>For the next two weeks (until <strong>%s</strong>), %s is running on our top Unlimited tier at no charge — no usage caps, white-labeling, and every premium capability unlocked.</p>
<p style="margin:16px 0;font-size:14px;color:#6b6775;">This is a great window to try the things a FREE or PREMIUM plan usually caps: more agents and workflows, higher usage limits, and white-label branding on your public-facing surfaces.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="{{APP_URL}}/" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Explore Unlimited features</a>
</td>
</tr>
</table>
<p style="margin:20px 0 0 0;font-size:13px;color:#a09cae;">Your plan automatically returns to its usual tier after %s — no action needed, and nothing is charged.</p>
""",
                expiresOn, tenantName, expiresOn);
    }

    /**
     * Pre-expiry nudge for a complimentary-plan grant (signup bonus / promo / trial): {@code
     * daysLeft} days remain, the plan then returns to FREE on its own, and a subscription keeps the
     * full plan. {@code expiresOn} is a pre-formatted date; {@code upgradeUrl} is where the CTA
     * goes. Tenant name is HTML-escaped (free text).
     */
    public String renderCompGrantReminder(
            String tenantName, String expiresOn, int daysLeft, String upgradeUrl) {
        String when = daysLeft <= 1 ? "tomorrow" : "in " + daysLeft + " days";
        return String.format(
                """
<h2>Your free full-access period ends %s</h2>
<p>%s has had every feature unlocked at no charge. That bonus ends on <strong>%s</strong>, and your workspace then moves to the Free plan automatically &mdash; nothing is deleted and nothing is charged.</p>
<p style="margin:16px 0;font-size:14px;color:#6b6775;">To keep the higher limits, white-labeling and premium features, choose a plan before then. Start a subscription any time &mdash; you can pay in your local currency.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="%s" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Choose a plan</a>
</td>
</tr>
</table>
<p style="margin:20px 0 0 0;font-size:13px;color:#a09cae;">Prefer to stay on Free? No action is needed.</p>
""",
                when, HtmlUtils.htmlEscape(tenantName), expiresOn, upgradeUrl);
    }

    /**
     * Sent when the grant has just lapsed and the workspace is back on Free: what changed, and how
     * to get it back.
     */
    public String renderCompGrantEnded(String tenantName, String upgradeUrl) {
        return String.format(
                """
<h2>Your workspace is now on the Free plan</h2>
<p>The complimentary full-access period for %s has ended, so the workspace has moved to the Free plan. Everything you built is still there &mdash; only the higher limits and premium features are paused.</p>
<p style="margin:16px 0;font-size:14px;color:#6b6775;">Subscribe to turn them back on immediately. You can pay in your local currency, and you can cancel any time.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="%s" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Choose a plan</a>
</td>
</tr>
</table>
""",
                HtmlUtils.htmlEscape(tenantName), upgradeUrl);
    }

    /**
     * Monthly hosted-pool spend cap alert. {@code stage} is 80 or 100. Tenant name is HTML-escaped
     * (free text); amounts are formatted here, {@code resetsOn} is a pre-formatted date.
     */
    public String renderSpendCapAlert(
            String tenantName,
            int stage,
            java.math.BigDecimal monthToDateUsd,
            java.math.BigDecimal capUsd,
            String resetsOn) {
        String spent = money(monthToDateUsd);
        String cap = money(capUsd);
        String headline =
                stage >= 100
                        ? "Monthly AI spend limit reached"
                        : "80% of your monthly AI spend limit used";
        String detail =
                stage >= 100
                        ? "Requests that use the platform-provided AI models are paused until the"
                                + " limit resets on <strong>"
                                + HtmlUtils.htmlEscape(resetsOn)
                                + "</strong>. Agents running on your own provider keys (BYOK) are"
                                + " not affected."
                        : "When it reaches 100%, requests that use the platform-provided AI models"
                                + " pause until the limit resets on <strong>"
                                + HtmlUtils.htmlEscape(resetsOn)
                                + "</strong>. Agents running on your own provider keys (BYOK) are"
                                + " not affected.";
        return String.format(
                """
<h2>%s</h2>
<p>%s has used <strong>%s</strong> of its <strong>%s</strong> monthly limit for platform-provided AI models.</p>
<p style="margin:16px 0;font-size:14px;color:#6b6775;">%s</p>
<p style="margin:20px 0 0 0;font-size:13px;color:#a09cae;">Need a higher limit? Reply to this email or contact support.</p>
""",
                headline,
                HtmlUtils.htmlEscape(tenantName == null ? "Your workspace" : tenantName),
                spent,
                cap,
                detail);
    }

    private static String money(java.math.BigDecimal value) {
        if (value == null) {
            return "$0.00";
        }
        return "$" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
