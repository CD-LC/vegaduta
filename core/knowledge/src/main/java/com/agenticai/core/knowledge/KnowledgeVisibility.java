package com.agenticai.core.knowledge;

/**
 * Who may read a {@link KnowledgeCollection} (migration V710).
 *
 * <ul>
 *   <li>{@link #TENANT} - every member of the tenant can search and download. The default, and the
 *       value of every collection that existed before the feature, so an upgrade changes nothing.
 *   <li>{@link #SHARED} - the owner, tenant/platform administrators and explicit grantees
 *       (VIEWER: search and download, EDITOR: also ingest and delete documents).
 *   <li>{@link #PRIVATE} - the owner and tenant/platform administrators only. Existing grants are
 *       kept but dormant, so flipping back to SHARED restores them.
 * </ul>
 */
public enum KnowledgeVisibility {
    PRIVATE,
    TENANT,
    SHARED;

    /** Null (a mocked or pre-V710 row) reads as {@link #TENANT}: the pre-feature behaviour. */
    public static KnowledgeVisibility orDefault(KnowledgeVisibility value) {
        return value == null ? TENANT : value;
    }
}
