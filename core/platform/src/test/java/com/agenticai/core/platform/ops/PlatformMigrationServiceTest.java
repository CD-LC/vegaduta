package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.ops.PlatformMigrationService.MigrationsResponse;
import com.agenticai.core.platform.ops.PlatformMigrationService.TenantMigration;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PlatformMigrationServiceTest {

    private static Tenant tenant(String schema, Instant erasedAt) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(UUID.nameUUIDFromBytes(schema.getBytes()));
        when(t.getName()).thenReturn(schema.toUpperCase());
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getErasedAt()).thenReturn(erasedAt);
        return t;
    }

    @Test
    void reportsEachTenantAgainstTheClasspathAndSkipsErased() throws Exception {
        TenantRepository repo = mock(TenantRepository.class);
        Tenant ok = tenant("acme", null);
        Tenant behind = tenant("beta", null);
        Tenant broken = tenant("gamma", null);
        Tenant missing = tenant("delta", null);
        Tenant erased = tenant("gone", Instant.now());
        when(repo.findRosterExcludingDemo()).thenReturn(List.of(ok, behind, broken, missing, erased));
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        when(migrator.expectedTenantVersions()).thenReturn(Set.of("1", "2", "10", "452"));

        Connection conn = mock(Connection.class);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("\"acme\"")) {
                return rows(new Object[][] {{"1", true}, {"2", true}, {"10", true}, {"452", true}});
            }
            if (sql.contains("\"beta\"")) {
                return rows(new Object[][] {{"1", true}, {"2", true}, {"10", true}});
            }
            if (sql.contains("\"gamma\"")) {
                return rows(new Object[][] {{"1", true}, {"2", false}});
            }
            throw new SQLException("relation does not exist", "42P01");
        });

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("dataSource", ds);
        PlatformMigrationService svc = new PlatformMigrationService(beans.getBeanProvider(Flyway.class),
            beans.getBeanProvider(DataSource.class), repo, migrator);

        MigrationsResponse r = svc.status();

        assertEquals("452", r.expectedTenantVersion());
        assertNull(r.controlPlane().version()); // no Flyway bean in this test
        assertEquals(4, r.tenants().size());
        TenantMigration a = r.tenants().get(0);
        assertEquals("452", a.version());
        assertEquals("ok", a.status());
        assertEquals(0, a.pending());
        TenantMigration b = r.tenants().get(1);
        assertEquals("10", b.version());
        assertEquals("behind", b.status());
        assertEquals(1, b.pending());
        TenantMigration c = r.tenants().get(2);
        assertEquals("failed", c.status());
        assertEquals(1, c.failed());
        TenantMigration d = r.tenants().get(3);
        assertNull(d.version());
        assertEquals("no-history", d.status());
        verify(st, never()).executeQuery(org.mockito.ArgumentMatchers.contains("\"gone\""));

        svc.status(); // cached
        verify(ds, org.mockito.Mockito.times(1)).getConnection();
    }

    @Test
    void versionOrderingIsNumeric() {
        assertEquals("452", PlatformMigrationService.highest(Set.of("9", "452", "45", "100")));
        assertEquals("12.10", PlatformMigrationService.highest(Set.of("12.9", "12.10", "12")));
        assertNull(PlatformMigrationService.highest(Set.of()));
    }

    @Test
    void schemaIdentifierQuotesPlainNamesAndRefusesEverythingElse() {
        assertEquals("\"t_abc\"", PlatformMigrationService.schemaIdentifier("t_abc"));
        // Quoting never launders a bad name: a quote-bearing, mixed-case or null schema is refused
        // (IllegalArgumentException, which tenantRow turns into an errorRow), not rendered.
        assertThrows(IllegalArgumentException.class, () -> PlatformMigrationService.schemaIdentifier("a\"b"));
        assertThrows(IllegalArgumentException.class, () -> PlatformMigrationService.schemaIdentifier("Acme"));
        assertThrows(IllegalArgumentException.class, () -> PlatformMigrationService.schemaIdentifier(null));
    }

    private static ResultSet rows(Object[][] data) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] i = {-1};
        when(rs.next()).thenAnswer(inv -> ++i[0] < data.length);
        when(rs.getString(1)).thenAnswer(inv -> (String) data[i[0]][0]);
        when(rs.getBoolean(2)).thenAnswer(inv -> (Boolean) data[i[0]][1]);
        return rs;
    }
}
