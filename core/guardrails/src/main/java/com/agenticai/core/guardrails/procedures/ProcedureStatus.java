package com.agenticai.core.guardrails.procedures;

/**
 * Lifecycle of a {@link Procedure}: only {@code ACTIVE} procedures are
 * injected into agent prompts and consulted by {@link
 * ProcedureGuardEvaluator}. {@code DRAFT} is editable authoring state;
 * {@code ARCHIVED} is retained for audit/history but inert - an archived
 * procedure stays attached to its agents (reattaching history is preserved)
 * but contributes nothing at runtime.
 */
public enum ProcedureStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
