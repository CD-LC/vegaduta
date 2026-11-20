package com.agenticai.core.billing.resolution;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw read of {@code control_plane.platform_setting} for {@link ResolutionPricingService} (plain
 * JDBC rather than {@code PlatformSettingsService} - see that class's javadoc).
 */
@Repository
public class PlatformSettingJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlatformSettingJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The setting's value, or null when the key has no row. */
    public String readSetting(String key) {
        List<String> rows =
                jdbcTemplate.query(
                        "SELECT value FROM control_plane.platform_setting WHERE key = ?",
                        (rs, rowNum) -> rs.getString(1),
                        key);
        return rows.stream().findFirst().orElse(null);
    }
}
