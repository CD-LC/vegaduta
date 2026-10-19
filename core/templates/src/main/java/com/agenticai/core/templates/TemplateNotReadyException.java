package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateReadinessReport;

/**
 * Thrown by {@code submit} when a template fails the marketplace quality bar. Mapped to HTTP 422 in
 * {@code GlobalExceptionHandler}; the full report rides along so the UI can show every blocker at
 * once instead of one per attempt.
 */
public class TemplateNotReadyException extends RuntimeException {

    private final transient TemplateReadinessReport report;

    public TemplateNotReadyException(TemplateReadinessReport report) {
        super(
                "This template isn't ready to submit yet: "
                        + report.blockers().size()
                        + " issue(s) to fix first. "
                        + report.blockers().get(0).message());
        this.report = report;
    }

    public TemplateReadinessReport getReport() {
        return report;
    }
}
