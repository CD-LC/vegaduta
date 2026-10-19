package com.agenticai.core.admin.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-request lookup behind {@code GranularPermissionConverter}: stored
 * grants bind every caller, results are cached briefly so authentication
 * doesn't put a control-plane query on every API call, and {@link
 * AdminPermissionAuthoritySource#evict} lets the admin portal make its own
 * change visible immediately.
 */
class AdminPermissionAuthoritySourceTest {

    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "user-1";

    private UserPermissionService userPermissionService;
    private AdminPermissionAuthoritySource source;

    @BeforeEach
    void setUp() {
        userPermissionService = mock(UserPermissionService.class);
        source = new AdminPermissionAuthoritySource(userPermissionService);
    }

    @Test
    void delegatesToTheOrSeedLookupWithTheCallersRoles() {
        when(userPermissionService.getOrSeedEffectivePermissions(eq(TENANT_ID), eq(USER_ID), anySet(), eq(USER_ID)))
            .thenReturn(Set.of("MANAGE_BILLING"));

        assertEquals(Set.of("MANAGE_BILLING"),
            source.effectivePermissions(TENANT_ID, USER_ID, Set.of("agent-builder")));
    }

    @Test
    void nullTenantOrSubjectGetsNoPermissions() {
        assertEquals(Set.of(), source.effectivePermissions(null, USER_ID, Set.of()));
        assertEquals(Set.of(), source.effectivePermissions(TENANT_ID, null, Set.of()));
    }

    @Test
    void cachesWithinTheTtlWindow() {
        when(userPermissionService.getOrSeedEffectivePermissions(anyString(), anyString(), anySet(), anyString()))
            .thenReturn(Set.of("VIEW_AUDIT_LOG"));

        source.effectivePermissions(TENANT_ID, USER_ID, Set.of());
        source.effectivePermissions(TENANT_ID, USER_ID, Set.of());

        verify(userPermissionService, times(1))
            .getOrSeedEffectivePermissions(anyString(), anyString(), anySet(), anyString());
    }

    @Test
    void evictForcesAFreshReadOnTheNextCall() {
        when(userPermissionService.getOrSeedEffectivePermissions(anyString(), anyString(), anySet(), anyString()))
            .thenReturn(Set.of("VIEW_AUDIT_LOG"));

        source.effectivePermissions(TENANT_ID, USER_ID, Set.of());
        source.evict(TENANT_ID, USER_ID);
        source.effectivePermissions(TENANT_ID, USER_ID, Set.of());

        verify(userPermissionService, times(2))
            .getOrSeedEffectivePermissions(anyString(), anyString(), anySet(), anyString());
    }
}
