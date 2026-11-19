package com.agenticai.core.billing.email;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC lookups {@link BillingEmailService} needs to address a billing email: the tenant's admin
 * recipients and its current plan. Exceptions propagate unchanged; the service decides the
 * fallback for each.
 */
@Repository
public class BillingRecipientJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public BillingRecipientJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findAdminEmails(UUID tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT email FROM control_plane.app_user WHERE tenant_id = ? AND role"
                        + " = ? AND email IS NOT NULL",
                String.class,
                tenantId,
                "TENANT_ADMIN");
    }

    /** The first TENANT_ADMIN user's email for this tenant (throws when there is none). */
    public String findFirstAdminEmail(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM control_plane.app_user WHERE tenant_id = ? AND role = ?"
                        + " ORDER BY created_at ASC LIMIT 1",
                String.class,
                tenantId,
                "TENANT_ADMIN");
    }

    /** The tenant's current plan (PREMIUM, UNLIMITED, FREE, etc.). */
    public String findPlan(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT plan FROM control_plane.tenant WHERE id = ?", String.class, tenantId);
    }
}
