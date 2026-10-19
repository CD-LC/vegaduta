package com.agenticai.core.sharing;

/**
 * The two resource kinds this module governs access to. Deliberately just an
 * enum, not a reference to {@code Agent}/{@code WorkflowDefinition} - this
 * module has zero dependency on {@code core/agents}/{@code core/workflow}
 * (see this module's {@code pom.xml} description), so it can never know
 * about those entity types directly.
 */
public enum ResourceType {
    AGENT,
    WORKFLOW
}
