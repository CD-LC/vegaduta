package com.agenticai.core.agents.demo.studio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable record of one studio workflow run (V339): status, input,
 * output and the final step trace. The live progress goes over SSE from
 * {@link DemoStudioWorkflowRunner}; this row is what "recent runs" shows
 * after a reload and what makes an unanswered approval visible as such.
 */
@Entity
@Table(name = "demo_studio_run", schema = "control_plane")
public class DemoStudioRun {

    public enum Status { RUNNING, AWAITING_APPROVAL, COMPLETED, STOPPED, REJECTED, FAILED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "input_text", columnDefinition = "text")
    private String inputText;

    @Column(name = "output_text", columnDefinition = "text")
    private String outputText;

    @Column(name = "trace_json", nullable = false, columnDefinition = "text")
    private String traceJson = "[]";

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected DemoStudioRun() {
    }

    public DemoStudioRun(UUID workflowId, UUID sessionId, String inputText, Instant now) {
        this.workflowId = workflowId;
        this.sessionId = sessionId;
        this.inputText = inputText;
        this.status = Status.RUNNING.name();
        this.startedAt = now;
    }

    public void progress(Status status, String traceJson) {
        this.status = status.name();
        this.traceJson = traceJson;
    }

    public void finish(Status status, String outputText, String traceJson, Instant now) {
        this.status = status.name();
        this.outputText = outputText;
        this.traceJson = traceJson;
        this.finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getStatus() {
        return status;
    }

    public String getInputText() {
        return inputText;
    }

    public String getOutputText() {
        return outputText;
    }

    public String getTraceJson() {
        return traceJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
