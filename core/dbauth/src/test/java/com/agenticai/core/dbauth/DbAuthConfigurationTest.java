package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs with Spring Boot's {@code DataSourceAutoConfiguration} loaded, as in core, so Boot's own
 * pool and its own {@code JdbcConnectionDetails} are part of every context. Flyway and JPA are not
 * loaded here: they inject "the" {@code DataSource} by type, so what is proven for them is that
 * exactly one such bean exists and it is the token pool.
 */
class DbAuthConfigurationTest {

    private static final String SSL_URL =
            "jdbc:postgresql://db.example.internal:5432/app?sslmode=require";

    /** Counts tokens handed out; every call is a new token. */
    static final class FakeSupplier implements DbTokenSupplier {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override
        public String password() {
            if (fail) {
                throw new DbAuthException("no credentials", null);
            }
            return "token-" + calls.incrementAndGet();
        }
    }

    /** Records the (url, user, password) of each physical connection the pool opens. */
    static final class RecordingFactory implements TokenDataSource.ConnectionFactory {
        final List<String> opened = new ArrayList<>();

        @Override
        public synchronized Connection open(String url, Properties info) {
            opened.add(url + "|" + info.getProperty("user") + "|" + info.getProperty("password"));
            return mock(Connection.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingOnly {
        @Bean
        RecordingFactory recordingFactory() {
            return new RecordingFactory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        FakeSupplier fakeSupplier() {
            return new FakeSupplier();
        }

        @Bean
        RecordingFactory recordingFactory() {
            return new RecordingFactory();
        }
    }

    /** What Testcontainers' @ServiceConnection (or Docker Compose support) contributes. */
    @Configuration(proxyBeanMethods = false)
    static class SuppliesItsOwnLogin {
        @Bean
        JdbcConnectionDetails containerConnectionDetails() {
            return new JdbcConnectionDetails() {
                @Override
                public String getJdbcUrl() {
                    return "jdbc:postgresql://container:5432/test";
                }

                @Override
                public String getUsername() {
                    return "test";
                }

                @Override
                public String getPassword() {
                    return "test";
                }
            };
        }
    }

    private final ApplicationContextRunner base =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://h:5432/app",
                            "spring.datasource.username=agentic");

    private final ApplicationContextRunner runner =
            base.withUserConfiguration(DbAuthConfiguration.class, Fakes.class);

    @BeforeEach
    void awsTestCredentials() {
        System.setProperty("aws.accessKeyId", "unit-test-access-key");
        System.setProperty("aws.secretAccessKey", "unit-test-secret");
    }

    @AfterEach
    void clearAwsTestCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void passwordModeLeavesSpringBootsOwnDataSourceAlone() {
        runner.withPropertyValues("spring.datasource.password=pw")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            // Exactly one DataSource, and it is Boot's own plain pool: no
                            // TokenDataSource wrapper.
                            assertThat(ctx).hasSingleBean(DataSource.class);
                            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
                            assertThat(pool.getDataSource()).isNull();
                            assertThat(pool.getPassword()).isEqualTo("pw");
                        });
    }

