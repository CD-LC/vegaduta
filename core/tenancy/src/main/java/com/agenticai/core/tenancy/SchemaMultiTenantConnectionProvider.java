package com.agenticai.core.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Phase 0 Stage 4 - the other half of schema-per-tenant multi-tenancy alongside {@link
 * TenantSchemaResolver}. Reuses the app's existing pooled {@link DataSource} - no second connection
 * pool per tenant, which is the whole point of choosing schema-based over database-based
 * multi-tenancy (see the Microservices Architecture doc, Section 4's tradeoff table) - and switches
 * a checked-out connection's active schema via the standard JDBC {@link Connection#setSchema} call.
 * PostgreSQL's JDBC driver implements {@code setSchema} as {@code SET search_path}, so this reaches
 * exactly the mechanism the Phase0 Build Plan's Stage 4 section calls for, via the portable JDBC
 * API rather than a hand-written SQL statement.
 *
 * <p>Registered the same way as {@link TenantSchemaResolver} - via {@link
 * HibernatePropertiesCustomizer} - since Spring Boot's auto-configured {@code EntityManagerFactory}
 * doesn't pick up a {@code MultiTenantConnectionProvider} bean on its own either.
 *
 * <p><b>Any connection pooler in front of Postgres MUST use session pooling.</b> {@code setSchema}
 * is {@code SET search_path} - session state, executed in its own implicit transaction. A
 * transaction-pooling proxy (PgBouncer's {@code pool_mode = transaction}) rebinds the client to a
 * different server connection per transaction, so the {@code SET} here and the queries that follow
 * it can land on different backends: reads silently run against {@code public} or, worse, another
 * tenant's schema, and writes land there too. Measured on this stack while PgBouncer was in
 * transaction mode (fixed 2026-07-24): 41 of 60 concurrent clients got the wrong schema, 21 of them
 * another tenant's. See {@code deploy/docker-compose.yml}'s pgbouncer {@code POOL_MODE} comment.
 * Getting transaction pooling back means dropping session-scoped {@code search_path} altogether
 * (schema-qualified statements, or database-per-tenant), not tuning the proxy.
 */
@Component
public class SchemaMultiTenantConnectionProvider
        implements MultiTenantConnectionProvider<String>, HibernatePropertiesCustomizer {

    private final DataSource dataSource;

    public SchemaMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Called when Hibernate needs a connection with no tenant identifier available at all (startup
     * schema validation, metadata reads) - {@link TenantSchemaResolver#DEFAULT_SCHEMA} is always
     * present, so it's a safe, always-valid choice here.
     *
     * <p>F-14: deliberately NOT counted by {@link TenantSchemaResolver#FALLBACK_METRIC} - this path
     * never goes through the resolver, only Hibernate's own bootstrap calls it, and it is
     * legitimate by construction (there is no request, so there is no tenant context to have lost).
     * Instrumenting it would only add noise under the metric that exists to find lost request
     * context.
     */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return getConnection(TenantSchemaResolver.DEFAULT_SCHEMA);
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setSchema(tenantIdentifier);
        } catch (SQLException | RuntimeException e) {
            // Never leak the borrowed connection when the schema switch fails (JAVA-044). Closed
            // inline rather than via a helper so the close is visible on this exceptional path.
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection)
            throws SQLException {
        // Reset before returning to the pool - a pooled connection must
        // never carry a stale tenant's schema into whichever request
        // borrows it next. Explicit reset here rather than relying on the
        // pool's own connection-reset behavior, matching the pattern Spring
        // Data's own reference example for this exact feature uses.
        //
        // JAVA-044: close() used to be skipped when setSchema threw, leaking
        // the connection. And a connection whose schema could NOT be reset
        // must not go back to the pool still pointing at this tenant's
        // schema, so it is aborted (physically closed; the pool discards it
        // on its next validation) before being handed back.
        try {
            connection.setSchema(TenantSchemaResolver.DEFAULT_SCHEMA);
        } catch (SQLException | RuntimeException resetFailure) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException abortFailure) {
                resetFailure.addSuppressed(abortFailure);
            }
            throw resetFailure;
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return null;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, this);
    }
}
