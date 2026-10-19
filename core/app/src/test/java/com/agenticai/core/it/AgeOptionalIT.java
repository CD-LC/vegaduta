package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.tenancy.AgeTolerantFlywayMigrationStrategy;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Apache AGE is optional: every migration applies against a server that has pgvector but NOT the
 * {@code age} extension (what AWS RDS, Cloud SQL and AlloyDB offer). V44 (graph memory) is recorded
 * as applied without running, both for the startup run and for a tenant schema, and everything else
 * migrates normally.
 *
 * <p>Uses its own container from a pgvector-only image ({@code vegaduta.test.pgvector.image},
 * default {@code pgvector/pgvector:pg16}), not the shared pgvector+AGE one the rest of the suite
 * uses.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class AgeOptionalIT {

    static final String PGVECTOR_IMAGE_PROPERTY = "vegaduta.test.pgvector.image";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startServerWithoutAge() {
        String image = System.getProperty(PGVECTOR_IMAGE_PROPERTY, "pgvector/pgvector:pg16");
        postgres =
                new PostgreSQLContainer<>(DockerImageName.parse(image))
                        .withDatabaseName("age_optional_it")
                        .withUsername("agentic")
                        .withPassword("it-only-password")
                        .withCommand("postgres", "-c", "max_connections=100", "-c", "fsync=off");
        postgres.start();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static DataSource dataSource() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl() + "&options=-c%20TimeZone%3DUTC",
                        postgres.getUsername(),
                        postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @Test
    void theServerReallyHasNoAge() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM pg_available_extensions WHERE name IN ('age',"
                                        + " 'vector')",
                                String.class))
                .containsExactly("vector");
    }

    /**
     * Control: without the tolerance, V44 stops plain Flyway on this server (why the feature
     * exists).
     */
    @Test
    void plainFlywayStopsAtV44OnAServerWithoutAge() {
        DataSource ds =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl().replace("age_optional_it", "postgres"),
                        postgres.getUsername(),
                        postgres.getPassword());
        JdbcTemplate admin = new JdbcTemplate(ds);
        admin.execute("DROP DATABASE IF EXISTS age_control_it");
        admin.execute("CREATE DATABASE age_control_it");
        DataSource control =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl().replace("age_optional_it", "age_control_it"),
                        postgres.getUsername(),
                        postgres.getPassword());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PlatformMigrations.migrateBoot(control))
                .hasMessageContaining("age");
    }

    @Test
    void startupMigrationAppliesWithGraphMemorySkipped() {
        DataSource ds = dataSource();
        Flyway flyway = PlatformMigrations.bootFlyway(ds);

        new AgeTolerantFlywayMigrationStrategy(new TenantSchemaMigrator(ds)).migrate(flyway);

        assertThat(flyway.info().pending()).as("nothing left pending").isEmpty();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version = '44'"
                                        + " AND success",
                                Integer.class))
                .as("V44 recorded as applied")
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
                .contains("vector")
                .doesNotContain("age");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema"
                                        + " = 'control_plane' AND table_name = 'tenant'",
                                Integer.class))
                .as("the rest of the platform schema exists")
                .isEqualTo(1);
    }

    @Test
    void aTenantSchemaMigratesWithGraphMemorySkipped() {
        DataSource ds = dataSource();
        String schema = "tenant_age_optional_it";

        new TenantSchemaMigrator(ds).migrate(schema);

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        List<String> applied =
                jdbc.queryForList(
                        "SELECT version FROM \""
                                + schema
                                + "\".flyway_schema_history WHERE success AND version IS NOT NULL",
                        String.class);
        assertThat(applied).contains("44");
        assertThat(PlatformMigrations.strictTenantFlyway(ds, schema).info().pending())
                .as("nothing left pending in the tenant schema")
                .isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema"
                                        + " = '"
                                        + schema
                                        + "' AND table_name = 'agent'",
                                Integer.class))
                .as("ordinary tenant tables exist")
                .isEqualTo(1);
    }

    /**
     * Replicas booting at once on a fresh server without AGE: Flyway's advisory lock serialises
     * migrate(), but the skip handling runs outside it. Every replica must come up and V44 is
     * recorded once.
     */
    @Test
    void concurrentStartupsOnAServerWithoutAgeAllSucceed() throws Exception {
        DataSource admin =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl().replace("age_optional_it", "postgres"),
                        postgres.getUsername(),
                        postgres.getPassword());
        for (int round = 0; round < 4; round++) {
            String db = "age_race_it_" + round;
            new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + db);
            new JdbcTemplate(admin).execute("CREATE DATABASE " + db);
            DataSource ds =
                    new DriverManagerDataSource(
                            postgres.getJdbcUrl().replace("age_optional_it", db),
                            postgres.getUsername(),
                            postgres.getPassword());
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(3);
            List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
            for (int replica = 0; replica < 3; replica++) {
                runs.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    new AgeTolerantFlywayMigrationStrategy(
                                                    new TenantSchemaMigrator(ds))
                                            .migrate(PlatformMigrations.bootFlyway(ds));
                                    return null;
                                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> run : runs) {
                run.get(5, java.util.concurrent.TimeUnit.MINUTES);
            }
            pool.shutdownNow();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM flyway_schema_history WHERE version ="
                                            + " '44' AND success",
                                    Integer.class))
                    .as("V44 recorded exactly once, round " + round)
                    .isEqualTo(1);
            assertThat(PlatformMigrations.bootFlyway(ds).info().pending()).isEmpty();
        }
    }
}
