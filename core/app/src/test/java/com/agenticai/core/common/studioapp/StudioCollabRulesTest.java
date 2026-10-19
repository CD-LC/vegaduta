package com.agenticai.core.common.studioapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.common.studioapp.StudioCollabRules.Role;
import org.junit.jupiter.api.Test;

class StudioCollabRulesTest {

    @Test
    void ownerCanDoEverything() {
        assertTrue(Role.OWNER.canRead());
        assertTrue(Role.OWNER.canWriteVersions());
        assertTrue(Role.OWNER.canAdminister());
        assertTrue(Role.OWNER.canManageMembers());
        assertTrue(Role.OWNER.canComment());
    }

    @Test
    void editorWritesVersionsButCannotAdministerOrShare() {
        assertTrue(Role.EDITOR.canWriteVersions());
        assertFalse(Role.EDITOR.canAdminister());
        assertFalse(Role.EDITOR.canManageMembers());
        assertTrue(Role.EDITOR.canComment());
    }

    @Test
    void viewerIsReadOnlyButCanComment() {
        assertTrue(Role.VIEWER.canRead());
        assertFalse(Role.VIEWER.canWriteVersions());
        assertFalse(Role.VIEWER.canAdminister());
        assertFalse(Role.VIEWER.canManageMembers());
        assertTrue(Role.VIEWER.canComment());
    }

    @Test
    void inviteRoleParsingNeverYieldsOwner() {
        assertEquals(Role.EDITOR, StudioCollabRules.parseInviteRole(" editor ").orElseThrow());
        assertEquals(Role.VIEWER, StudioCollabRules.parseInviteRole("VIEWER").orElseThrow());
        assertTrue(StudioCollabRules.parseInviteRole("OWNER").isEmpty());
        assertTrue(StudioCollabRules.parseInviteRole("admin").isEmpty());
        assertTrue(StudioCollabRules.parseInviteRole(null).isEmpty());
    }

    @Test
    void commentDeletionIsAuthorOrOwner() {
        assertTrue(StudioCollabRules.canDeleteComment(Role.OWNER, "a", "o"));
        assertTrue(StudioCollabRules.canDeleteComment(Role.EDITOR, "a", "a"));
        assertTrue(StudioCollabRules.canDeleteComment(Role.VIEWER, "a", "a"));
        assertFalse(StudioCollabRules.canDeleteComment(Role.EDITOR, "a", "b"));
        assertFalse(StudioCollabRules.canDeleteComment(Role.VIEWER, "a", "b"));
        assertFalse(StudioCollabRules.canDeleteComment(null, "a", "a"));
        assertFalse(StudioCollabRules.canDeleteComment(Role.OWNER, "a", null));
    }

    @Test
    void anyMemberMayResolveButNonMembersMayNot() {
        assertTrue(StudioCollabRules.canResolveComment(Role.VIEWER));
        assertFalse(StudioCollabRules.canResolveComment(null));
    }

    @Test
    void commentBodyIsTrimmedAndBounded() {
        assertEquals("hi", StudioCollabRules.cleanBody("  hi \n").orElseThrow());
        assertTrue(StudioCollabRules.cleanBody("   ").isEmpty());
        assertTrue(StudioCollabRules.cleanBody(null).isEmpty());
        assertEquals(2000, StudioCollabRules.cleanBody("x".repeat(2000)).orElseThrow().length());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> StudioCollabRules.cleanBody("x".repeat(2001)));
        assertEquals("comment_too_long", e.getMessage());
    }

    @Test
    void emailShapeCheck() {
        assertEquals("a@b.co", StudioCollabRules.cleanEmail("  A@B.co ").orElseThrow());
        assertTrue(StudioCollabRules.cleanEmail("nope").isEmpty());
        assertTrue(StudioCollabRules.cleanEmail("a@@b").isEmpty());
        assertTrue(StudioCollabRules.cleanEmail("@b.co").isEmpty());
        assertTrue(StudioCollabRules.cleanEmail("a@").isEmpty());
        assertTrue(StudioCollabRules.cleanEmail("a b@c.d").isEmpty());
        assertTrue(StudioCollabRules.cleanEmail(null).isEmpty());
    }
}
