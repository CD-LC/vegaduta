package com.agenticai.core.providers.modelcost;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One costed model (migration V263). Read-only from the application's side:
 * rows are seeded by the migration and edited by an operator with SQL - see
 * {@link MediaModelCostService} for how they are ordered and used.
 */
@Entity
@Table(name = "media_model_cost", schema = "control_plane")
public class MediaModelCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "modality", nullable = false, length = 16)
    private String modality;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit;

    @Column(name = "unit_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal unitCostUsd;

    @Column(name = "typical_call_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal typicalCallCostUsd;

    @Column(name = "quality_rank", nullable = false)
    private int qualityRank;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "source_note", columnDefinition = "TEXT")
    private String sourceNote;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MediaModelCost() {
        // JPA
    }

    public String getModality() {
        return modality;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelId() {
        return modelId;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitCostUsd() {
        return unitCostUsd;
    }

    public BigDecimal getTypicalCallCostUsd() {
        return typicalCallCostUsd;
    }

    public int getQualityRank() {
        return qualityRank;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
