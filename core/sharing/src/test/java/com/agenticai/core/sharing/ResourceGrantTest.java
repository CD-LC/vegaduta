package com.agenticai.core.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Entity coverage for {@link ResourceGrant}: constructor defaults (active, self-stamped {@code
 * grantedAt}), the soft-revoke bookkeeping ({@code revokedAt}/{@code revokedBy} set rather than
 * the row deleted, so revocation history survives), and {@code updateLevel}'s re-grant semantics
 * used by the upsert path in {@code ResourceAccessService#grant}.
 */
class ResourceGrantTest {

    private static final UUID RESOURCE_ID = UUID.randomUUID();

    @Test
    void aFreshGrantIsActiveAndSelfTimestamped() {
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, "bob", PermissionLevel.VIEWER, "alice");

        assertTrue(grant.isActive());
        assertNull(grant.getRevokedAt());
        assertNull(grant.getRevokedBy());
        assertNotNull(grant.getGrantedAt());
        assertNull(grant.getId()); // @GeneratedValue - only JPA assigns it
        assertEquals(ResourceType.AGENT, grant.getResourceType());
        assertEquals(RESOURCE_ID, grant.getResourceId());
        assertEquals("bob", grant.getGranteeUserId());
        assertEquals(PermissionLevel.VIEWER, grant.getPermissionLevel());
        assertEquals("alice", grant.getGrantedBy());
    }

    @Test
    void revokeRecordsWhoAndWhenAndFlipsActive() {
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.WORKFLOW, RESOURCE_ID, "bob", PermissionLevel.RUNNER, "alice");

        grant.revoke("alice");

        assertFalse(grant.isActive());
        assertNotNull(grant.getRevokedAt());
        assertEquals("alice", grant.getRevokedBy());
    }

    @Test
    void updateLevelChangesTheLevelAndTheGranterOfRecord() {
        ResourceGrant grant =
                new ResourceGrant(
                        ResourceType.AGENT, RESOURCE_ID, "bob", PermissionLevel.RUNNER, "alice");

        grant.updateLevel(PermissionLevel.EDITOR, "admin-sub");

        assertEquals(PermissionLevel.EDITOR, grant.getPermissionLevel());
        assertEquals("admin-sub", grant.getGrantedBy());
        assertTrue(grant.isActive());
    }
}
