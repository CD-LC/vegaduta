package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantControlPlanePurger}: the 15-step control-plane cleanup - every step its own
 * auto-committed statement with the tenant's schema name bound to every placeholder, one failing
 * step never blocking the rest, and the returned failure labels honest about which tables could not
 * be cleaned.
 */
class TenantControlPlanePurgerTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private TenantControlPlanePurger purger;
    private Tenant tenant;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(0);
        purger = new TenantControlPlanePurger(dataSource);
        tenant = mock(Tenant.class);
        when(tenant.getName()).thenReturn("Acme");
    }

    @Test
    void everyDeleteAndUpdateStepRunsWithTheSchemaNameBound() throws SQLException {
        List<String> failures = purger.purgeControlPlaneRows(tenant, "acme");

        assertTrue(failures.isEmpty());
        // 14 CONTROL_PLANE_DELETES entries + 1 CONTROL_PLANE_UPDATES entry.
        verify(connection, times(15)).prepareStatement(anyString());
        verify(statement, times(15)).executeUpdate();
        // Every '?' placeholder in every statement is bound to the schema
        // name: x402_payment binds it twice (payer OR offer owner) and the
        // community_template_rating CTE binds it twice (delete + recount).
        verify(statement, times(17)).setString(anyInt(), eq("acme"));
    }

    @Test
    void oneFailingStepIsReportedAndTheRestStillRun() throws SQLException {
        PreparedStatement failing = mock(PreparedStatement.class);
        when(failing.executeUpdate()).thenThrow(new SQLException("lock timeout"));
        when(connection.prepareStatement(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            return sql.contains("x402_payment") ? failing : statement;
                        });

        List<String> failures = purger.purgeControlPlaneRows(tenant, "acme");

        assertEquals(List.of("x402_payment"), failures);
        // The other 14 steps still ran against the healthy statement.
        verify(statement, times(14)).executeUpdate();
    }

    @Test
    void everyStepFailingCollectsEveryLabel() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("no such table"));

        List<String> failures = purger.purgeControlPlaneRows(tenant, "acme");

        assertEquals(15, failures.size());
        assertTrue(failures.contains("checkout_session"));
        assertTrue(failures.contains("community_template_rating"));
        assertTrue(failures.contains("community_template.source_tenant_id"));
    }

    @Test
    void aRuntimeExceptionCountsAsAFailureToo() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new IllegalStateException("driver blew up"));

        List<String> failures = purger.purgeControlPlaneRows(tenant, "acme");

        assertEquals(15, failures.size());
    }
}
