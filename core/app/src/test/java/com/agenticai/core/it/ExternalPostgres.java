package com.agenticai.core.it;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A Postgres 16 with pgvector AND Apache AGE that the test did not start itself, for ITs that need
 * real transactions and row locks but not a Spring context (so no Testcontainers/Docker).
 *
 * <p>Address: system property {@value #URL_PROPERTY} or env {@code VEGADUTA_IT_PG_URL} (JDBC URL of
 * any database on the server, e.g. {@code jdbc:postgresql://pg-host:5432/postgres}); user/password
 * from {@value #USER_PROPERTY} / {@value #PASSWORD_PROPERTY} (env {@code VEGADUTA_IT_PG_USER} /
 * {@code VEGADUTA_IT_PG_PASSWORD}). Nothing set, or the server unreachable, or it lacks the two
 * extensions: {@link #requireOrSkip()} skips the test (JUnit assumption), it never fails it.
 */
final class ExternalPostgres {

    static final String URL_PROPERTY = "vegaduta.it.pg.url";
    static final String USER_PROPERTY = "vegaduta.it.pg.user";
    static final String PASSWORD_PROPERTY = "vegaduta.it.pg.password";

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private ExternalPostgres() {
    }

    private static String setting(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String baseUrl() {
        return setting(URL_PROPERTY, "VEGADUTA_IT_PG_URL", "");
    }

    private static String user() {
        return setting(USER_PROPERTY, "VEGADUTA_IT_PG_USER", "agentic");
    }

    private static String password() {
        return setting(PASSWORD_PROPERTY, "VEGADUTA_IT_PG_PASSWORD", "");
    }

    /** Skips the calling test class unless the configured server is reachable and has vector + age. */
    static void requireOrSkip() {
        assumeTrue(!baseUrl().isEmpty(), "no external Postgres configured (-D" + URL_PROPERTY + "=jdbc:postgresql://...)");
        try (Connection c = DriverManager.getConnection(baseUrl(), user(), password());
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT count(*) FROM pg_available_extensions WHERE name IN ('vector', 'age')")) {
            rs.next();
            assumeTrue(rs.getInt(1) == 2, "Postgres at " + baseUrl() + " lacks pgvector and/or Apache AGE");
        } catch (SQLException e) {
            assumeTrue(false, "Postgres at " + baseUrl() + " is not reachable: " + e.getMessage());
        }
    }

    /**
     * Drops and recreates database {@code name}, installs vector and age into {@code public} (as the
     * deploy/postgres image and the boot migrations do), and returns a DataSource for it whose
     * connections default to {@code schema} on the search_path (like the tenant connection provider).
     */
    static DataSource freshDatabase(String name, String schema) {
        if (!SAFE_NAME.matcher(name).matches() || !SAFE_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe name");
        }
        try (Connection admin = DriverManager.getConnection(baseUrl(), user(), password());
             Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create " + name, e);
        }
        DriverManagerDataSource plain = dataSourceFor(name, null);
        try (Connection c = plain.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector SCHEMA public");
            s.execute("CREATE EXTENSION IF NOT EXISTS age");
        } catch (SQLException e) {
            throw new IllegalStateException("could not install extensions in " + name, e);
        }
        return dataSourceFor(name, schema);
    }

    /**
     * A fresh, empty database with pgvector and AGE installed and NO schema on the search path, for a full
     * application boot (Flyway creates the control plane and the tenant schemas itself). Returns its data source so the
     * caller can hand the URL and credentials to Spring.
     */
    static DriverManagerDataSource bootDatabase(String name) {
        freshDatabase(name, "public");
        return dataSourceFor(name, null);
    }

    static DriverManagerDataSource dataSourceFor(String database, String schemaOrNull) {
        String url = baseUrl();
        int slash = url.indexOf('/', "jdbc:postgresql://".length());
        int query = url.indexOf('?', slash);
        String prefix = url.substring(0, slash);
        String suffix = query < 0 ? "" : url.substring(query + 1);
        StringBuilder full = new StringBuilder(prefix).append('/').append(database).append("?options=-c%20TimeZone%3DUTC");
        if (schemaOrNull != null) {
            full.append("&currentSchema=").append(schemaOrNull).append(",public");
        }
        if (!suffix.isEmpty()) {
            full.append('&').append(suffix);
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(full.toString(), user(), password());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    /** Flyway for the tenant migrations only, optionally stopped at {@code targetVersion} (null = latest). */
    static org.flywaydb.core.Flyway tenantFlyway(DataSource dataSource, String schema, String targetVersion) {
        var cfg = org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .locations(PlatformMigrations.TENANT)
            .schemas(schema)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false);
        if (targetVersion != null) {
            cfg.target(org.flywaydb.core.api.MigrationVersion.fromVersion(targetVersion));
        }
        return cfg.load();
    }

    /** Inserts a minimal agent row (agent_memory.agent_id references it) and returns its id. */
    static java.util.UUID insertAgent(org.springframework.jdbc.core.JdbcTemplate jdbc, String name) {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
            + " knowledge_base_enabled, created_at, updated_at) VALUES (?, ?, 'it', 'it', 'it', 16, 0.1, false, now(), now())",
            id, name);
        return id;
    }
}
