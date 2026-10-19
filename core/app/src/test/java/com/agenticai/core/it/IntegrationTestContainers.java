package com.agenticai.core.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton containers for the integration suite. Started lazily on
 * first use and never stopped explicitly: Testcontainers' Ryuk sidecar removes
 * them (and an on-the-fly built image) when the test JVM exits. One Postgres
 * serves every IT class; classes that need a pristine database call
 * {@link #freshDatabase(String)} instead of sharing the Spring context's one.
 *
 * <p>Postgres must carry pgvector AND Apache AGE (tenant V10/V44 create both
 * extensions), which no public image ships together. By default the image is
 * built from {@code deploy/postgres/Dockerfile} (no COPY instructions, so the
 * directory itself is the build context). CI and repeat local runs should
 * build it once and pass the tag via {@value #POSTGRES_IMAGE_PROPERTY}.
 */
public final class IntegrationTestContainers {

    /** Pre-built pgvector+AGE image tag; unset = build deploy/postgres on the fly. */
    public static final String POSTGRES_IMAGE_PROPERTY = "vegaduta.test.postgres.image";

    /** Redis-protocol image for the Spring context test. */
    public static final String REDIS_IMAGE_PROPERTY = "vegaduta.test.redis.image";

    static final String DEFAULT_REDIS_IMAGE = "valkey/valkey:8-alpine";

    static final String DATABASE = "agentic_ai";
    static final String USERNAME = "agentic";
    // Container-local credential for a throwaway database; not a secret.
    static final String PASSWORD = "it-only-password";

    private static final Pattern SAFE_DB_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    private IntegrationTestContainers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(postgresImage())
                .withDatabaseName(DATABASE)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                // Same preload the compose stack passes (deploy/compose/*.yml);
                // fsync off because the data is disposable.
                .withCommand("postgres", "-c", "shared_preload_libraries=age",
                    "-c", "max_connections=200", "-c", "fsync=off");
            container.start();
            postgres = container;
        }
        return postgres;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            String image = System.getProperty(REDIS_IMAGE_PROPERTY, DEFAULT_REDIS_IMAGE);
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(6379);
            container.start();
            redis = container;
        }
        return redis;
    }

    /**
     * Creates an empty database on the shared container and returns a
     * connection-per-call DataSource for it, so a class can run migrations
     * "from empty" without seeing what the Spring context test applied.
     */
    public static DataSource freshDatabase(String name) {
        if (!SAFE_DB_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("unsafe database name: " + name);
        }
        PostgreSQLContainer<?> pg = postgres();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), USERNAME, PASSWORD);
             Statement stmt = admin.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + name);
            stmt.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + name, e);
        }
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/" + name + "?options=-c%20TimeZone%3DUTC";
        DriverManagerDataSource ds = new DriverManagerDataSource(url, USERNAME, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    private static DockerImageName postgresImage() {
        String configured = System.getProperty(POSTGRES_IMAGE_PROPERTY, "").trim();
        String image;
        if (!configured.isEmpty()) {
            image = configured;
        } else {
            Path context = locatePostgresBuildContext();
            // deleteOnExit=true: Ryuk removes the built tag when the JVM exits,
            // so local runs do not accumulate ~700 MB images.
            image = new ImageFromDockerfile("vegaduta-it-postgres", true)
                .withFileFromPath(".", context)
                .get();
        }
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }

    /** Walks up from the working directory (core/app under Maven) to deploy/postgres. */
    private static Path locatePostgresBuildContext() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deploy").resolve("postgres");
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "deploy/postgres/Dockerfile not found above " + Paths.get("").toAbsolutePath()
                + " - pass -D" + POSTGRES_IMAGE_PROPERTY + "=<pgvector+AGE image> instead");
    }
}
