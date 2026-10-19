package com.agenticai.core.common.diagnostics;

/**
 * The four explanation depths {@link FailureExplanationService} always produces
 * together in one {@link FailureExplanation}. This enum is not used to select a
 * single tier from the primary contract (see {@link FailureExplanationService#explain}
 * javadoc for why all four are returned at once) - it exists as a stable, typed
 * vocabulary for any future single-tier convenience method or UI affordance (e.g. a
 * tier picker) that wants to refer to one of these four without stringly-typing it.
 */
public enum ExplanationTier {
    BASIC,
    INTERMEDIATE,
    TECHNICAL,
    EXPERT_TECHNICAL
}
