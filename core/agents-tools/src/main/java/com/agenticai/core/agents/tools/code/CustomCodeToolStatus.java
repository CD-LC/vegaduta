package com.agenticai.core.agents.tools.code;

/**
 * Where a {@link CustomCodeTool} stands with the sandbox's validator.
 *
 * <p>Only {@link #VALIDATED} rows are ever handed to an agent (see {@code
 * CustomCodeToolService#listRunnable}). A {@link #REJECTED} row is still persisted deliberately -
 * the tenant needs to see the compiler/parser errors in the editor to fix them, and throwing the
 * source away on a failed save would make that impossible. {@link #DRAFT} is the transient state a
 * row carries only if it was written before a validation verdict came back (the sandbox being
 * unreachable mid-save); it is never runnable either.
 */
public enum CustomCodeToolStatus {
    DRAFT,
    VALIDATED,
    REJECTED
}
