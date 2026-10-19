package com.agenticai.core.rl.web.dto;

import com.agenticai.core.rl.domain.BanditAlgorithm;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Admin toggle for one per-dimension policy ({@code PUT /api/admin/rl/agents/
 * {agentId}/policies/{dimension}}): whether the bandit explores this dimension
 * at all, its epsilon exploration floor, and which arm-selection algorithm to
 * use. {@link #explorationRate}/{@link #algorithm} are both optional (null
 * leaves the existing value unchanged); {@code explorationRate}, when
 * present, must be a probability in [0,1] - {@code PolicyStore.updatePolicy}
 * clamps it defensively regardless.
 */
public record PolicyUpdateRequest(
    boolean enabled,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal explorationRate,
    BanditAlgorithm algorithm
) {
}
