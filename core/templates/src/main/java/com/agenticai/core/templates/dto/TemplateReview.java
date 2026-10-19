package com.agenticai.core.templates.dto;

import java.time.Instant;

/**
 * Everything the platform learned about a template when it was submitted: the rule engine's report
 * plus the AI reviewer's verdict. Persisted as JSON on the template and shown to the reviewing
 * admin and the owner.
 */
public record TemplateReview(
        int standardVersion,
        TemplateReadinessReport rules,
        TemplateAiReview ai,
        Instant reviewedAt) {}
