package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantObjectStorageEraser}: best-effort deletion of every object key discoverable via a
 * DB query (avatars in {@code control_plane.app_user} plus the five {@code OBJECT_KEY_TABLES}
 * schema-scoped columns), the same-listing count twin, and the "one bad part doesn't sink
 * everything" posture on both halves.
 */
class TenantObjectStorageEraserTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private AppUserRepository appUserRepository;
    private FileStorageService fileStorageService;
    private TenantObjectStorageEraser eraser;

    private Tenant tenant;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        appUserRepository = mock(AppUserRepository.class);
        fileStorageService = mock(FileStorageService.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        eraser =
                new TenantObjectStorageEraser(
                        dataSource, appUserRepository, fileStorageService);
        tenant = mock(Tenant.class);
        when(tenant.getName()).thenReturn("Acme");
        when(tenant.getSchemaName()).thenReturn("acme");
    }

    @Test
    void erasureDeletesEveryAppUserAvatarAndEveryDiscoveredTableKey() throws SQLException {
        AppUser withAvatar =
                new AppUser(null, "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        withAvatar.setAvatarUrl("avatars/user-1.png");
        AppUser withoutAvatar =
                new AppUser(null, "kc-2", "b@acme.test", UserRole.AGENT_BUILDER);
        when(appUserRepository.findByTenantId(any())).thenReturn(List.of(withAvatar, withoutAvatar));
        // One object-key row in the first table queried, empty everywhere
        // after (the shared mock's next() sequence is consumed in order).
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("docs/file.pdf");

        eraser.eraseObjectStorageForTenant(tenant, "acme");

        verify(fileStorageService).delete("avatars/user-1.png");
        verify(fileStorageService).delete("docs/file.pdf");
        // Five schema-scoped table scans, one statement each.
        verify(statement, times(5)).executeQuery(anyString());
    }

    @Test
    void aBlankOrNullKeyIsNeverDeleted() {
        assertDoesNotThrow(() -> eraser.deleteObjectBestEffort(null, tenant, "x"));
        assertDoesNotThrow(() -> eraser.deleteObjectBestEffort("   ", tenant, "x"));
        verify(fileStorageService, never()).delete(any());
    }

    @Test
    void oneFailedDeleteDoesNotAbortTheRest() throws SQLException {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("bad-key", "good-key");
        doThrow(new RuntimeException("S3 timeout")).when(fileStorageService).delete("bad-key");

        eraser.eraseObjectKeysFromTable(tenant, "acme", "generated_image", "object_key");

        verify(fileStorageService).delete("good-key");
    }

    @Test
    void aMissingTableLogsAndMovesOnRatherThanFailingErasure() throws SQLException {
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("relation does not exist"));

        assertDoesNotThrow(
                () -> eraser.eraseObjectKeysFromTable(tenant, "acme", "generated_image", "object_key"));
        verify(fileStorageService, never()).delete(any());
    }

    @Test
    void countObjectStorageItemsSumsAvatarsAndEveryTableColumn() throws SQLException {
        AppUser withAvatar =
                new AppUser(null, "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        withAvatar.setAvatarUrl("avatars/user-1.png");
        AppUser blankAvatar =
                new AppUser(null, "kc-2", "b@acme.test", UserRole.AGENT_BUILDER);
        blankAvatar.setAvatarUrl("   ");
        when(appUserRepository.findByTenantId(any()))
                .thenReturn(List.of(withAvatar, blankAvatar));
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(2L);

        // 1 non-blank avatar + 5 tables x 2 counted keys.
        assertEquals(11L, eraser.countObjectStorageItems(tenant));
    }

    @Test
    void countNonNullColumnUnderReportsRatherThanFailingOnAMissingTable() throws SQLException {
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("relation does not exist"));

        assertEquals(0L, eraser.countNonNullColumn("acme", "generated_image", "object_key"));
    }
}
