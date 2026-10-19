package com.agenticai.core.agents.job;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * No such job for this agent - thrown both for a job id that does not exist at
 * all and for one that exists but belongs to a DIFFERENT agent than the path's
 * {@code agentId}. The two are deliberately indistinguishable to the caller:
 * same anti-enumeration posture as {@code ResourceAccessDeniedException} and
 * {@code WorkflowRunRepository#findByIdAndWorkflowId}, so probing job ids
 * against an agent you legitimately own leaks nothing about jobs you do not.
 *
 * <p><b>Extends {@link NoSuchElementException} on purpose</b>, rather than
 * being a standalone {@code RuntimeException} plus a new {@code
 * GlobalExceptionHandler} entry. {@code GlobalExceptionHandler} already maps
 * {@code NoSuchElementException} to a 404 carrying {@code getMessage()}, and it
 * also declares an {@code @ExceptionHandler(Exception.class)} catch-all that
 * runs BEFORE Spring's own {@code ResponseStatusExceptionResolver} - so a
 * {@code @ResponseStatus}-annotated exception would be swallowed into an opaque
 * 500 AND recorded as a spurious PlatformIssue. Subclassing an already-mapped
 * type gets the correct status with no edit to a file this workstream does not
 * own, the same reasoning {@code PerUserBudgetExhaustedException} documents for
 * extending {@code TenantLimitExceededException}.
 */
public class AgentJobNotFoundException extends NoSuchElementException {

    public AgentJobNotFoundException(UUID jobId) {
        super("No agent job found with id " + jobId);
    }
}
