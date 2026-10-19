package com.agenticai.core.tenancy.aireport;

/** Body of {@code PATCH /api/platform/ai-reports/{id}}. */
public record AiContentReportReviewRequest(String status, String reviewNote) {
}
