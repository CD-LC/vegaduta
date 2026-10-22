package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * READ-ONLY cross-tenant billing view for the platform console ({@code /api/platform/billing/**}).
 * Nothing here moves money or mutates a row.
 *
 * <p><b>Why plain JDBC, and why no tenant context.</b> Everything this service reads is in the
 * {@code control_plane} schema and is addressed with a fully qualified name: subscription and
 * comp-grant state are columns on {@code control_plane.tenant} (V11 billing columns, V114 comp
 * grant, V161 past-due/cancel, V71 pending upgrade, V200 plan-before-comp), and invoices are {@code
 * control_plane.billing_invoice} (V160). None of it lives in a per-tenant schema, so the
 * tenant-less operator token needs no {@code TenantContext} binding - a fully-qualified {@code
 * control_plane.x} reference does not depend on the connection's current schema. (Same posture as
 * {@code InvoiceRepository} and {@code DemoSandboxMetricsService}.)
 *
 * <p><b>Known gaps, reported rather than faked:</b>
 *
 * <ul>
 *   <li>There is no payment-attempt / refund / failed-charge table. The only durable payment record
 *       is one invoice per <i>captured</i> payment; failed charges surface only as {@code
 *       past_due_since} on the tenant.
 *   <li>{@code current_period_end} is stored on the tenant but the webhook handler is the only
 *       writer; it is returned as-is (often null) and is never estimated here.
 *   <li>Invoice money is summed per currency and never across currencies.
 *   <li>Demo ({@code /try}) sandbox tenants are excluded, same as the roster.
 * </ul>
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
@Profile("security")
public class PlatformBillingService {

    /** Hard ceiling on tenant rows scanned for the subscriptions list. */
    static final int TENANT_SCAN_CAP = 5000;

    private static final String LIVE_TENANTS =
            "FROM control_plane.tenant t WHERE t.status = 'ACTIVE' AND NOT COALESCE(t.is_demo,"
                    + " FALSE)";

    private final JdbcTemplate jdbc;

    public PlatformBillingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- summary

    public BillingSummary summary() {
        Map<String, Long> byPlan = new LinkedHashMap<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT t.plan AS k, COUNT(*) AS n "
                                + LIVE_TENANTS
                                + " GROUP BY t.plan ORDER BY t.plan")) {
            byPlan.put(String.valueOf(r.get("k")), num(r.get("n")));
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT COALESCE(t.subscription_status, 'none') AS k, COUNT(*) AS n "
                                + LIVE_TENANTS
                                + " GROUP BY 1 ORDER BY 1")) {
            byStatus.put(String.valueOf(r.get("k")), num(r.get("n")));
        }
        Map<String, Object> c =
                jdbc
                        .queryForList(
                                "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE"
                                    + " t.comp_plan_expires_at > now()) AS comp_active, COUNT(*)"
                                    + " FILTER (WHERE t.comp_plan_expires_at > now()   AND"
                                    + " t.comp_plan_expires_at <= now() + interval '7 days') AS"
                                    + " comp_expiring_7d, COUNT(*) FILTER (WHERE"
                                    + " t.comp_plan_expires_at <= now()) AS comp_overdue_revert,"
                                    + " COUNT(*) FILTER (WHERE t.past_due_since IS NOT NULL) AS"
                                    + " past_due, COUNT(*) FILTER (WHERE t.cancel_scheduled) AS"
                                    + " cancel_scheduled, COUNT(*) FILTER (WHERE t.pending_plan IS"
                                    + " NOT NULL) AS pending_upgrade "
                                        + LIVE_TENANTS)
                        .stream()
                        .findFirst()
                        .orElse(Map.of());

        List<CurrencyTotal> invoiceTotals = new ArrayList<>();
        for (Map<String, Object> r :
                jdbc.queryForList(
                        "SELECT COALESCE(currency, '?') AS currency, COUNT(*) AS n,"
                            + " COALESCE(SUM(amount_minor), 0) AS amount,"
                            + " COALESCE(SUM(gst_amount_minor), 0) AS gst, COUNT(*) FILTER (WHERE"
                            + " issued_at >= now() - interval '30 days') AS n30,"
                            + " COALESCE(SUM(amount_minor) FILTER (WHERE issued_at >= now() -"
                            + " interval '30 days'), 0) AS amount30 FROM"
                            + " control_plane.billing_invoice GROUP BY 1 ORDER BY 1")) {
            invoiceTotals.add(
                    new CurrencyTotal(
                            String.valueOf(r.get("currency")),
                            num(r.get("n")),
                            num(r.get("amount")),
                            num(r.get("gst")),
                            num(r.get("n30")),
                            num(r.get("amount30"))));
        }
        return new BillingSummary(
                num(c.get("total")),
                byPlan,
                byStatus,
                num(c.get("comp_active")),
                num(c.get("comp_expiring_7d")),
                num(c.get("comp_overdue_revert")),
                num(c.get("past_due")),
                num(c.get("cancel_scheduled")),
                num(c.get("pending_upgrade")),
                invoiceTotals);
    }

    // ---------------------------------------------------------- subscriptions

    /**
     * @param plan optional exact plan filter (FREE/PREMIUM/UNLIMITED)
     * @param state optional derived-state filter, see {@link #stateOf}
     * @param q optional case-insensitive substring over tenant name
     */
    public SubscriptionList subscriptions(String plan, String state, String q, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql =
                new StringBuilder(
                                "SELECT t.id, t.name, t.plan, t.payment_gateway,"
                                    + " t.gateway_subscription_id, t.subscription_status,"
                                    + " t.trial_ends_at, t.current_period_end, t.past_due_since,"
                                    + " t.cancel_scheduled, t.pending_plan, t.comp_plan_expires_at,"
                                    + " t.comp_plan_granted_at, t.comp_plan_reason,"
                                    + " t.plan_before_comp_grant, t.created_at ")
                        .append(LIVE_TENANTS);
        if (plan != null && !plan.isBlank()) {
            sql.append(" AND t.plan = ?");
            args.add(plan.trim().toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND t.name ILIKE ?");
            args.add(
                    "%"
                            + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                            + "%");
        }
        sql.append(" ORDER BY t.name LIMIT ").append(TENANT_SCAN_CAP);

        String wanted =
                state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        List<SubscriptionRow> matched =
                jdbc.queryForList(sql.toString(), args.toArray()).stream()
                        .map(PlatformBillingService::subscriptionRow)
                        .filter(r -> wanted == null || wanted.equals(r.state()))
                        .toList();
        int cap = Math.max(1, Math.min(limit, 500));
        return new SubscriptionList(
                matched.size(),
                matched.size() >= TENANT_SCAN_CAP,
                matched.stream().limit(cap).toList());
    }

    /**
     * Derived, display-only classification. Order matters: a live comp grant masks whatever the
     * plan column says; then failed-payment grace; then a scheduled cancel; then a gateway-backed
     * paid plan. A paid plan with no gateway subscription and no comp grant can only be a
     * platform-admin override ({@code Tenant#changePlan}).
     */
    static String stateOf(
            String plan,
            String gatewaySubscriptionId,
            boolean hasComp,
            boolean pastDue,
            boolean cancelScheduled) {
        if (hasComp) {
            return "COMP";
        }
        if ("FREE".equals(plan)) {
            return "FREE";
        }
        if (pastDue) {
            return "PAST_DUE";
        }
        if (cancelScheduled) {
            return "CANCELING";
        }
        return gatewaySubscriptionId != null && !gatewaySubscriptionId.isBlank()
                ? "PAID"
                : "ADMIN_OVERRIDE";
    }

    /**
     * A comp grant only masks the plan while it is still running. Expired grants linger with {@code
     * comp_plan_expires_at} set until {@code CompGrantExpiryReverter} reverts them (and forever if
     * that revert fails), and used to be reported as COMP for that whole time.
     */
    static boolean isLiveComp(Instant compPlanExpiresAt, Instant now) {
        return compPlanExpiresAt != null && compPlanExpiresAt.isAfter(now);
    }

    private static SubscriptionRow subscriptionRow(Map<String, Object> r) {
        String plan = str(r.get("plan"));
        Instant compExpires = instant(r.get("comp_plan_expires_at"));
        boolean pastDue = r.get("past_due_since") != null;
        boolean cancel = Boolean.TRUE.equals(r.get("cancel_scheduled"));
        String gwSub = str(r.get("gateway_subscription_id"));
        return new SubscriptionRow(
                str(r.get("id")),
                str(r.get("name")),
                plan,
                stateOf(plan, gwSub, isLiveComp(compExpires, Instant.now()), pastDue, cancel),
                str(r.get("payment_gateway")),
                gwSub,
                str(r.get("subscription_status")),
                instant(r.get("trial_ends_at")),
                instant(r.get("current_period_end")),
                instant(r.get("past_due_since")),
                cancel,
                str(r.get("pending_plan")),
                compExpires,
                instant(r.get("comp_plan_granted_at")),
                str(r.get("comp_plan_reason")),
                str(r.get("plan_before_comp_grant")),
                instant(r.get("created_at")));
    }

    // ------------------------------------------------------------ comp grants

    /**
     * Every tenant with a comp grant on record, soonest expiry first; {@code overdue} = past
     * expiry, revert not yet run.
     */
    public CompGrantList compGrants(int limit) {
        int cap = Math.max(1, Math.min(limit, 500));
        List<CompGrantRow> rows =
                jdbc
                        .queryForList(
                                "SELECT t.id, t.name, t.plan, t.plan_before_comp_grant,"
                                        + " t.comp_plan_granted_at, t.comp_plan_expires_at,"
                                        + " t.comp_plan_reason "
                                        + LIVE_TENANTS
                                        + " AND t.comp_plan_expires_at IS NOT NULL"
                                        + " ORDER BY t.comp_plan_expires_at LIMIT "
                                        + cap)
                        .stream()
                        .map(
                                r -> {
                                    Instant exp = instant(r.get("comp_plan_expires_at"));
                                    return new CompGrantRow(
                                            str(r.get("id")),
                                            str(r.get("name")),
                                            str(r.get("plan")),
                                            str(r.get("plan_before_comp_grant")),
                                            instant(r.get("comp_plan_granted_at")),
                                            exp,
                                            str(r.get("comp_plan_reason")),
                                            exp != null && exp.isBefore(Instant.now()));
                                })
                        .toList();
        return new CompGrantList(rows.size(), rows);
    }

    // --------------------------------------------------------------- invoices

    /** Most recent invoices across tenants (or one tenant), newest first. Capped at 200. */
    public InvoiceList invoices(String tenantId, int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        String sql =
                "SELECT i.id, i.tenant_id, t.name AS tenant_name, i.invoice_number, i.gateway,"
                    + " i.gateway_payment_id, i.plan, i.period, i.currency, i.amount_minor,"
                    + " i.gst_amount_minor, i.issued_at FROM control_plane.billing_invoice i LEFT"
                    + " JOIN control_plane.tenant t ON t.id = i.tenant_id";
        List<Map<String, Object>> rows;
        if (tenantId != null && !tenantId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(tenantId.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("tenantId must be a UUID");
            }
            rows =
                    jdbc.queryForList(
                            sql
                                    + " WHERE i.tenant_id = ? ORDER BY i.issued_at DESC,"
                                    + " i.invoice_number DESC LIMIT "
                                    + cap,
                            id);
        } else {
            rows =
                    jdbc.queryForList(
                            sql + " ORDER BY i.issued_at DESC, i.invoice_number DESC LIMIT " + cap);
        }
        List<InvoiceRow> out =
                rows.stream()
                        .map(
                                r ->
                                        new InvoiceRow(
                                                str(r.get("id")),
                                                str(r.get("tenant_id")),
                                                str(r.get("tenant_name")),
                                                str(r.get("invoice_number")),
                                                str(r.get("gateway")),
                                                str(r.get("gateway_payment_id")),
                                                str(r.get("plan")),
                                                str(r.get("period")),
                                                str(r.get("currency")),
                                                num(r.get("amount_minor")),
                                                num(r.get("gst_amount_minor")),
                                                instant(r.get("issued_at"))))
                        .toList();
        return new InvoiceList(out.size(), out);
    }

    // ---------------------------------------------------------------- helpers

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }

    // ----------------------------------------------------------------- shapes

    /**
     * Money is minor units (paise/cents), one entry per currency; never summed across currencies.
     */
    public record CurrencyTotal(
            String currency,
            long invoices,
            long amountMinor,
            long gstMinor,
            long invoicesLast30d,
            long amountMinorLast30d) {}

    public record BillingSummary(
            long liveTenants,
            Map<String, Long> tenantsByPlan,
            Map<String, Long> tenantsBySubscriptionStatus,
            long activeCompGrants,
            long compGrantsExpiringWithin7d,
            long compGrantsOverdueForRevert,
            long pastDue,
            long cancelScheduled,
            long pendingUpgrade,
            List<CurrencyTotal> invoiceTotals) {}

    public record SubscriptionRow(
            String tenantId,
            String name,
            String plan,
            String state,
            String paymentGateway,
            String gatewaySubscriptionId,
            String subscriptionStatus,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            Instant pastDueSince,
            boolean cancelScheduled,
            String pendingPlan,
            Instant compExpiresAt,
            Instant compGrantedAt,
            String compReason,
            String planBeforeComp,
            Instant createdAt) {}

    /**
     * {@code total} is the match count before the {@code limit} cap; {@code scanTruncated} if the
     * tenant scan cap was hit.
     */
    public record SubscriptionList(
            int total, boolean scanTruncated, List<SubscriptionRow> subscriptions) {}

    public record CompGrantRow(
            String tenantId,
            String name,
            String plan,
            String planBeforeComp,
            Instant grantedAt,
            Instant expiresAt,
            String reason,
            boolean overdue) {}

    public record CompGrantList(int total, List<CompGrantRow> grants) {}

    public record InvoiceRow(
            String id,
            String tenantId,
            String tenantName,
            String invoiceNumber,
            String gateway,
            String gatewayPaymentId,
            String plan,
            String period,
            String currency,
            long amountMinor,
            long gstAmountMinor,
            Instant issuedAt) {}

    public record InvoiceList(int count, List<InvoiceRow> invoices) {}
}
