package com.agenticai.core.dbauth;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import java.sql.DriverManager;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@code app.datasource.auth}: how core logs in to Postgres.
 *
 * <ul>
 *   <li>{@code password} (default): no DataSource is defined here, Spring Boot builds its usual
 *       Hikari pool from {@code spring.datasource.*}. Only a startup check runs.
 *   <li>{@code aws-iam} / {@code azure-entra} / {@code gcp-iam}: this class defines the one primary
 *       DataSource, a Hikari pool over a {@link TokenDataSource}. Spring Boot's own DataSource
 *       backs off, so Flyway (control plane), {@code TenantSchemaMigrator} (per-tenant Flyway), the
 *       migrate-only runner and JPA all use it, and every new physical connection carries a current
 *       token.
 * </ul>
 *
 * Pool sizing still comes from {@code spring.datasource.hikari.*}. Session pooling rules are
 * unchanged: token modes connect straight to the managed server, not through PgBouncer.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DbAuthProperties.class)
public class DbAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DbAuthConfiguration.class);

    /**
     * Fails startup on an unknown mode, or in password mode with no password (the previous
     * placeholder failure).
     */
    @Bean
    DbAuthStartupCheck dbAuthStartupCheck(
            Environment env, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
        return new DbAuthStartupCheck(env, connectionDetails);
    }

    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Conditional(TokenModeCondition.class)
    HikariDataSource dataSource(
            Environment env,
            DbAuthProperties props,
            ObjectProvider<DbTokenSupplier> supplierOverride,
            ObjectProvider<TokenDataSource.ConnectionFactory> factoryOverride) {
        DbAuthMode mode = DbAuthMode.parse(props.getAuth());
        String url = env.getProperty("spring.datasource.url");
        String username = env.getProperty("spring.datasource.username");
        JdbcUrls.requireTls(url, mode);
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "app.datasource.auth="
                            + mode.value()
                            + " needs the database role in POSTGRES_USER");
        }
        if (!env.getProperty("spring.datasource.password", "").isBlank()) {
            log.warn(
                    "POSTGRES_PASSWORD is set but app.datasource.auth={}: it is ignored",
                    mode.value());
        }
        DbTokenSupplier supplier =
                supplierOverride.getIfAvailable(
                        () ->
                                new CachingDbTokenSupplier(
                                        newSource(mode, url, username, props),
                                        props.getTokenRefreshSkew(),
                                        Clock.systemUTC()));
        TokenDataSource.ConnectionFactory factory =
                factoryOverride.getIfAvailable(() -> DriverManager::getConnection);
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(new TokenDataSource(url, username, supplier, factory));
        log.info(
                "Database login: {} token per new connection (user {}, TLS required)",
                mode.value(),
                username);
        return pool;
    }

    /**
     * One switch, one static factory per cloud, so an SDK class is only resolved for the selected
     * mode.
     */
    private static DbTokenSource newSource(
            DbAuthMode mode, String url, String username, DbAuthProperties props) {
        return switch (mode) {
            case AWS_IAM -> AwsRdsIamTokenSource.create(url, username, props.getAws().getRegion());
            case AZURE_ENTRA -> AzureEntraTokenSource.create(props.getAzure().getScope());
            case GCP_IAM -> GcpIamTokenSource.create(props.getGcp().getScope());
            case PASSWORD -> throw new IllegalStateException("password mode has no token source");
        };
    }

    static final class TokenModeCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DbAuthMode.parse(context.getEnvironment().getProperty("app.datasource.auth"))
                    .usesToken();
        }
    }

    static final class DbAuthStartupCheck {
        /** Spring Boot's package-private spring.datasource.* implementation of the details. */
        static final String PROPERTIES_BACKED = "PropertiesJdbcConnectionDetails";

        private final Environment env;
        private final ObjectProvider<JdbcConnectionDetails> connectionDetails;

        DbAuthStartupCheck(
                Environment env, ObjectProvider<JdbcConnectionDetails> connectionDetails) {
            this.env = env;
            this.connectionDetails = connectionDetails;
        }

        @PostConstruct
        void check() {
            DbAuthMode mode = DbAuthMode.parse(env.getProperty("app.datasource.auth"));
            if (mode == DbAuthMode.PASSWORD && !loginComesFromElsewhere()) {
                String password = env.getProperty("spring.datasource.password");
                if (password == null || password.isBlank()) {
                    throw new IllegalStateException(
                            "POSTGRES_PASSWORD is required when app.datasource.auth=password (the"
                                    + " default). Set it, or select aws-iam / azure-entra / gcp-iam"
                                    + " with DB_AUTH.");
                }
            }
        }

        /**
         * True when a JdbcConnectionDetails bean that is NOT Spring Boot's own properties-backed
         * one supplies the login (Testcontainers {@code @ServiceConnection}, Docker Compose
         * support). Boot's DataSourceAutoConfiguration registers {@code
         * PropertiesJdbcConnectionDetails} whenever it builds the pool from {@code
         * spring.datasource.*}, which is exactly the password-mode case this check exists for, so
         * that bean must not exempt anything.
         */
        private boolean loginComesFromElsewhere() {
            return connectionDetails
                    .orderedStream()
                    .anyMatch(d -> !PROPERTIES_BACKED.equals(d.getClass().getSimpleName()));
        }
    }
}
