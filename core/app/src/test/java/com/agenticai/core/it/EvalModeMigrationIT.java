package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V670 on a database that already holds eval data: a tenant schema migrated up to V669, with datasets that held the old
 * default (tool-less) and datasets that ran with tools, and runs of both, then migrated to the latest version.
 *
 * <ul>
 *   <li>a run records the mode it replayed in: {@code FULL} where its dataset ran with tools, {@code QUICK} otherwise
 *       (the best the old data allows);
 *   <li>a dataset that held the old default (FALSE) now pins nothing (NULL) and so follows the new default, while one that
 *       ran with tools keeps pinning the full pipeline;
 *   <li>the column accepts NULL and no longer defaults to FALSE; the mode column only takes FULL or QUICK.
 * </ul>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class EvalModeMigrationIT {

    private static final String SCHEMA = "tenant_it_evalmode";
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID TOOLS_DATASET = UUID.randomUUID();
    private static final UUID TEXT_DATASET = UUID.randomUUID();
    private static final UUID TOOLS_RUN = UUID.randomUUID();
    private static final UUID TEXT_RUN = UUID.randomUUID();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(PlatformMigrations.TENANT)
            .schemas(SCHEMA)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .target(MigrationVersion.fromVersion(target))
            .load();
    }

    @BeforeAll
    static void migrateToTheVersionBeforeAndInsertOldData() {
        dataSource = IntegrationTestContainers.freshDatabase("eval_mode_it");
        jdbc = new JdbcTemplate(dataSource);
        PlatformMigrations.migrateBoot(dataSource); // the control plane, as at boot
        flyway("669").migrate();

        dataset(TOOLS_DATASET, "ran with tools", true);
        dataset(TEXT_DATASET, "never chose", false);
        run(TOOLS_RUN, TOOLS_DATASET);
        run(TEXT_RUN, TEXT_DATASET);

        flyway("latest").migrate();
    }

    private static void dataset(UUID id, String name, boolean tools) {
        jdbc.update("INSERT INTO " + SCHEMA + ".eval_dataset (id, agent_id, name, tool_execution_enabled) VALUES (?, ?, ?, ?)",
            id, AGENT, name, tools);
    }

    private static void run(UUID id, UUID dataset) {
        jdbc.update("INSERT INTO " + SCHEMA + ".eval_run (id, dataset_id, agent_id, tenant_id, requested_by, status, pass_threshold) "
            + "VALUES (?, ?, ?, 'tenant-1', 'user-1', 'SUCCEEDED', 0.7)", id, dataset, AGENT);
    }

    private static Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    @Test
    void aRunIsBackfilledWithTheModeItsDatasetHadWhenTheMigrationRan() {
        assertThat(row("SELECT eval_mode FROM " + SCHEMA + ".eval_run WHERE id = ?", TOOLS_RUN))
            .containsEntry("eval_mode", "FULL");
        assertThat(row("SELECT eval_mode FROM " + SCHEMA + ".eval_run WHERE id = ?", TEXT_RUN))
            .containsEntry("eval_mode", "QUICK");
    }

    @Test
    void aDatasetThatHeldTheOldDefaultNowPinsNothingAndOneThatRanWithToolsStillPinsThem() {
        assertThat(row("SELECT tool_execution_enabled FROM " + SCHEMA + ".eval_dataset WHERE id = ?", TEXT_DATASET))
            .containsEntry("tool_execution_enabled", null);
        assertThat(row("SELECT tool_execution_enabled FROM " + SCHEMA + ".eval_dataset WHERE id = ?", TOOLS_DATASET))
            .containsEntry("tool_execution_enabled", true);
    }

    @Test
    void theDatasetColumnAcceptsNullAndNoLongerDefaultsToFalse() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".eval_dataset (id, agent_id, name) VALUES (?, ?, 'no choice')", id, AGENT);

        assertThat(row("SELECT tool_execution_enabled FROM " + SCHEMA + ".eval_dataset WHERE id = ?", id))
            .containsEntry("tool_execution_enabled", null);
        List<String> nullable = jdbc.queryForList(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = 'eval_dataset' "
                + "AND column_name = 'tool_execution_enabled'", String.class, SCHEMA);
        assertThat(nullable).containsExactly("YES");
    }

    @Test
    void aRunInsertedWithoutAModeIsQuickAndOnlyFullOrQuickIsAccepted() {
        UUID id = UUID.randomUUID();
        run(id, TEXT_DATASET);
        assertThat(row("SELECT eval_mode FROM " + SCHEMA + ".eval_run WHERE id = ?", id)).containsEntry("eval_mode", "QUICK");

        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".eval_run SET eval_mode = 'TURBO' WHERE id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE " + SCHEMA + ".eval_run SET eval_mode = 'FULL' WHERE id = ?", id);
    }
}
