package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The tenant-less web-admin token must get an empty bell, not a query against a schema with no
 * notification table.
 */
class NotificationControllerPlatformAdminTest {

    private final NotificationService service = mock(NotificationService.class);
    private final NotificationController controller = new NotificationController(service);

    private static Authentication auth(String... authorities) {
        return new TestingAuthenticationToken("op", "n/a", authorities);
    }

    @Test
    void tenantlessPlatformAdminGetsEmptyBellWithoutTouchingTheStore() {
        Authentication platformAdmin = auth("ROLE_platform-admin");
        assertTrue(controller.list(false, 50, null, platformAdmin).getBody().isEmpty());
        assertEquals(0, controller.unreadCount(platformAdmin));
        controller.markAllRead(platformAdmin);
        controller.markRead(java.util.UUID.randomUUID(), platformAdmin);
        verifyNoInteractions(service);
    }

    @Test
    void markReadPassesTheCallersIdentityAndAdminFlagToTheService() {
        java.util.UUID id = java.util.UUID.randomUUID();
        controller.markRead(id, auth("ROLE_member"));
        org.mockito.Mockito.verify(service).markRead(id, "op", false);
        controller.markRead(id, auth("ROLE_tenant-admin", "PERM_MANAGE_USERS"));
        org.mockito.Mockito.verify(service).markRead(id, "op", true);
    }

    @Test
    void tenantMemberWithPermissionsStillQueriesTheStore() {
        when(service.countUnread(any(), anyBoolean())).thenReturn(3L);
        assertEquals(3, controller.unreadCount(auth("ROLE_tenant-admin", "PERM_MANAGE_USERS")));
    }

    @Test
    void ordinaryMemberStillQueriesTheStore() {
        when(service.listVisible(any(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(List.of());
        assertTrue(controller.list(false, 50, null, auth("ROLE_member")).getBody().isEmpty());
    }
}
