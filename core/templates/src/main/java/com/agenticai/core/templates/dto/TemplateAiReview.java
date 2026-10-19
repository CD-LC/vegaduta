package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.dto.TemplateReadinessReport.Issue;
import java.util.List;

/**
 * The AI reviewer's verdict on a template, judged against the published standard. It is an aid,
 * never an approval: the platform team still decides.
 *
 * @param status PASSED (no findings), ISSUES (see lists), UNAVAILABLE (the model could not be
 *     reached or answered unusably - submission proceeds and the admin is told)
 * @param summary one or two sentences for the author and the reviewing admin
 */
public record TemplateAiReview(
        Status status, String summary, List<Issue> blockers, List<Issue> advisories) {
    public enum Status {
        PASSED,
        ISSUES,
        UNAVAILABLE
    }

    public static TemplateAiReview unavailable(String why) {
        return new TemplateAiReview(Status.UNAVAILABLE, why, List.of(), List.of());
    }
}
