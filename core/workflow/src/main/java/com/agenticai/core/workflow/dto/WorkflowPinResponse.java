package com.agenticai.core.workflow.dto;

/**
 * One pin as the API returns it. {@code pinnedAt} is an ISO-8601 string and {@code data} is the pinned output
 * text, or null when the caller may list pins but not read the pinned values (RUNNER and VIEWER; pinned data
 * can hold PII from real runs). {@code sizeChars} is always set so the UI can show the size of a hidden pin.
 */
public record WorkflowPinResponse(
    String nodeId,
    String source,
    String sourceRunId,
    String pinnedBy,
    String pinnedAt,
    int sizeChars,
    String configHash,
    String data
) {
}
