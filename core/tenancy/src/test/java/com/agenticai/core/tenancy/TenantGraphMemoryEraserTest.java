package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantGraphMemoryEraser}: the SEC-001 never-drop-another-tenant's-schema guard, the {@code
 * drop_graph} path when the AGE graph is registered, the plain {@code DROP SCHEMA} fallback when it
 * is not (or when {@code drop_graph} itself fails), and best-effort {@code false} on any database
 * problem - the postmortem contract that a stuck {@code _kg} schema must never again leave a tenant
 * unerasable.
 */
class TenantGraphMemoryEraserTest {

    private static final String GRAPH_REGISTERED =
            "SELECT COUNT(*) FROM ag_catalog.ag_graph WHERE name::text = ?";
    private static final String DROP_GRAPH = "SELECT * FROM ag_catalog.drop_graph(?::name, true)";
    private static final String AGE_PROBE = "SELECT to_regclass('ag_catalog.ag_graph') IS NOT NULL";

    private TenantRepository tenantRepository;
    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private TenantGraphMemoryEraser eraser;

    private Tenant tenant;

    @BeforeEach
    void setUp() throws SQLException {
        tenantRepository = mock(TenantRepository.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        eraser = new TenantGraphMemoryEraser(tenantRepository, dataSource);
        tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(UUID.randomUUID());
        when(tenant.getName()).thenReturn("Acme");
        when(tenantRepository.findBySchemaName(anyString())).thenReturn(Optional.empty());
        AgeSessionSupport.resetCachedPreloadStateForTests();
    }

    /** A ResultSet answering {@code next() -> false} for AGE's SHOW/search_path probes. */
    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private void stubProbe(boolean ageInstalled) throws SQLException {
        ResultSet probe = mock(ResultSet.class);
        when(probe.next()).thenReturn(true);
        when(probe.getBoolean(1)).thenReturn(ageInstalled);
        ResultSet empty = emptyResultSet();
        org.mockito.Mockito.doAnswer(
                        invocation -> AGE_PROBE.equals(invocation.getArgument(0)) ? probe : empty)
                .when(statement)
                .executeQuery(anyString());
    }

    private void stubGraphRegistered(boolean registered) throws SQLException {
        ResultSet lookup = mock(ResultSet.class);
        when(lookup.next()).thenReturn(true);
        when(lookup.getLong(1)).thenReturn(registered ? 1L : 0L);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(lookup);
        when(connection.prepareStatement(GRAPH_REGISTERED)).thenReturn(ps);
    }

    @Test
    void refusesToDropWhenTheGraphSchemaNameIsProtected() {
        // "pg_tenant_kg" starts with "pg_" - protected, never a DROP target.
        assertFalse(eraser.dropGraphMemorySchema(tenant, "pg_tenant"));
    }

    @Test
    void refusesToDropAnotherTenantsSchemaThatEndsInKg() {
        Tenant otherTenant = mock(Tenant.class);
        when(otherTenant.getId()).thenReturn(UUID.randomUUID()); // != tenant's id
        when(tenantRepository.findBySchemaName("acme_kg")).thenReturn(Optional.of(otherTenant));

        assertFalse(eraser.dropGraphMemorySchema(tenant, "acme"));
    }

    @Test
    void anUnregisteredGraphFallsBackToAPlainDropSchema() throws SQLException {
        stubProbe(false);

        assertTrue(eraser.dropGraphMemorySchema(tenant, "acme"));

        verify(statement).execute("DROP SCHEMA IF EXISTS \"acme_kg\" CASCADE");
    }

    @Test
    void aRegisteredGraphIsDroppedThroughDropGraphNotRawDdl() throws SQLException {
        stubProbe(true);
        stubGraphRegistered(true);
        PreparedStatement dropGraph = mock(PreparedStatement.class);
        when(connection.prepareStatement(DROP_GRAPH)).thenReturn(dropGraph);

        assertTrue(eraser.dropGraphMemorySchema(tenant, "acme"));

        verify(dropGraph).setString(1, "acme_kg");
        verify(dropGraph).execute();
    }

    @Test
    void aFailedDropGraphStillFallsBackToDropSchema() throws SQLException {
        // The Shyama incident's leftover state: a half-torn-down graph whose
        // drop_graph errors must still get cleaned up, not retried forever.
        stubProbe(true);
        stubGraphRegistered(true);
        PreparedStatement dropGraph = mock(PreparedStatement.class);
        org.mockito.Mockito.doThrow(new SQLException("catalog row wedged"))
                .when(dropGraph)
                .execute();
        when(connection.prepareStatement(DROP_GRAPH)).thenReturn(dropGraph);

        assertTrue(eraser.dropGraphMemorySchema(tenant, "acme"));

        verify(statement).execute("DROP SCHEMA IF EXISTS \"acme_kg\" CASCADE");
    }

    @Test
    void aDatabaseFailureReturnsFalseSoErasureContinuesButIsHonest() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        assertFalse(eraser.dropGraphMemorySchema(tenant, "acme"));
    }

    @Test
    void isAgeGraphRegisteredAnswersFalseWhenAgeIsNotInstalled() throws SQLException {
        stubProbe(false);

        assertFalse(eraser.isAgeGraphRegistered(connection, statement, "acme_kg"));
    }

    @Test
    void isAgeGraphRegisteredAnswersTrueOnlyWhenTheGraphRowExists() throws SQLException {
        stubProbe(true);
        stubGraphRegistered(true);
        assertTrue(eraser.isAgeGraphRegistered(connection, statement, "acme_kg"));

        stubGraphRegistered(false);
        assertFalse(eraser.isAgeGraphRegistered(connection, statement, "acme_kg"));
    }

    @Test
    void isAgeGraphRegisteredTreatsProbeFailuresAsNotRegistered() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("no pg_catalog"));
        assertFalse(eraser.isAgeGraphRegistered(connection, statement, "acme_kg"));

        stubProbe(true);
        when(connection.prepareStatement(GRAPH_REGISTERED))
                .thenThrow(new SQLException("catalog missing"));
        assertFalse(eraser.isAgeGraphRegistered(connection, statement, "acme_kg"));
    }
}
