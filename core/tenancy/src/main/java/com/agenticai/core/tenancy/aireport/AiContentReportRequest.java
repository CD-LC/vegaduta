package com.agenticai.core.tenancy.aireport;

/**
 * Wire body of {@code POST /api/ai-reports} - see
 * the app-store compliance requirements (described inline). Enum-valued fields are
 * plain strings so an unknown value becomes a clean 400 from the service
 * instead of a Jackson deserialisation error.
 */
public record AiContentReportRequest(
    String surface,
    String reason,
    String comment,
    String content,
    String prompt,
    String agentId,
    String sessionId,
    String messageRef,
    Boolean onDevice,
    String appPlatform,
    String appVersion
) {
}
