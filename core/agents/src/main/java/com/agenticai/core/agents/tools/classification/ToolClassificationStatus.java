package com.agenticai.core.agents.tools.classification;

/**
 * Lifecycle of a stored {@code tool_classification} row.
 *
 * <p>Two values, and the second one is the reason this is an enum rather than a
 * boolean {@code enabled} column: a quarantined tool and an unclassified tool are
 * both blocked but are not the same problem, and the console has to say which.
 */
public enum ToolClassificationStatus {

    /** The declaration is in force. {@link ToolClassificationRegistry} resolves it normally. */
    ACTIVE,

    /**
     * The tool is blocked pending human action, regardless of what the rest of
     * the row says.
     *
     * <p>This is the state an MCP-discovered tool lands in when its server's tool
     * manifest changes after approval (a renamed tool, a rewritten description -
     * the description is prompt content, so a silent rewrite is a live injection
     * vector), and the state a platform admin puts a tool into after an incident.
     * It resolves to {@link ToolClassificationDecision.Reason#QUARANTINED}, which
     * is deliberately not clearable by re-declaring the classification: the point
     * of quarantine is that somebody has to look.
     */
    QUARANTINED
}
