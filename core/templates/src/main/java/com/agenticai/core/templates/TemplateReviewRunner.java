package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateAiReview;
import com.agenticai.core.templates.dto.TemplateReadinessReport;
import com.agenticai.core.templates.dto.TemplateReview;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Runs the template standard against a template: the deterministic readiness rules first, then
 * (only when they pass, and only if asked) the AI reviewer. Shared by {@link
 * CommunityTemplateService#submit} and {@link CommunityTemplateService#validate}.
 */
final class TemplateReviewRunner {

    private final TemplateSnapshotService snapshotService;
    private final TemplateReadinessService readinessService;
    private final TemplateAiReviewService aiReviewService;
    private final CommunityTemplateMapper mapper;

    TemplateReviewRunner(
            TemplateSnapshotService snapshotService,
            TemplateReadinessService readinessService,
            TemplateAiReviewService aiReviewService,
            CommunityTemplateMapper mapper) {
        this.snapshotService = snapshotService;
        this.readinessService = readinessService;
        this.aiReviewService = aiReviewService;
        this.mapper = mapper;
    }

    TemplateReview run(CommunityTemplate template, boolean withAi) {
        JsonNode config = mapper.readConfig(template.getConfigJson());
        List<String> userTags = snapshotService.parseTags(template.getTagsJson());
        TemplateReadinessReport rules =
                readinessService.assess(
                        template.getKind(),
                        template.getTitle(),
                        template.getSummary(),
                        userTags,
                        template.getCategory(),
                        config);
        TemplateAiReview ai;
        if (!withAi) {
            ai = TemplateAiReview.unavailable("AI review not requested.");
        } else if (!rules.blockers().isEmpty()) {
            // Don't spend a model call on something that cannot be submitted yet.
            ai = TemplateAiReview.unavailable("The AI review runs once the automatic checks pass.");
        } else {
            ai =
                    aiReviewService.review(
                            template.getKind(),
                            template.getTitle(),
                            template.getSummary(),
                            userTags,
                            template.getCategory(),
                            config,
                            rules.requirements());
        }
        return new TemplateReview(TemplateStandard.VERSION, rules, ai, Instant.now());
    }

    static TemplateReadinessReport mergeAi(
            TemplateReadinessReport rules, TemplateAiReview ai) {
        List<TemplateReadinessReport.Issue> blockers = new java.util.ArrayList<>(rules.blockers());
        blockers.addAll(ai.blockers());
        List<TemplateReadinessReport.Issue> advisories =
                new java.util.ArrayList<>(rules.advisories());
        advisories.addAll(ai.advisories());
        return new TemplateReadinessReport(
                false,
                List.copyOf(blockers),
                List.copyOf(advisories),
                rules.requirements(),
                rules.derivedTags());
    }

    String mergedTags(CommunityTemplate template, List<String> derived) {
        java.util.LinkedHashSet<String> all =
                new java.util.LinkedHashSet<>(snapshotService.parseTags(template.getTagsJson()));
        all.addAll(derived);
        return snapshotService.writeTags(new java.util.ArrayList<>(all));
    }
}