    /**
     * The regression this guards: Boot registers PropertiesJdbcConnectionDetails in exactly the
     * password-mode case, so a check that skips "when any JdbcConnectionDetails exists" never fires
     * in a real boot.
     */
    @Test
    void passwordModeWithoutAPasswordFailsStartupInTheRealWiring() {
        runner.withPropertyValues("spring.datasource.password=pw")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBeansOfType(JdbcConnectionDetails.class).values())
                                    .as("Boot's own properties-backed details are in the context")
                                    .singleElement()
                                    .satisfies(
                                            d ->
                                                    assertThat(d.getClass().getSimpleName())
                                                            .isEqualTo(
                                                                    DbAuthConfiguration
                                                                            .DbAuthStartupCheck
                                                                            .PROPERTIES_BACKED));
                        });
        // unset, empty and whitespace-only
        runner.run(
                ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasStackTraceContaining("POSTGRES_PASSWORD is required"));
        runner.withPropertyValues("spring.datasource.password=")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .getFailure()
                                        .hasStackTraceContaining("POSTGRES_PASSWORD is required"));
        runner.withPropertyValues("app.datasource.auth=password", "spring.datasource.password=  ")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .getFailure()
                                        .hasStackTraceContaining("POSTGRES_PASSWORD is required"));
    }

    @Test
    void aJdbcConnectionDetailsBeanThatSuppliesTheLoginExemptsTheCheck() {
        runner.withUserConfiguration(SuppliesItsOwnLogin.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx.getBean(JdbcConnectionDetails.class).getUsername())
                                    .isEqualTo("test");
                        });
    }

    @Test
    void unknownModeFailsStartup() {
        runner.withPropertyValues("app.datasource.auth=iam")
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .getFailure()
                                        .hasStackTraceContaining("Unknown app.datasource.auth"));
    }

    @Test
    void tokenModeWithoutTlsFailsFast() {
        runner.withPropertyValues("app.datasource.auth=aws-iam")
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("requires TLS"));
        // pgjdbc ignores an upper-case parameter name, so it is not TLS.
        runner.withPropertyValues(
                        "app.datasource.auth=aws-iam",
                        "spring.datasource.url=jdbc:postgresql://h:5432/app?SSLMODE=require")
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("requires TLS"));
    }

    @Test
    void tokenModeNeedsADatabaseRole() {
        runner.withPropertyValues(
                        "app.datasource.auth=azure-entra",
                        "spring.datasource.url=" + SSL_URL,
                        "spring.datasource.username=")
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("POSTGRES_USER"));
    }

    @Test
    void tokenModeReplacesSpringBootsDataSourceWithTheTokenPool() {
        runner.withPropertyValues(
                        "app.datasource.auth=gcp-iam",
                        "spring.datasource.url=" + SSL_URL,
                        "spring.datasource.hikari.minimum-idle=0")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            // Boot's DataSourceAutoConfiguration is loaded and backed off: the
                            // only DataSource (what Flyway, the tenant migrator and JPA get) is
                            // ours.
                            assertThat(ctx).hasSingleBean(DataSource.class);
                            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
                            assertThat(pool.getDataSource()).isInstanceOf(TokenDataSource.class);
                        });
    }

    @Test
    void everyNewPhysicalConnectionGetsAFreshTokenAndIdleReuseDoesNot() {
        runner.withPropertyValues(
                        "app.datasource.auth=gcp-iam",
                        "spring.datasource.username=sa-name@proj.iam",
                        "spring.datasource.url=" + SSL_URL,
                        "spring.datasource.password=ignored",
                        "spring.datasource.hikari.minimum-idle=0",
                        "spring.datasource.hikari.maximum-pool-size=3")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
                            RecordingFactory factory = ctx.getBean(RecordingFactory.class);

                            try (Connection a = pool.getConnection();
                                    Connection b = pool.getConnection()) {
                                assertThat(a).isNotNull();
                                assertThat(b).isNotNull();
                            }
                            // Hikari's start-up probe opened one connection too; every physical
                            // connection has its own token.
                            assertThat(factory.opened).hasSizeGreaterThanOrEqualTo(2);
                            assertThat(factory.opened)
                                    .allMatch(
                                            o ->
                                                    o.startsWith(
                                                            SSL_URL + "|sa-name@proj.iam|token-"));
                            assertThat(factory.opened).doesNotHaveDuplicates();
                            assertThat(factory.opened)
                                    .hasSize(ctx.getBean(FakeSupplier.class).calls.get());

                            int before = ctx.getBean(FakeSupplier.class).calls.get();
                            try (Connection c = pool.getConnection()) {
                                assertThat(c).isNotNull();
                            }
                            assertThat(ctx.getBean(FakeSupplier.class).calls.get())
                                    .as("idle connection reused")
                                    .isEqualTo(before);
                        });
    }

    @Test
    void supplierFailureSurfacesAsSqlExceptionFromThePool() {
        runner.withPropertyValues(
                        "app.datasource.auth=aws-iam",
                        "spring.datasource.url=" + SSL_URL,
                        "spring.datasource.hikari.minimum-idle=0",
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "spring.datasource.hikari.connection-timeout=1000")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            ctx.getBean(FakeSupplier.class).fail = true;
                            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
                            SQLException thrown = null;
                            try (Connection c = pool.getConnection()) {
                                org.junit.jupiter.api.Assertions.fail("expected SQLException");
                            } catch (SQLException e) {
                                thrown = e;
                            }
                            // Hikari wraps the last failure of the factory: the 28000 login error
                            // built from the token supplier's DbAuthException is its root.
                            Throwable root = thrown;
                            while (root.getCause() != null) {
                                root = root.getCause();
                            }
                            assertThat(thrown.getMessage()).contains("Connection is not available");
                            assertThat(root).isInstanceOf(DbAuthException.class);
                            assertThat(ctx.getBean(RecordingFactory.class).opened).isEmpty();
                        });
    }

    /** No fake supplier: the real CachingDbTokenSupplier over the real AWS signer. */
    @Test
    void theDefaultAwsTokenPathSignsOnceAndSharesTheCachedTokenAcrossConnections() {
        base.withUserConfiguration(DbAuthConfiguration.class, RecordingOnly.class)
                .withPropertyValues(
                        "app.datasource.auth=aws-iam",
                        "app.datasource.aws.region=eu-west-1",
                        "spring.datasource.url="
                                + "jdbc:postgresql://mydb.abc123.eu-west-1.rds.amazonaws.com:5432/app"
                                + "?sslmode=require",
                        "spring.datasource.hikari.minimum-idle=0",
                        "spring.datasource.hikari.maximum-pool-size=3")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
                            try (Connection a = pool.getConnection();
                                    Connection b = pool.getConnection()) {
                                assertThat(a).isNotSameAs(b);
                            }
                            List<String> opened = ctx.getBean(RecordingFactory.class).opened;
                            assertThat(opened).hasSizeGreaterThanOrEqualTo(2);
                            assertThat(opened)
                                    .allMatch(
                                            o ->
                                                    o.contains(
                                                                    "|agentic|mydb.abc123.eu-west-1"
                                                                            + ".rds.amazonaws.com:5432/?")
                                                            && o.contains("Action=connect")
                                                            && o.contains("X-Amz-Signature="));
                            // One cached token inside its refresh window, handed to every new
                            // physical connection.
                            assertThat(opened.stream().distinct()).hasSize(1);
                        });
    }
}
