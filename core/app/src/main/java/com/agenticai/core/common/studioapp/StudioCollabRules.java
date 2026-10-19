package com.agenticai.core.common.studioapp;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure decision logic for studio project collaboration: which role may do
 * what, and validation of comment bodies. Free of persistence so it is unit
 * testable without a database. See {@link StudioProjectService} for the
 * enforcement points.
 */
final class StudioCollabRules {

    static final int MAX_COMMENT_CHARS = 2000;
    static final int MAX_MEMBERS_PER_PROJECT = 25;
    static final int MAX_COMMENTS_PER_PROJECT = 500;

    /** Effective role of the caller on one project. */
    enum Role {
        OWNER, EDITOR, VIEWER;

        boolean canRead() {
            return true;
        }

        /** PUT new versions (and therefore create the project's history on a shared copy). */
        boolean canWriteVersions() {
            return this == OWNER || this == EDITOR;
        }

        /** Rename, change settings (connections/publish/auth/ai), delete the project. */
        boolean canAdminister() {
            return this == OWNER;
        }

        boolean canManageMembers() {
            return this == OWNER;
        }

        /** Every member, including a viewer, may read and post comments. */
        boolean canComment() {
            return true;
        }
    }

    /** The role a member may be invited with (OWNER is never granted). */
    static Optional<Role> parseInviteRole(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "VIEWER" -> Optional.of(Role.VIEWER);
            case "EDITOR" -> Optional.of(Role.EDITOR);
            default -> Optional.empty();
        };
    }

    /** A comment may be removed by its author or by the project owner. */
    static boolean canDeleteComment(Role role, String commentAuthor, String caller) {
        if (role == null || caller == null) {
            return false;
        }
        return role == Role.OWNER || caller.equals(commentAuthor);
    }

    /** Any member may resolve/unresolve a thread - it is a shared to-do, not authorship. */
    static boolean canResolveComment(Role role) {
        return role != null;
    }

    /**
     * Trims a comment body. Returns empty when blank.
     *
     * @throws IllegalArgumentException {@code comment_too_long} when over the cap
     */
    static Optional<String> cleanBody(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String t = raw.strip();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        if (t.length() > MAX_COMMENT_CHARS) {
            throw new IllegalArgumentException("comment_too_long");
        }
        return Optional.of(t);
    }

    /** Lower-cased, trimmed, minimally shaped email; empty when it cannot be one. */
    static Optional<String> cleanEmail(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String t = raw.strip().toLowerCase(Locale.ROOT);
        int at = t.indexOf('@');
        if (t.length() > 254 || at < 1 || at != t.lastIndexOf('@') || at == t.length() - 1 || t.contains(" ")) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    private StudioCollabRules() {
    }
}
