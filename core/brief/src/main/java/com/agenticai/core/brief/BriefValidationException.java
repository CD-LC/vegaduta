package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefValidationResult.Issue;
import java.util.List;

/**
 * A brief failed {@link BriefValidator} (on create/update) or the planner could not produce a
 * usable one. Carries the failing section(s) so the UI can point at them; HTTP 422.
 */
public class BriefValidationException extends RuntimeException {

    private final List<Issue> errors;

    public BriefValidationException(List<Issue> errors) {
        super("The brief is not valid: " + summarize(errors));
        this.errors = List.copyOf(errors);
    }

    public BriefValidationException(String section, String message) {
        this(List.of(new Issue(section, message)));
    }

    public List<Issue> getErrors() {
        return errors;
    }

    private static String summarize(List<Issue> errors) {
        return errors.isEmpty() ? "unknown" : errors.get(0).toString() + (errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : "");
    }
}
