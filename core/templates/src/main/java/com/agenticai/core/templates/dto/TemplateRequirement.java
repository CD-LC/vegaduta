package com.agenticai.core.templates.dto;

/**
 * One thing an importer must have (or switch on) for an imported template to work as designed.
 * Derived at read time from the sanitised config, so every template - platform-seeded or community
 * - reports it without a migration.
 *
 * @param type what kind of requirement this is (stable machine id)
 * @param id the specific item (an MCP server id, a tool name) or {@code null}
 * @param label short human label, e.g. "MCP server: office-ally"
 * @param detail one sentence on why the template needs it
 * @param required {@code true} = the template does not work without it; {@code false} = works, but
 *     better with it
 * @param setupPath in-app route where the importer activates it, or {@code null} when nothing needs
 *     activating (built-in capability)
 */
public record TemplateRequirement(
        Type type, String id, String label, String detail, boolean required, String setupPath) {
    public enum Type {
        MCP_SERVER,
        GOOGLE_CONNECTION,
        KNOWLEDGE_BASE,
        CODE_INTEL,
        CODE_SANDBOX,
        MEMORY,
        HUMAN_APPROVAL,
        MODEL_KEY,
        VOICE_MODEL,
        VIDEO_MODEL,
        BUILT_IN_TOOL
    }
}
