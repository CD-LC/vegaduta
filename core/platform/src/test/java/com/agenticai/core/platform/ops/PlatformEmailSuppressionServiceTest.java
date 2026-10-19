package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.ops.PlatformEmailSuppressionService.SuppressionPage;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PlatformEmailSuppressionServiceTest {

    private final DataSource ds = mock(DataSource.class);
    private final Connection conn = mock(Connection.class);
    private final TenantRepository repo = mock(TenantRepository.class);
    private final PlatformTenantAuditor auditor = mock(PlatformTenantAuditor.class);
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    private PlatformEmailSuppressionService service;

    private static Tenant tenant(String schema, String name) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(UUID.nameUUIDFromBytes(schema.getBytes()));
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getName()).thenReturn(name);
        return t;
    }

    @BeforeEach
    void setUp() throws Exception {
        when(ds.getConnection()).thenReturn(conn);
        List<Tenant> roster = List.of(tenant("acme", "Acme"), tenant("beta", ""));
        when(repo.findRosterExcludingDemo()).thenReturn(roster);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            statements.put(sql, ps);
            if (sql.startsWith("SELECT") && sql.contains("\"acme\"")) {
                ResultSet rs = rows(new Object[][] {
                    {"a@x.com", "UNSUBSCRIBE", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"))},
                    {"c@x.com", "BOUNCE", Timestamp.from(Instant.parse("2026-10-03T00:00:00Z"))}});
                when(ps.executeQuery()).thenReturn(rs);
            } else if (sql.startsWith("SELECT")) {
                when(ps.executeQuery()).thenThrow(new SQLException("no table", "42P01"));
            } else if (sql.contains("\"acme\"")) {
                when(ps.executeUpdate()).thenReturn(1);
            } else {
                when(ps.executeUpdate()).thenReturn(0);
            }
            return ps;
        });
        service = new PlatformEmailSuppressionService(ds, repo, auditor);
    }

    @Test
    void mergesTenantsNewestFirstAndPagesOneBased() throws Exception {
        SuppressionPage all = service.list(null, null, null);
        assertEquals(2, all.total());
        assertEquals("c@x.com", all.items().get(0).email());
        assertEquals("Acme", all.items().get(0).source());
        assertEquals("acme", all.items().get(0).tenantId());

        SuppressionPage second = service.list(null, 2, 1);
        assertEquals(1, second.items().size());
        assertEquals("a@x.com", second.items().get(0).email());
        assertEquals(2, second.total());
    }

    @Test
    void searchEscapesLikeWildcards() throws Exception {
        service.list("A_B%", 1, 10);
        PreparedStatement ps = statements.entrySet().stream()
            .filter(e -> e.getKey().contains("\"acme\"")).findFirst().orElseThrow().getValue();
        verify(ps).setString(1, "%a\\_b\\%%");
    }

    @Test
    void removeDeletesWhereverPresentAndAudits() {
        assertEquals(1, service.remove(" A@X.com ", null, "ops"));
        verify(auditor).record(eq("acme"), eq("ops"), eq("platform.email_suppression.remove"), any(), eq("a@x.com"), anyString());
        verify(auditor, never()).record(eq("beta"), any(), any(), any(), any(), any());
    }

    @Test
    void removeCanBeScopedAndIs404WhenAbsent() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.remove("a@x.com", "beta", "ops"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertThrows(IllegalArgumentException.class, () -> service.remove(" ", null, "ops"));
    }

    @Test
    void controllerReturns204() {
        var controller = new PlatformEmailSuppressionController(service);
        assertEquals(204, controller.remove("a@x.com", null).getStatusCode().value());
    }

    private static ResultSet rows(Object[][] data) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] i = {-1};
        when(rs.next()).thenAnswer(inv -> ++i[0] < data.length);
        when(rs.getString(1)).thenAnswer(inv -> (String) data[i[0]][0]);
        when(rs.getString(2)).thenAnswer(inv -> (String) data[i[0]][1]);
        when(rs.getTimestamp(3)).thenAnswer(inv -> (Timestamp) data[i[0]][2]);
        return rs;
    }
}
