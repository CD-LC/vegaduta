package com.agenticai.core.templates;

/**
 * What a {@link CommunityTemplate} is a design snapshot of. Mirrors {@code
 * core/sharing}'s {@code ResourceType} deliberately (AGENT/WORKFLOW) but is a
 * separate enum: this module is conceptually distinct from resource sharing
 * (see the contract doc) and must not couple its persisted values to another
 * module's enum ordering.
 */
public enum TemplateKind {
    AGENT,
    WORKFLOW
}
