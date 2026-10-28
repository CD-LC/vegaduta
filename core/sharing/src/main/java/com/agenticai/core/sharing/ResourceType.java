package com.agenticai.core.sharing;

/**
 * The resource kinds this module governs access to. Deliberately just an
 * enum, not a reference to {@code Agent}/{@code WorkflowDefinition} - this
 * module has zero dependency on {@code core/agents}/{@code core/workflow}
 * (see this module's {@code pom.xml} description), so it can never know
 * about those entity types directly.
 */
public enum ResourceType {
    AGENT,
    WORKFLOW,
    /**
     * A knowledge-base collection (owned by {@code core/knowledge}, which supplies the {@link
     * ResourceOwnerLookup}). Levels mean: {@link PermissionLevel#VIEWER} search and download,
     * {@link PermissionLevel#EDITOR} ingest and delete documents; {@link PermissionLevel#RUNNER}
     * grants nothing on a collection. Added by tenant migration V710 (the {@code
     * chk_resource_grant_type} constraint).
     */
    KNOWLEDGE_COLLECTION
}
