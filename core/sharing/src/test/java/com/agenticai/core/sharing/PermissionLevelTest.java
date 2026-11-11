package com.agenticai.core.sharing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link PermissionLevel}'s DECLARATION ORDER is load-bearing: {@code
 * ResourceAccessService#requireAccess} compares grants via {@link Enum#compareTo}, so RUNNER <
 * VIEWER < EDITOR must hold forever - a reorder silently grants higher privilege than every stored
 * grant intends. This test pins that contract so an accidental reorder fails here instead of in
 * production.
 */
class PermissionLevelTest {

    @Test
    void declarationOrderIsIncreasingPrivilege() {
        assertTrue(PermissionLevel.RUNNER.compareTo(PermissionLevel.VIEWER) < 0);
        assertTrue(PermissionLevel.VIEWER.compareTo(PermissionLevel.EDITOR) < 0);
        assertTrue(PermissionLevel.RUNNER.compareTo(PermissionLevel.EDITOR) < 0);
        assertEquals(0, PermissionLevel.RUNNER.ordinal());
        assertEquals(1, PermissionLevel.VIEWER.ordinal());
        assertEquals(2, PermissionLevel.EDITOR.ordinal());
    }

    @Test
    void exactlyThreeLevelsInThatOrder() {
        assertArrayEquals(
                new PermissionLevel[] {
                    PermissionLevel.RUNNER, PermissionLevel.VIEWER, PermissionLevel.EDITOR
                },
                PermissionLevel.values());
    }
}
