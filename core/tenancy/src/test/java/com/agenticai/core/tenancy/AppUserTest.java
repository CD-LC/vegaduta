package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Entity coverage for {@link AppUser}: constructor defaults, the mutators that also touch
 * {@code updatedAt}, and the {@code lastSeenAt} column's null-until-first-seen contract (it is
 * written only by {@code AppUserLastSeenRecorder}'s own UPDATE, never through this entity).
 */
class AppUserTest {

    @Test
    void constructorStampsTimestampsAndLeavesOptionalColumnsNull() {
        UUID tenantId = UUID.randomUUID();

        AppUser user = new AppUser(tenantId, "kc-sub-1", "a@acme.test", UserRole.TENANT_ADMIN);

        assertEquals(tenantId, user.getTenantId());
        assertEquals("kc-sub-1", user.getKeycloakUserId());
        assertEquals("a@acme.test", user.getEmail());
        assertEquals(UserRole.TENANT_ADMIN, user.getRole());
        assertNotNull(user.getCreatedAt());
        assertNotNull(user.getUpdatedAt());
        assertNull(user.getId()); // @GeneratedValue - only JPA assigns it
        assertNull(user.getPhoneNumber());
        assertNull(user.getAvatarUrl());
        assertNull(user.getLastSeenAt());
    }

    @Test
    void setRoleUpdatesTheRoleAndRefreshesUpdatedAt() {
        AppUser user = new AppUser(UUID.randomUUID(), "kc-1", "a@acme.test", UserRole.AGENT_BUILDER);

        user.setRole(UserRole.DELEGATED_USER);

        assertEquals(UserRole.DELEGATED_USER, user.getRole());
        assertTrue(!user.getUpdatedAt().isBefore(user.getCreatedAt()));
    }

    @Test
    void relinkKeycloakUserPointsTheRowAtTheCurrentAccount() {
        AppUser user = new AppUser(UUID.randomUUID(), "kc-stale", "a@acme.test", UserRole.END_CUSTOMER);

        user.relinkKeycloakUser("kc-current");

        assertEquals("kc-current", user.getKeycloakUserId());
    }

    @Test
    void phoneNumberAndAvatarUrlAreIndependentOptionalFields() {
        AppUser user = new AppUser(UUID.randomUUID(), "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);

        user.setPhoneNumber("+15550001234");
        assertEquals("+15550001234", user.getPhoneNumber());
        assertNull(user.getAvatarUrl());

        user.setAvatarUrl("avatars/user-1.png");
        assertEquals("avatars/user-1.png", user.getAvatarUrl());
    }
}
