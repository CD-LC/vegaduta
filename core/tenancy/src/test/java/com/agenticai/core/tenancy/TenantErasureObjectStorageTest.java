package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantDataErasureService}: object-storage cleanup before the schema drop, and the
 * read-only object count.
 */
class TenantErasureObjectStorageTest extends TenantDataErasureFixture {

    // --- Infra audit fix (2026-07-27): object-storage cleanup before schema drop --------------

    @Test
    void erasureDeletesObjectStorageKeysFoundInSchemaScopedTablesBeforeDroppingTheSchema()
            throws SQLException {
        // The mocked ResultSet is shared across all 5 OBJECT_KEY_TABLES
        // queries (one dataSource.getConnection() stub for the whole test),
        // so a stateful next()=[true, false] sequence is only consumed once
        // in total, by whichever table's loop runs first - not once per
        // table. That's fine here: this test only needs to prove that a
        // discovered key gets delete()'d, and that it happens before the
        // schema drop. The first two queries are now the Valkey-purge id
        // collections (MCP grants, workflow runs), which find nothing.
        when(resultSet.next()).thenReturn(false, false, true, false);
        when(resultSet.getString(1)).thenReturn("some/object-key.bin");

        service.eraseCurrentTenant("Acme");

        verify(fileStorageService).delete("some/object-key.bin");
        // Order matters - erasure must discover keys while the rows (and the
        // schema they live in) still exist, strictly before DROP SCHEMA runs.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fileStorageService, statement);
        order.verify(fileStorageService).delete("some/object-key.bin");
        order.verify(statement, times(2)).execute(anyString());
    }

    @Test
    void erasureDeletesEachTenantAppUsersAvatarObject() {
        AppUser user = new AppUser(tenant.getId(), "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        user.setAvatarUrl("avatars/user-1.png");
        when(appUserRepository.findByTenantId(tenant.getId())).thenReturn(List.of(user));

        service.eraseCurrentTenant("Acme");

        verify(fileStorageService).delete("avatars/user-1.png");
    }

    @Test
    void aUserWithNoAvatarIsSkippedRatherThanDeletingANullKey() {
        AppUser user = new AppUser(tenant.getId(), "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        when(appUserRepository.findByTenantId(tenant.getId())).thenReturn(List.of(user));

        assertDoesNotThrowDuringErasure();

        verify(fileStorageService, never()).delete(any());
    }

    @Test
    void oneFailedObjectStorageDeleteDoesNotStopErasureOrLeaveTheTenantUnerased()
            throws SQLException {
        when(resultSet.next()).thenReturn(false, false, true, false);
        when(resultSet.getString(1)).thenReturn("some/object-key.bin");
        org.mockito.Mockito.doThrow(new RuntimeException("S3 timeout"))
                .when(fileStorageService)
                .delete("some/object-key.bin");

        service.eraseCurrentTenant("Acme");

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        verify(statement, times(2)).execute(anyString());
    }

    @Test
    void aTableEnumerationFailureDoesNotStopErasure() throws SQLException {
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("relation does not exist"));

        service.eraseCurrentTenant("Acme");

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        verify(statement, times(2)).execute(anyString());
    }

    private void assertDoesNotThrowDuringErasure() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.eraseCurrentTenant("Acme"));
    }

    // --- Infra transparency feature (2026-07-27): read-only object-storage count ---------------

    @Test
    void countObjectStorageItemsForCurrentTenantSumsAvatarsAndEveryObjectKeyTable()
            throws SQLException {
        AppUser userWithAvatar =
                new AppUser(tenant.getId(), "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        userWithAvatar.setAvatarUrl("avatars/user-1.png");
        AppUser userWithoutAvatar =
                new AppUser(tenant.getId(), "kc-2", "b@acme.test", UserRole.TENANT_ADMIN);
        when(appUserRepository.findByTenantId(tenant.getId()))
                .thenReturn(List.of(userWithAvatar, userWithoutAvatar));

        // One COUNT(*) query per OBJECT_KEY_TABLES entry (5 tables, since
        // voice_call_recording was added 2026-08-07) - each returns a row
        // with a count of 2, via the single shared mocked ResultSet/Statement
        // (same "one stub reused across every table's query" trade-off the
        // existing erasure tests above already accept).
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(2L);

        long total = service.countObjectStorageItemsForCurrentTenant();

        // 1 avatar + (2 per table * 5 OBJECT_KEY_TABLES entries) = 11.
        assertEquals(11L, total);
    }

    @Test
    void countObjectStorageItemsForCurrentTenantIsZeroWhenTenantHasNothingStored() {
        when(appUserRepository.findByTenantId(tenant.getId())).thenReturn(List.of());

        long total = service.countObjectStorageItemsForCurrentTenant();

        assertEquals(0L, total);
    }

    @Test
    void countObjectStorageItemsForCurrentTenantTreatsAMissingTableAsZeroNotAFailure()
            throws SQLException {
        when(appUserRepository.findByTenantId(tenant.getId())).thenReturn(List.of());
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("relation does not exist"));

        long total = service.countObjectStorageItemsForCurrentTenant();

        assertEquals(0L, total);
    }
}
