package com.agenticai.core.sharing;

/**
 * A grantee's level of access to a shared Agent or Workflow.
 *
 * <p><b>Ordinal order matters</b> - increasing privilege, low to high. {@link
 * ResourceAccessService#requireAccess} compares a grant's level against the
 * required level via {@link #compareTo}, so this enum's DECLARATION ORDER is
 * load-bearing: never reorder these constants, and any new level must be
 * inserted at the correct privilege rank, not appended.
 *
 * <ul>
 *   <li>{@link #RUNNER} - can execute/chat with the resource, no visibility
 *       into its configuration (system prompt, tools, provider/model, the
 *       workflow graph itself).</li>
 *   <li>{@link #VIEWER} - {@link #RUNNER} plus can see the full
 *       configuration, read-only.</li>
 *   <li>{@link #EDITOR} - {@link #VIEWER} plus can modify configuration
 *       (tools, prompts, the workflow graph).</li>
 * </ul>
 *
 * <p>The resource's owner (a {@code createdBy} match) and a tenant-admin/
 * platform-admin caller always have effective EDITOR-or-higher access
 * regardless of any grant row here - see {@link ResourceAccessService}.
 */
public enum PermissionLevel {
    RUNNER,
    VIEWER,
    EDITOR
}
