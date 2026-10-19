package com.agenticai.core.tenancy.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.aireport.AiContentReportRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AccountSelfDeletionServiceTest {

    private KeycloakAdminClient keycloak;
    private TenantDataErasureService erasure;
    private AppUserRepository appUsers;
    private FileStorageService storage;
    private AiContentReportRepository reports;
    private AuditLogService audit;
    private ApplicationEventPublisher events;
    private AccountSelfDeletionService service;
    private AppUser appUser;

    @BeforeEach
    void setUp() {
        keycloak = mock(KeycloakAdminClient.class);
        erasure = mock(TenantDataErasureService.class);
        appUsers = mock(AppUserRepository.class);
        storage = mock(FileStorageService.class);
        reports = mock(AiContentReportRepository.class);
        audit = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AccountSelfDeletionService(keycloak, erasure, appUsers, storage, reports, audit, events);

        appUser = mock(AppUser.class);
        when(appUser.getAvatarUrl()).thenReturn("avatars/abc.png");
        when(appUsers.findByKeycloakUserId("user-1")).thenReturn(Optional.of(appUser));
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static KeycloakUserRepresentation kcUser(String id) {
        return new KeycloakUserRepresentation(id, null, null, null, null, true, true, null, null, null);
    }

    @Test
    void wrongConfirmationIs400AndDeletesNothing() {
        for (String confirm : new String[] {null, "", "delete", "yes", "DELETE "}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteCurrentAccount(confirm));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verifyNoInteractions(keycloak, erasure, appUsers, storage, reports, audit, events);
    }

    @Test
    void soleMemberErasesWholeTenantThenKeycloakUser() {
        when(keycloak.listUsersByTenant("acme")).thenReturn(List.of(kcUser("user-1")));
        // The erasure already deleted the Keycloak user, so the backstop 404s.
        doThrow(new RuntimeException("404")).when(keycloak).deleteUser("user-1");

        AccountSelfDeletionService.Result result = service.deleteCurrentAccount("DELETE");

        assertTrue(result.deleted());
        assertTrue(result.tenantDeleted());
        InOrder order = inOrder(erasure, appUsers, keycloak);
        order.verify(erasure).eraseCurrentTenantForSoleMemberAccountDeletion();
        order.verify(appUsers).delete(appUser);
        order.verify(keycloak).deleteUser("user-1");
        verify(reports).anonymiseReporter("user-1");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void sharedWorkspaceDeletesOnlyOwnDataThenKeycloakUser() {
        when(keycloak.listUsersByTenant("acme")).thenReturn(List.of(kcUser("user-1"), kcUser("user-2")));
        when(reports.anonymiseReporter("user-1")).thenReturn(2);

        AccountSelfDeletionService.Result result = service.deleteCurrentAccount("DELETE");

        assertTrue(result.deleted());
        assertFalse(result.tenantDeleted());
        verify(erasure, never()).eraseCurrentTenantForSoleMemberAccountDeletion();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(events, storage, appUsers, keycloak);
        order.verify(events).publishEvent(event.capture());
        order.verify(storage).delete("avatars/abc.png");
        order.verify(appUsers).delete(appUser);
        order.verify(keycloak).deleteUser("user-1");
        assertEquals(new AccountDeletionRequested("acme", "user-1"), event.getValue());
        verify(keycloak, never()).deleteUser("user-2");
        verify(audit).record(eq("user-1"), eq("ACCOUNT_SELF_DELETED"), eq("USER"), eq("user-1"),
            eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void sharedWorkspaceKeycloakFailurePropagatesSoClientCanRetry() {
        when(keycloak.listUsersByTenant("acme")).thenReturn(List.of(kcUser("user-1"), kcUser("user-2")));
        doThrow(new IllegalStateException("keycloak down")).when(keycloak).deleteUser("user-1");

        assertThrows(IllegalStateException.class, () -> service.deleteCurrentAccount("DELETE"));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void platformAdminCannotSelfDelete() {
        TenantContext.set(TenantContext.of(null, "admin-1", Set.of("ROLE_platform-admin")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.deleteCurrentAccount("DELETE"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(keycloak, erasure);
    }
}
