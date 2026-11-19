package com.agenticai.core.platform.retention;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Executes the schema-qualified count / batched-delete statements {@link DataRetentionPurge} builds
 * (see {@code DataRetentionPurge.Target}); each statement binds the single cutoff timestamp.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
public class DataRetentionJdbcRepository {

    private final JdbcTemplate jdbc;

    public DataRetentionJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Bounded count of eligible rows ({@code sql} is a {@code count(*)} statement). */
    public int countEligible(String sql, Timestamp cutoff) {
        Integer c = jdbc.queryForObject(sql, Integer.class, cutoff);
        return c == null ? 0 : c;
    }

    /** Runs one batched DELETE; returns the rows removed. */
    public int deleteBatch(String sql, Timestamp cutoff) {
        return jdbc.update(sql, cutoff);
    }
}
