package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link SchemaMultiTenantConnectionProvider} is the other half of
 * schema-per-tenant multi-tenancy alongside {@link TenantSchemaResolver} -
 * this is the class that actually issues {@code Connection.setSchema(...)}
 * against a pooled JDBC connection. Its own javadoc calls out the one
 * property that matters most: {@link #releaseConnection} must reset the
 * connection's schema back to {@link TenantSchemaResolver#DEFAULT_SCHEMA}
 * *before* returning it to the pool, so a connection checked back in after
 * serving tenant A can never be handed to a request for tenant B still
 * carrying tenant A's search_path. That reset call had zero test coverage
 * before this suite - if it were ever accidentally deleted in a refactor,
 * nothing today would catch it, and the failure mode is silent cross-tenant
 * data exposure, not an exception.
 */
class SchemaMultiTenantConnectionProviderTest {

    private DataSource dataSource;
    private Connection connection;
    private SchemaMultiTenantConnectionProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        provider = new SchemaMultiTenantConnectionProvider(dataSource);
    }

    /** JAVA-044: a failed reset must neither leak the connection nor return it carrying the tenant schema. */
    @Test
    void releaseClosesAndAbortsWhenTheSchemaResetFails() throws SQLException {
        org.mockito.Mockito.doThrow(new SQLException("reset failed")).when(connection)
            .setSchema(TenantSchemaResolver.DEFAULT_SCHEMA);

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
            () -> provider.releaseConnection("tenant_a", connection));

        verify(connection).abort(org.mockito.ArgumentMatchers.any());
        verify(connection).close();
    }

    @Test
    void getConnectionClosesTheConnectionWhenTheSchemaSwitchFails() throws SQLException {
        org.mockito.Mockito.doThrow(new SQLException("no such schema")).when(connection).setSchema("tenant_x");

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, () -> provider.getConnection("tenant_x"));

        verify(connection).close();
    }

    @Test
    void getConnectionSetsRequestedTenantSchema() throws SQLException {
        Connection result = provider.getConnection("tenant_b");

        assertEquals(connection, result);
        verify(connection).setSchema("tenant_b");
    }

    @Test
    void getAnyConnectionUsesDefaultSchemaNotAnArbitraryTenant() throws SQLException {
        // Called when Hibernate has no tenant identifier at all (startup
        // schema validation, metadata reads) - must never accidentally
        // route to whichever tenant happens to be "current" on some
        // unrelated thread.
        provider.getAnyConnection();

        verify(connection).setSchema(TenantSchemaResolver.DEFAULT_SCHEMA);
    }

    @Test
    void releaseConnectionResetsSchemaToDefaultBeforeClosing() throws SQLException {
        provider.releaseConnection("tenant_b", connection);

        // Order matters: the reset must happen before close(), and in
        // particular must happen at all before the connection can be
        // reused by the pool for a different tenant's request. Verifying
        // the sequence, not just that both calls occurred, is what
        // actually pins down the tenant-isolation guarantee this class
        // exists to provide.
        InOrder order = inOrder(connection);
        order.verify(connection).setSchema(TenantSchemaResolver.DEFAULT_SCHEMA);
        order.verify(connection).close();
    }

    @Test
    void releaseConnectionNeverLeavesTenantSchemaSetOnAReturnedConnection() throws SQLException {
        provider.releaseConnection("tenant_evil_corp", connection);

        verify(connection, never()).setSchema(eq("tenant_evil_corp"));
    }

    @Test
    void releaseAnyConnectionJustClosesWithoutResettingSchema() throws SQLException {
        // getAnyConnection()/releaseAnyConnection() are a pair used for
        // schema-agnostic operations - releaseAnyConnection deliberately
        // does not touch the schema (see the interface contract this
        // implements), unlike releaseConnection(tenantIdentifier, ...)
        // above. Pinning this down as a characterization test so the two
        // release paths aren't accidentally unified in a future refactor
        // without someone consciously deciding the schema-reset behavior
        // for both.
        provider.releaseAnyConnection(connection);

        verify(connection, never()).setSchema(org.mockito.ArgumentMatchers.anyString());
        verify(connection).close();
    }

    @Test
    void doesNotSupportAggressiveRelease() {
        assertFalse(provider.supportsAggressiveRelease());
    }

    @Test
    void isNotUnwrappableAsAnything() {
        assertFalse(provider.isUnwrappableAs(DataSource.class));
    }
}
