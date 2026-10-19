package com.agenticai.core.chat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * One cheap, bounded query at startup (after the tenant migrations ran): which ACTIVE tenant
 * schemas have no {@code llm_spend_month} (V481)? The budget check tolerates the gap by falling
 * back to the SUM, but that is slower and means a tenant migration failed or has not reached that
 * schema, so it is reported at ERROR and as the {@value #MISSING_GAUGE} gauge for alerting. Never
 * blocks or fails startup.
 */
@Component
public class LlmSpendCounterSchemaCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmSpendCounterSchemaCheck.class);

    /** Number of active tenant schemas lacking {@code llm_spend_month} at the last check. */
    public static final String MISSING_GAUGE = "llm.spend.counter.missing.schemas";

    /** Listing is capped so a platform-wide failure cannot produce an unbounded result or log. */
    static final int MAX_LISTED = 50;

    private static final String SQL =
            "SELECT t.schema_name FROM control_plane.tenant t WHERE t.status = 'ACTIVE' AND NOT"
                    + " EXISTS (SELECT 1 FROM information_schema.tables i WHERE i.table_schema ="
                    + " t.schema_name AND i.table_name = 'llm_spend_month') ORDER BY t.schema_name"
                    + " LIMIT "
                    + MAX_LISTED;

    private final DataSource dataSource;
    private final AtomicInteger missing = new AtomicInteger();

    public LlmSpendCounterSchemaCheck(DataSource dataSource, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        Gauge.builder(MISSING_GAUGE, missing, AtomicInteger::get).register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        check();
    }

    /** Runs the check; returns the schema names (up to {@value #MAX_LISTED}) lacking the table. */
    public List<String> check() {
        List<String> schemas = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setQueryTimeout(10);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.warn("llm_spend_month schema check skipped - {}", e.getMessage());
            return List.of();
        }
        missing.set(schemas.size());
        if (!schemas.isEmpty()) {
            log.error(
                    "{} active tenant schema(s) have no llm_spend_month table (V481 not applied):"
                            + " {} - the BYOK budget check falls back to SUM(llm_call_log) for"
                            + " them; fix the failed tenant migration",
                    schemas.size(),
                    schemas);
        }
        return schemas;
    }
}
