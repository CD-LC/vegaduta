package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The raw {@code control_plane} billing SQL behind {@link PlatformBillingService}: read-only,
 * returns untyped rows for the service to shape.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
@Profile("security")
public class PlatformBillingJdbcRepository {

    private static final String LIVE_TENANTS =
            "FROM control_plane.tenant t WHERE t.status = 'ACTIVE' AND NOT COALESCE(t.is_demo,"
                    + " FALSE)";

    private final JdbcTemplate jdbc;

    public PlatformBillingJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Live-tenant counts keyed {@code k} (plan) with count {@code n}. */
    public List<Map<String, Object>> tenantCountsByPlan() {
        return jdbc.queryForList(
                "SELECT t.plan AS k, COUNT(*) AS n "
                        + LIVE_TENANTS
                        + " GROUP BY t.plan ORDER BY t.plan");
    }

    /** Live-tenant counts keyed {@code k} (subscription status) with count {@code n}. */
    public List<Map<String, Object>> tenantCountsBySubscriptionStatus() {
        return jdbc.queryForList(
                "SELECT COALESCE(t.subscription_status, 'none') AS k, COUNT(*) AS n "
                        + LIVE_TENANTS
                        + " GROUP BY 1 ORDER BY 1");
    }

    /** The single aggregate row of live-tenant / comp-grant / past-due counters. */
    public Map<String, Object> tenantCounters() {
        return jdbc
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
    }

    /** Per-currency invoice totals. */
    public List<Map<String, Object>> invoiceTotalsByCurrency() {
        return jdbc.queryForList(
                "SELECT COALESCE(currency, '?') AS currency, COUNT(*) AS n,"
                        + " COALESCE(SUM(amount_minor), 0) AS amount,"
                        + " COALESCE(SUM(gst_amount_minor), 0) AS gst, COUNT(*) FILTER (WHERE"
                        + " issued_at >= now() - interval '30 days') AS n30,"
                        + " COALESCE(SUM(amount_minor) FILTER (WHERE issued_at >= now() -"
                        + " interval '30 days'), 0) AS amount30 FROM"
                        + " control_plane.billing_invoice GROUP BY 1 ORDER BY 1");
    }

    /**
     * Live tenants' subscription columns, name-ordered, at most {@code scanCap} rows.
     *
     * @param plan optional exact plan (already normalised), null/blank for any
     * @param nameQuery optional case-insensitive name substring, null/blank for any
     */
    public List<Map<String, Object>> subscriptionRows(String plan, String nameQuery, int scanCap) {
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
            args.add(plan);
        }
        if (nameQuery != null && !nameQuery.isBlank()) {
            sql.append(" AND t.name ILIKE ?");
            args.add(
                    "%"
                            + nameQuery
                                    .trim()
                                    .replace("\\", "\\\\")
                                    .replace("%", "\\%")
                                    .replace("_", "\\_")
                            + "%");
        }
        sql.append(" ORDER BY t.name LIMIT ").append(scanCap);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Tenants with a comp grant on record, soonest expiry first. */
    public List<Map<String, Object>> compGrantRows(int cap) {
        return jdbc.queryForList(
                "SELECT t.id, t.name, t.plan, t.plan_before_comp_grant,"
                        + " t.comp_plan_granted_at, t.comp_plan_expires_at,"
                        + " t.comp_plan_reason "
                        + LIVE_TENANTS
                        + " AND t.comp_plan_expires_at IS NOT NULL"
                        + " ORDER BY t.comp_plan_expires_at LIMIT "
                        + cap);
    }

    /** Newest invoices, optionally for one tenant ({@code tenantId} null = all). */
    public List<Map<String, Object>> invoiceRows(UUID tenantId, int cap) {
        String sql =
                "SELECT i.id, i.tenant_id, t.name AS tenant_name, i.invoice_number, i.gateway,"
                        + " i.gateway_payment_id, i.plan, i.period, i.currency, i.amount_minor,"
                        + " i.gst_amount_minor, i.issued_at FROM control_plane.billing_invoice i LEFT"
                        + " JOIN control_plane.tenant t ON t.id = i.tenant_id";
        if (tenantId != null) {
            return jdbc.queryForList(
                    sql
                            + " WHERE i.tenant_id = ? ORDER BY i.issued_at DESC,"
                            + " i.invoice_number DESC LIMIT "
                            + cap,
                    tenantId);
        }
        return jdbc.queryForList(
                sql + " ORDER BY i.issued_at DESC, i.invoice_number DESC LIMIT " + cap);
    }
}
