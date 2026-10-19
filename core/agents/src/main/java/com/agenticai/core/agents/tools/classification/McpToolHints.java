package com.agenticai.core.agents.tools.classification;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * The four safety hints an MCP server may attach to a tool (MCP spec 2025-06-18,
 * {@code Tool.annotations}: {@code readOnlyHint}, {@code destructiveHint},
 * {@code idempotentHint}, {@code openWorldHint}). Each is {@code null} when the
 * server did not state it - "absent" is a distinct, meaningful answer because the
 * spec gives each hint a different default.
 *
 * <p>These are <b>hints from a third party</b>, not facts: the spec itself says a
 * client must treat annotations from an untrusted server as untrusted. They are
 * therefore only ever used to TIGHTEN how the platform treats a tool (see
 * {@link McpAnnotationClassifier}), never to relax anything an administrator or the
 * platform declared.
 *
 * <p>Spec: https://modelcontextprotocol.io/specification/2025-06-18/server/tools#tool
 * and https://modelcontextprotocol.io/specification/2025-06-18/schema#toolannotations
 *
 * @param readOnly    {@code readOnlyHint}: the tool does not modify its environment (spec default false)
 * @param destructive {@code destructiveHint}: meaningful only when not read-only; the tool may perform
 *                    destructive updates rather than purely additive ones (spec default true)
 * @param idempotent  {@code idempotentHint}: calling it repeatedly with the same arguments has no
 *                    additional effect (spec default false)
 * @param openWorld   {@code openWorldHint}: the tool interacts with an open set of external entities
 *                    (the web, third-party systems) rather than a closed domain (spec default true)
 */
public record McpToolHints(Boolean readOnly, Boolean destructive, Boolean idempotent, Boolean openWorld) {

    /** No hint stated at all. */
    public static final McpToolHints NONE = new McpToolHints(null, null, null, null);

    /**
     * True when the server stated at least one hint that bears on risk: a read-only or
     * destructive hint, or an explicit {@code openWorldHint=true}. An annotations object
     * carrying only a {@code title}, an {@code idempotentHint} or {@code openWorldHint=false}
     * says nothing about whether the tool changes anything, so the tool is treated exactly
     * like an unannotated one (today's classification, unchanged).
     */
    public boolean hasRiskHint() {
        return readOnly != null || destructive != null || Boolean.TRUE.equals(openWorld);
    }

    /** Null-safe extraction from the SDK type; {@code null} annotations yield {@link #NONE}. */
    public static McpToolHints from(McpSchema.ToolAnnotations annotations) {
        if (annotations == null) {
            return NONE;
        }
        return new McpToolHints(
            annotations.readOnlyHint(),
            annotations.destructiveHint(),
            annotations.idempotentHint(),
            annotations.openWorldHint());
    }
}
