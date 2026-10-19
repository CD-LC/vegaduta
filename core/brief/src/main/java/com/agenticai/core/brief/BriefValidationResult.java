package com.agenticai.core.brief;

import java.util.List;

/**
 * Outcome of {@link BriefValidator#validate}. {@code errors} block saving/applying and name the
 * failing section ({@code agents[a1].systemPrompt}, {@code workflows[w1].graph}, {@code limits}, ...)
 * so the UI can point at it; {@code warnings} are honest caveats that do not block;
 * {@code missingPermissions} are {@code PERM_*} authorities the caller lacks that apply would need
 * (apply degrades on those rather than failing - contract section 2).
 */
public record BriefValidationResult(
    List<Issue> errors, List<String> warnings, List<String> missingPermissions
) {

    public BriefValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        missingPermissions = missingPermissions == null ? List.of() : List.copyOf(missingPermissions);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    /** One validation failure: {@code section} is a JSON-path-like locator, {@code message} a human sentence. */
    public record Issue(String section, String message) {
        @Override
        public String toString() {
            return section + ": " + message;
        }
    }
}
