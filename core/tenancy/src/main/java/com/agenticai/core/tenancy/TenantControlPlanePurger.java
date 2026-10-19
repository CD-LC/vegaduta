package com.agenticai.core.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Erasure strategy for control-plane rows keyed by the tenant's schema name (which dropping the
 * tenant schema never touches). Extracted unchanged from {@link TenantDataErasureService}; each
 * step is its own auto-committed statement and best-effort.
 */
final class TenantControlPlanePurger {

    private static final Logger log = LoggerFactory.getLogger(TenantControlPlanePurger.class);

    private final DataSource dataSource;

    TenantControlPlanePurger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Erasure completeness (2026-10-09) - control-plane rows keyed by the tenant's SCHEMA NAME
     * (what {@link TenantContext#getTenantId} carries), which dropping the tenant schema never
     * touches. Every entry's key column was checked against its migration comment AND the code that
     * writes it (the {@code TenantContext} tenant id, i.e. the schema name, in each case):
     * checkout_session (V221), commerce_product (V252), agent_directory_entry (V402),
     * ai_content_report (V369), x402_payment/x402_offer (V405, X402Offer#tenantId "seller's tenant
     * SCHEMA NAME"), tenant_sso_config / tenant_scim_token (V253, tenant_schema),
     * channel_account_link (V6), device_pairing_code (V36), user_permission_grant (V199,
     * UserPermissionService), experiment_assignment (V92, FeatureFlagService#evaluateActiveFlags
     * from AgentChatService's TenantContext id), llm_gateway_content_policy (V300,
     * LlmGatewayContentPolicyController#tenantId), community_template_rating (V251,
     * CommunityTemplateService's TenantContext id).
     *
     * <p>Order matters once: x402 payments are removed before offers, because {@code
     * x402_payment.offer_id} references {@code x402_offer(id)} with no cascade - including OTHER
     * tenants' payments made to this tenant's offers.
     *
     * <p>Deliberately NOT here (retention or not applicable): {@code billing_invoice}/{@code
     * billing_webhook_event} (financial records), {@code platform_tenant_deletion_log} (the
     * erasure's own audit trail), {@code community_template} (published work other tenants imported
     * - only its {@code source_tenant_id} provenance is nulled, see {@link
     * #CONTROL_PLANE_UPDATES}), and {@code moderation_review_item}, whose control-plane copy V216
     * dropped (the live queue is tenant-schema, so the schema drop already removes it). {@code
     * app_user} is handled separately because, unlike these best-effort steps, it must succeed.
     */
    private static final List<String[]> CONTROL_PLANE_DELETES =
            List.of(
                    new String[] {
                        "checkout_session",
                        "DELETE FROM control_plane.checkout_session WHERE tenant_id = ?"
                    },
                    new String[] {
                        "commerce_product",
                        "DELETE FROM control_plane.commerce_product WHERE tenant_id = ?"
                    },
                    new String[] {
                        "agent_directory_entry",
                        "DELETE FROM control_plane.agent_directory_entry WHERE tenant_id = ?"
                    },
                    new String[] {
                        "ai_content_report",
                        "DELETE FROM control_plane.ai_content_report WHERE tenant_id = ?"
                    },
                    new String[] {
                        "x402_payment",
                        "DELETE FROM control_plane.x402_payment WHERE payer_tenant_id = ? OR"
                                + " offer_id IN (SELECT id FROM control_plane.x402_offer WHERE"
                                + " tenant_id = ?)"
                    },
                    new String[] {
                        "x402_offer", "DELETE FROM control_plane.x402_offer WHERE tenant_id = ?"
                    },
                    new String[] {
                        "tenant_sso_config",
                        "DELETE FROM control_plane.tenant_sso_config WHERE tenant_schema = ?"
                    },
                    new String[] {
                        "tenant_scim_token",
                        "DELETE FROM control_plane.tenant_scim_token WHERE tenant_schema = ?"
                    },
                    new String[] {
                        "channel_account_link",
                        "DELETE FROM control_plane.channel_account_link WHERE tenant_schema = ?"
                    },
                    new String[] {
                        "device_pairing_code",
                        "DELETE FROM control_plane.device_pairing_code WHERE tenant_schema = ?"
                    },
                    new String[] {
                        "user_permission_grant",
                        "DELETE FROM control_plane.user_permission_grant WHERE tenant_id = ?"
                    },
                    new String[] {
                        "experiment_assignment",
                        "DELETE FROM control_plane.experiment_assignment WHERE tenant_id = ?"
                    },
                    new String[] {
                        "llm_gateway_content_policy",
                        "DELETE FROM control_plane.llm_gateway_content_policy WHERE tenant_id = ?"
                    },
                    // Removing a rating must also correct the template's denormalised
                    // rating_count/rating_avg. A data-modifying CTE's outer query sees the
                    // PRE-delete snapshot, hence the explicit IS DISTINCT FROM exclusion.
                    new String[] {
                        "community_template_rating",
                        "WITH removed AS (DELETE FROM control_plane.community_template_rating WHERE"
                            + " rater_tenant_id = ? RETURNING template_id) UPDATE"
                            + " control_plane.community_template t SET rating_count = agg.cnt,"
                            + " rating_avg = agg.avg FROM (SELECT tpl.id, COUNT(r.id) AS cnt,"
                            + " COALESCE(AVG(r.stars), 0) AS avg FROM"
                            + " control_plane.community_template tpl LEFT JOIN"
                            + " control_plane.community_template_rating r ON r.template_id = tpl.id"
                            + " AND r.rater_tenant_id IS DISTINCT FROM ? WHERE tpl.id IN (SELECT"
                            + " template_id FROM removed) GROUP BY tpl.id) agg WHERE t.id = agg.id"
                    });

    /** Provenance scrub rather than delete - see {@link #CONTROL_PLANE_DELETES}' javadoc. */
    private static final List<String[]> CONTROL_PLANE_UPDATES =
            List.<String[]>of(
                    new String[] {
                        "community_template.source_tenant_id",
                        "UPDATE control_plane.community_template SET source_tenant_id = NULL WHERE"
                                + " source_tenant_id = ?"
                    });

    /**
     * Runs {@link #CONTROL_PLANE_DELETES} then {@link #CONTROL_PLANE_UPDATES} for this tenant's
     * schema name, each as its own auto-committed statement, so one failing table (missing on an
     * older database, a lock timeout) never blocks the rest. Returns the labels of the steps that
     * failed.
     */
    List<String> purgeControlPlaneRows(Tenant tenant, String schemaName) {
        List<String> failures = new ArrayList<>();
        List<String[]> steps = new ArrayList<>(CONTROL_PLANE_DELETES);
        steps.addAll(CONTROL_PLANE_UPDATES);
        for (String[] step : steps) {
            String label = step[0];
            String sql = step[1];
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                int placeholders = (int) sql.chars().filter(c -> c == '?').count();
                for (int i = 1; i <= placeholders; i++) {
                    statement.setString(i, schemaName);
                }
                int rows = statement.executeUpdate();
                if (rows > 0) {
                    log.info(
                            "Erasure: {} row(s) removed/updated in control_plane.{} for tenant"
                                    + " '{}'",
                            rows,
                            label,
                            tenant.getName());
                }
            } catch (SQLException | RuntimeException e) {
                failures.add(label);
                log.error(
                        "Erasure: control-plane cleanup of {} failed for tenant '{}' (schema '{}')"
                                + " - continuing",
                        label,
                        tenant.getName(),
                        schemaName,
                        e);
            }
        }
        return failures;
    }
}
