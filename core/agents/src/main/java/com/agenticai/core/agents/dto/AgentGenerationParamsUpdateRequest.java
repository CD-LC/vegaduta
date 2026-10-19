package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * PATCH /api/agents/{id}/generation-params - lets a customer tune an
 * existing agent's temperature/topP/maxTokens after creation, not just at
 * creation time (direct feedback: "customer should be able to select the
 * temperature, p and all other details they can set on agents"). All three
 * are required together (not individually optional like
 * {@link AgentCreateRequest}'s) - see {@code Agent#setGenerationParams}.
 *
 * <p>{@code maxTokens}'s upper bound (2026-08-10 incident: an agent with no
 * ceiling on this field got stored with a value large enough that
 * {@code FreeTierUsageLimiterService#checkAndReserveTokenBudget} - which
 * reserves this exact number against the tenant's daily hosted-pool token
 * budget BEFORE the call even runs - rejected every turn as "daily limit
 * reached" despite the tenant having used almost none of its actual quota;
 * the single reservation alone exceeded the remaining headroom) matches
 * {@code GenerationParamsInferenceService}'s own MAX_MAX_TOKENS ceiling for
 * the LLM-inferred path, so both ways of setting this field agree on what a
 * sane upper bound is.
 */
public record AgentGenerationParamsUpdateRequest(
    @NotNull(message = "maxTokens is required") @Positive @Max(value = 8192, message = "maxTokens must be at most 8192") Integer maxTokens,
    @NotNull(message = "temperature is required")
    @DecimalMin(value = "0.0", message = "temperature must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "temperature must be between 0 and 1")
    Double temperature,
    @NotNull(message = "topP is required")
    @DecimalMin(value = "0.0", message = "topP must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "topP must be between 0 and 1")
    Double topP,
    // Extra sampling controls (migration V331). null = leave unchanged;
    // stopSequences empty list = clear. Set clearSamplingParams=true to reset
    // all five to "unset" before applying any values sent alongside it.
    @Min(value = 1, message = "topK must be between 1 and 1000")
    @Max(value = 1000, message = "topK must be between 1 and 1000")
    Integer topK,
    @Size(max = 8, message = "stopSequences may hold at most 8 entries")
    List<@NotBlank(message = "stopSequences entries must not be blank")
        @Size(max = 64, message = "each stop sequence must be at most 64 characters") String> stopSequences,
    @DecimalMin(value = "-2.0", message = "frequencyPenalty must be between -2 and 2")
    @DecimalMax(value = "2.0", message = "frequencyPenalty must be between -2 and 2")
    Double frequencyPenalty,
    @DecimalMin(value = "-2.0", message = "presencePenalty must be between -2 and 2")
    @DecimalMax(value = "2.0", message = "presencePenalty must be between -2 and 2")
    Double presencePenalty,
    Integer seed,
    Boolean clearSamplingParams
) {
    /** Pre-V331 arity - the three classic sliders only; leaves the extra sampling controls untouched. */
    public AgentGenerationParamsUpdateRequest(Integer maxTokens, Double temperature, Double topP) {
        this(maxTokens, temperature, topP, null, null, null, null, null, null);
    }
}
