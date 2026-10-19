package com.agenticai.core.common.roi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of {@code /api/tenant/roi/**}. Every money figure is either recorded data or null.
 */
public final class RoiDtos {

    private RoiDtos() {}

    // ---- prices -----------------------------------------------------------------------------

    /** One stored price version. A null rate means "unknown". */
    public record PriceResponse(
            UUID id,
            String providerName,
            String modelPrefix,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal cacheReadPerMillion,
            LocalDate effectiveFrom,
            Instant setAt,
            String setBy,
            String note) {}

    public record PriceRequest(
            String providerName,
            String modelPrefix,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal cacheReadPerMillion,
            LocalDate effectiveFrom,
            String note) {}

    /** A provider/model that made calls in the window with no applicable price. */
    public record UnpricedModel(
            String providerName, String model, long calls, long totalTokens, String reason) {}

    public record PriceListResponse(
            List<PriceResponse> prices, List<UnpricedModel> unpricedModels, String currency) {}

    // ---- cost attribution ---------------------------------------------------------------------

    public record CostRow(
            String key,
            String label,
            long calls,
            long failedCalls,
            long pricedCalls,
            long unpricedCalls,
            long callsWithoutUsage,
            long promptTokens,
            long completionTokens,
            BigDecimal byokEstimatedCostUsd,
            BigDecimal platformHostedEstimatedCostUsd,
            BigDecimal estimatedCostUsd,
            boolean costComplete) {}

    public record CostReportResponse(
            LocalDate from,
            LocalDate to,
            String groupBy,
            String costBasis,
            List<CostRow> rows,
            CostRow total,
            long platformChargesMinor,
            String platformChargesCurrency) {}

    // ---- outcomes -----------------------------------------------------------------------------

    public record OutcomeRequest(
            String conversationKey, UUID agentId, String outcome, String channel, String note) {}

    public record OutcomeResponse(String conversationKey, String outcome, boolean recorded) {}

    // ---- assumptions --------------------------------------------------------------------------

    public record AssumptionRequest(
            BigDecimal valuePerResolution,
            String valueCurrency,
            BigDecimal minutesSavedPerResolution) {}

    public record AssumptionResponse(
            UUID agentId,
            BigDecimal valuePerResolution,
            String valueCurrency,
            BigDecimal minutesSavedPerResolution,
            Instant updatedAt,
            String basis) {}

    // ---- per-agent table ----------------------------------------------------------------------

    public record AgentRoiRow(
            UUID agentId,
            String agentName,
            long conversations,
            long resolved,
            long resolvedConfirmed,
            long resolvedAssumed,
            long resolvedByHuman,
            long handedToHuman,
            long abandoned,
            long unresolved,
            long humanCorrected,
            BigDecimal handoverRate,
            BigDecimal humanCorrectionRate,
            long approvalsDecided,
            long approvalsDenied,
            BigDecimal approvalDenialRate,
            BigDecimal byokEstimatedCostUsd,
            BigDecimal platformHostedEstimatedCostUsd,
            BigDecimal estimatedCostUsd,
            BigDecimal estimatedCostPerResolvedUsd,
            boolean costComplete,
            long platformChargesMinor,
            String platformChargesCurrency,
            BigDecimal valuePerResolution,
            String valueCurrency,
            BigDecimal minutesSavedPerResolution,
            BigDecimal valueTotal,
            BigDecimal minutesSavedTotal,
            BigDecimal netValue,
            BigDecimal roiPercent,
            String roiNote) {}

    public record AgentRoiResponse(
            LocalDate from,
            LocalDate to,
            boolean includeAssumedResolutions,
            String roiBasis,
            List<AgentRoiRow> agents) {}

    // ---- budget -------------------------------------------------------------------------------

    public record BudgetResponse(
            LocalDate monthStart,
            String plan,
            BigDecimal spentUsd,
            BigDecimal capUsd,
            BigDecimal percentUsed,
            boolean capConfigured,
            String spendBasis,
            List<Integer> alertsFired) {}
}
