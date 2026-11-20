package com.agenticai.core.agents.spendcap;

import com.agenticai.core.agents.spendcap.SpendCapService.TenantCap;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code control_plane.tenant} spend-cap columns behind {@link SpendCapService}: all SQL for
 * reading and writing a tenant's monthly cap and alert-stage claim lives here.
 */
@Repository
public class SpendCapJdbcRepository {

    static final String SQL_BY_SCHEMA =
            "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
                    + "spend_cap_alert_month FROM control_plane.tenant WHERE schema_name = ?";
    static final String SQL_BY_ID =
            "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
                    + "spend_cap_alert_month FROM control_plane.tenant WHERE id = ?";
    static final String SQL_ALL_CAPPED =
            "SELECT id, schema_name, name, spend_cap_usd, spend_cap_alert_stage, "
                    + "spend_cap_alert_month FROM control_plane.tenant WHERE spend_cap_usd IS NOT NULL";
    static final String SQL_SET_CAP =
            "UPDATE control_plane.tenant SET spend_cap_usd = ?, spend_cap_alert_stage = 0, "
                    + "spend_cap_alert_month = NULL WHERE id = ?";
    static final String SQL_CLAIM_STAGE =
            "UPDATE control_plane.tenant SET spend_cap_alert_stage = ?, "
                    + "spend_cap_alert_month = ? WHERE id = ? AND spend_cap_usd IS NOT NULL "
                    + "AND (spend_cap_alert_month IS DISTINCT FROM ? OR spend_cap_alert_stage < ?)";

    private final JdbcTemplate jdbc;

    public SpendCapJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TenantCap> findBySchema(String schema) {
        return jdbc.query(SQL_BY_SCHEMA, (rs, n) -> map(rs), schema).stream().findFirst();
    }

    Optional<TenantCap> findById(UUID tenantId) {
        return jdbc.query(SQL_BY_ID, (rs, n) -> map(rs), tenantId).stream().findFirst();
    }

    List<TenantCap> findAllCapped() {
        return jdbc.query(SQL_ALL_CAPPED, (rs, n) -> map(rs));
    }

    void setCap(BigDecimal normalizedCapUsd, UUID tenantId) {
        jdbc.update(SQL_SET_CAP, normalizedCapUsd, tenantId);
    }

    /** Rows updated: 1 when this caller won the claim for {@code stage} in {@code month}. */
    int claimAlertStage(int stage, LocalDate month, UUID tenantId) {
        return jdbc.update(
                SQL_CLAIM_STAGE, stage, Date.valueOf(month), tenantId, Date.valueOf(month), stage);
    }

    private static TenantCap map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date month = rs.getDate("spend_cap_alert_month");
        return new TenantCap(
                rs.getObject("id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("name"),
                rs.getBigDecimal("spend_cap_usd"),
                rs.getInt("spend_cap_alert_stage"),
                month == null ? null : month.toLocalDate());
    }
}
