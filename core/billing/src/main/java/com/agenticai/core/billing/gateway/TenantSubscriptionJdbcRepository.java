package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code control_plane.tenant} subscription columns for the billing event
 * handlers. JdbcTemplate rather than the Tenant entity to avoid a cycle dependency (tenancy -&gt;
 * billing -&gt; tenancy). Exceptions propagate unchanged ({@code EmptyResultDataAccessException}
 * for a missing row) - the handlers distinguish "no such tenant" from a database failure.
 */
@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class TenantSubscriptionJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantSubscriptionJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The tenant id (as text) owning a gateway subscription id. */
    public String findTenantIdByGatewaySubscriptionId(String gatewaySubscriptionId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM control_plane.tenant WHERE gateway_subscription_id = ?",
                String.class,
                gatewaySubscriptionId);
    }

    /** {@code name}, {@code plan} and {@code subscription_status} of one tenant. */
    public Map<String, Object> findNamePlanStatus(String tenantIdStr) {
        return jdbcTemplate.queryForMap(
                "SELECT name, plan, subscription_status FROM control_plane.tenant WHERE id = ?::uuid",
                tenantIdStr);
    }

    public String findName(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM control_plane.tenant WHERE id = ?", String.class, tenantId);
    }

    /** Sets the status and stamps {@code past_due_since} when it is not already set. */
    public void markPending(UUID tenantId, String newStatus, Instant now) {
        jdbcTemplate.update(
                "UPDATE control_plane.tenant SET subscription_status = ?, past_due_since = CASE WHEN past_due_since IS NULL THEN ? ELSE past_due_since END WHERE id = ?",
                newStatus,
                now,
                tenantId);
    }

    /** Idempotent move to ACTIVE; returns the number of rows changed. */
    public int markActive(UUID tenantId) {
        return jdbcTemplate.update(
                "UPDATE control_plane.tenant SET subscription_status = ? WHERE id = ? AND (subscription_status = ? OR subscription_status != ?)",
                "ACTIVE",
                tenantId,
                "PENDING",
                "ACTIVE");
    }
}
