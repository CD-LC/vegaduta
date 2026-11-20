package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The raw control-plane SQL behind {@link NewsletterService}. */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
public class NewsletterJdbcRepository {

    private static final String RECIPIENTS_SQL =
            "SELECT t.id AS tenant_id, t.name AS tenant_name, t.news_opt_in AS news_opt_in,"
                    + " au.email AS admin_email FROM control_plane.tenant t LEFT JOIN LATERAL (  "
                    + " SELECT email FROM control_plane.app_user   WHERE tenant_id = t.id AND role ="
                    + " 'TENANT_ADMIN'   ORDER BY created_at ASC LIMIT 1 ) au ON true WHERE t.status ="
                    + " 'ACTIVE' ORDER BY t.name";

    private static final String INSERT_CAMPAIGN_SQL =
            "INSERT INTO control_plane.newsletter_campaign (id, subject, sent_count, created_at,"
                    + " created_by) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public NewsletterJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Every active tenant with its oldest TENANT_ADMIN email, opted-in or not. */
    public List<NewsletterRecipient> listRecipients() {
        return jdbcTemplate.query(
                RECIPIENTS_SQL,
                (rs, rowNum) ->
                        new NewsletterRecipient(
                                UUID.fromString(rs.getString("tenant_id")),
                                rs.getString("tenant_name"),
                                rs.getString("admin_email"),
                                rs.getBoolean("news_opt_in")));
    }

    public void insertCampaign(String subject, int sentCount, String createdBy) {
        jdbcTemplate.update(
                INSERT_CAMPAIGN_SQL,
                UUID.randomUUID(),
                subject,
                sentCount,
                Timestamp.from(Instant.now()),
                createdBy);
    }
}
