package com.agenticai.core.quality.calibration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
import java.util.UUID;

/**
 * One calibration RUN of a {@link JudgeCalibrationSet}: the statistics only, never the labels or the
 * model answers. {@code kappa}, {@code orderSwapConsistency} and {@code rawAgreement} are null when undefined
 * (null is not 0 - see {@link JudgeCalibration}). Rows are append-only so drift is visible over time.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class JudgeCalibrationResult {
    private UUID id = UUID.randomUUID();
    private UUID setId;
    private UUID agentId;
    private String judgeModel;
    private String agentModel;
    private int n;
    private int usable;
    private int consistent;
    private Double kappa;
    private Double orderSwapConsistency;
    private Double rawAgreement;
    private String interpretation;
    private String selfPreferenceWarning;
    private String createdBy;
    private Instant createdAt;

    protected JudgeCalibrationResult() {
        // Jackson
    }

    public JudgeCalibrationResult(
        UUID setId, UUID agentId, String judgeModel, String agentModel, int n, int usable, int consistent,
        Double kappa, Double orderSwapConsistency, Double rawAgreement, String interpretation,
        String selfPreferenceWarning, String createdBy
    ) {
        this.setId = setId;
        this.agentId = agentId;
        this.judgeModel = judgeModel;
        this.agentModel = agentModel;
        this.n = n;
        this.usable = usable;
        this.consistent = consistent;
        this.kappa = kappa;
        this.orderSwapConsistency = orderSwapConsistency;
        this.rawAgreement = rawAgreement;
        this.interpretation = interpretation;
        this.selfPreferenceWarning = selfPreferenceWarning;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSetId() {
        return setId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public int getN() {
        return n;
    }

    public int getUsable() {
        return usable;
    }

    public int getConsistent() {
        return consistent;
    }

    public Double getKappa() {
        return kappa;
    }

    public Double getOrderSwapConsistency() {
        return orderSwapConsistency;
    }

    public Double getRawAgreement() {
        return rawAgreement;
    }

    public String getInterpretation() {
        return interpretation;
    }

    public String getSelfPreferenceWarning() {
        return selfPreferenceWarning;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
