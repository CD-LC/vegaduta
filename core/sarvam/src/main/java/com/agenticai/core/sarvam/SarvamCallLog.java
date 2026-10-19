package com.agenticai.core.sarvam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One real call to any of Stage 3's five Sarvam capability clients (translate/
 * speech-to-text/text-to-speech/transliteration/language-ID) - success or
 * failure, same "log every call, not just the successful ones" posture
 * {@code LlmCallLog} already established for chat completions.
 *
 * <p>Deliberately a separate table from {@code core.chat.LlmCallLog} rather
 * than overloading it: these aren't chat/token/cost-per-token calls (no
 * prompt/completion token counts, no {@code ProviderName}, some capabilities
 * like transliteration and language-ID don't even have a "model" concept),
 * and forcing them into that table's shape would mean nullable columns that
 * make sense for one row type but not the other, plus a JOIN a reader of
 * "show me my LLM chat spend" query never wants. Per the build plan's Stage
 * 3 task list: "a lightweight {@code SarvamCallLog} table, same shape, is
 * worth it here rather than overloading the LLM-specific table."
 *
 * <p>Only request/response <b>metadata</b> is recorded here - never the
 * actual translated/transcribed/synthesized text or audio bytes, matching
 * {@code LlmMetricsAdvisor}/{@code LlmCallLogService}'s existing "log
 * volume and outcome, not the payload" convention (the real content is
 * already visible wherever the caller displays it - an agent tool trace,
 * a workflow run's node output - this table exists purely for the "usage
 * is visible in cost/volume terms" telemetry goal the build plan calls out).
 *
 * <p>No {@code tenant_id} column, same as {@code SarvamCredential}/{@code
 * LlmCallLog}/every other tenant-scoped table in this codebase - schema-
 * per-tenant Hibernate multi-tenancy means this maps to a physically
 * different table per tenant schema, so every row here already belongs to
 * exactly one tenant by construction.
 */
@Entity
@Table(name = "sarvam_call_log")
public class SarvamCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SarvamCallCapability capability;

    /** Nullable: transliteration and language-ID have no model parameter at all. */
    @Column(length = 64)
    private String model;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    protected SarvamCallLog() {
        // JPA
    }

    public SarvamCallLog(
        SarvamCallCapability capability,
        String model,
        boolean success,
        String errorMessage,
        long latencyMs,
        Instant startedAt
    ) {
        this.capability = capability;
        this.model = model;
        this.success = success;
        this.errorMessage = errorMessage;
        this.latencyMs = latencyMs;
        this.startedAt = startedAt;
    }

    public UUID getId() {
        return id;
    }

    public SarvamCallCapability getCapability() {
        return capability;
    }

    public String getModel() {
        return model;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
