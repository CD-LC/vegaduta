package com.agenticai.core.workflow.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AI workflow generator (#392 - direct feedback: "customer should be able to
 * type what he wants and automatically it should get [built] with the help
 * of AI using available tools, mcp, condition, output, Human in loop
 * approval everything"). One free-text instruction in, a full graph out -
 * see {@link GenerateGraphResponse} and {@code WorkflowGraphGeneratorService}.
 *
 * @param instruction the user's plain-language description of what the
 *     workflow should do, e.g. "triage incoming WhatsApp support messages,
 *     look up the answer in our knowledge base, and have a human approve
 *     refunds over $50 before replying".
 * @param currentGraph optional. The graph currently on the canvas (unsaved
 *     edits included). When it has at least one node the instruction is
 *     treated as a CHANGE to that graph - unrelated nodes and their ids are
 *     kept - instead of a description of a brand-new workflow. Omitted, null
 *     or empty reproduces the original one-shot behaviour exactly.
 */
public record GenerateGraphRequest(
    @NotBlank String instruction,
    WorkflowGraphDto currentGraph
) {}
