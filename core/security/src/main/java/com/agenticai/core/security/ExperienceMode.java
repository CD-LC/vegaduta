package com.agenticai.core.security;

/**
 * How much manual configuration a tenant (or a single agent within it) wants
 * to deal with - the answer to "isn't this too much and confusing?" for
 * non-technical customers.
 *
 * <ul>
 *   <li>{@link #SIMPLE} - the platform decides. New agents are created with
 *       every currently-enabled tool available (no manual allow-list) and
 *       the knowledge base attached automatically, then {@code
 *       ToolRelevanceSelector} narrows what's actually offered to the model
 *       on each turn - the automatic-selection engine this platform already
 *       runs (see that class's javadoc) IS "the engine that builds
 *       everything required" the person is asking for; Simple mode is what
 *       exposes that as the default instead of an opt-in. The manual
 *       tool-allow-list / knowledge-collection-restriction editors are
 *       hidden for agents in this mode, not merely defaulted.
 *   <li>{@link #ADVANCED} - the person configures MCP servers, per-agent
 *       tool access, and knowledge-collection scoping by hand, same as
 *       today's behavior before this mode existed.
 * </ul>
 *
 * <p>Set tenant-wide at signup (the default every new agent inherits) and
 * overridable per agent at creation time - see {@code Tenant.experienceMode}
 * and {@code Agent.experienceMode}.
 */
public enum ExperienceMode {
    SIMPLE,
    ADVANCED
}
