package com.agenticai.core.workflow;

/**
 * A workflow's stored graph JSON ({@code workflow_definition.graph_json})
 * failed to deserialize back into a {@code WorkflowGraphDto} - data
 * corruption, or a backward-incompatible schema change, not anything the
 * caller did wrong. Previously a bare {@link IllegalStateException} thrown
 * from {@code WorkflowService#readGraph}, which fell through to
 * {@code GlobalExceptionHandler}'s generic catch-all and lost the specific
 * "this is a data-corruption problem" context. Given its own typed
 * exception so this genuinely-unexpected condition gets a clear
 * customer-facing message and, like the generic catch-all, a PlatformIssue
 * record for admin triage - see {@code GlobalExceptionHandler}.
 */
public class WorkflowGraphCorruptException extends RuntimeException {

    public WorkflowGraphCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
