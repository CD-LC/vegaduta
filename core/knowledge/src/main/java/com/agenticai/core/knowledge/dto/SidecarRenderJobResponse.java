package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A render job on the sidecar - the reply to {@code POST /render} (202) and to
 * {@code GET /render/{jobId}}.
 *
 * <p>{@code result} is deliberately an untyped map rather than a record. It is
 * the sidecar's own render summary (paths, duration, frame geometry, byte
 * count, and optionally the mp4 as base64), and its shape is expected to grow
 * as the render gains features; pinning it here would mean a Java change for
 * every new field the Python side reports, with nothing gained - the workflow
 * node passes it onward as JSON either way.
 *
 * <p>{@code status} is one of {@code queued}, {@code running}, {@code completed},
 * {@code failed}. Only {@code completed} guarantees {@code result} is populated,
 * and only {@code failed} guarantees {@code error} is.
 */
public record SidecarRenderJobResponse(
    @JsonProperty("job_id") String jobId,
    String status,
    String detail,
    @JsonProperty("created_at") Double createdAt,
    @JsonProperty("started_at") Double startedAt,
    @JsonProperty("finished_at") Double finishedAt,
    String error,
    Map<String, Object> result,
    @JsonProperty("queue_depth") Integer queueDepth
) {

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }

    public boolean isTerminal() {
        return isCompleted() || isFailed();
    }
}
