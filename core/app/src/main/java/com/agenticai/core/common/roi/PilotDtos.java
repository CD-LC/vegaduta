package com.agenticai.core.common.roi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the pilot kit under {@code /api/tenant/roi}: the tenant-entered baseline and the
 * pilot report. Every number is either recorded by the platform, typed by the tenant (and labelled
 * so), or null. Nothing is defaulted.
 */
public final class PilotDtos {

    private PilotDtos() {}

    /** Source label printed next to every baseline number. */
    public static final String TENANT_ENTERED = "tenant-entered";

    /** The four jobs the pilot process is written for. */
    public static final List<String> JOB_TYPES =
            List.of(
                    "MISSED_CALL_RECOVERY",
                    "APPOINTMENT_REMINDERS",
                    "PAYMENT_FOLLOW_UP",
                    "LEAD_FOLLOW_UP");

    /** What the tenant typed. {@code missedRate} is a fraction (0.25 = 25%). All fields optional. */
    public record BaselineRequest(
            String jobType,
            String baselinePeriod,
            BigDecimal monthlyVolume,
            BigDecimal avgHandlingMinutes,
            BigDecimal missedRate,
            BigDecimal avgValuePerConversion,
            String valueCurrency) {}

    public record BaselineResponse(
            UUID agentId,
            String jobType,
            String baselinePeriod,
            BigDecimal monthlyVolume,
            BigDecimal avgHandlingMinutes,
            BigDecimal missedRate,
            BigDecimal avgValuePerConversion,
            String valueCurrency,
            String enteredBy,
            Instant enteredAt,
            Instant updatedAt,
            /** Always {@link #TENANT_ENTERED}: the platform never measures or fills these. */
            String source) {

        public boolean isEmpty() {
            return jobType == null
                    && baselinePeriod == null
                    && monthlyVolume == null
                    && avgHandlingMinutes == null
                    && missedRate == null
                    && avgValuePerConversion == null;
        }
    }

    /** One number the report relies on that a person typed, with where it came from. */
    public record AssumptionLine(String label, String value, String source) {}

    /** {@code GET /api/tenant/roi/demo}: whether this workspace is a seeded demo. */
    public record DemoStatus(boolean demo, String label) {}

    /**
     * The pilot report's recorded data. {@code demo} is true only in a tenant seeded by {@code
     * scripts/vegaduta seed-demo}; the renderers then print an "Example" watermark.
     */
    public record PilotReport(
            boolean demo,
            String demoLabel,
            UUID agentId,
            String agentName,
            LocalDate from,
            LocalDate to,
            Instant generatedAt,
            boolean includeAssumedResolutions,
            long conversations,
            long resolved,
            long handedToHuman,
            long humanCorrected,
            BigDecimal resolvedRate,
            BigDecimal handoverRate,
            BigDecimal humanCorrectionRate,
            BigDecimal medianResponseSeconds,
            long responseSamples,
            BigDecimal estimatedCostUsd,
            boolean costComplete,
            BigDecimal costPerResolvedUsd,
            long platformChargesMinor,
            String platformChargesCurrency,
            BigDecimal valueTotal,
            BigDecimal minutesSavedTotal,
            BigDecimal netValue,
            BigDecimal roiPercent,
            String roiNote,
            String roiBasis,
            BaselineResponse baseline,
            List<AssumptionLine> assumptions,
            /** Every model call recorded for the agent in the window, successful or not. */
            long modelCalls,
            /** Currency of the tenant's value-per-resolution assumption, null when none. */
            String valueCurrency,
            /** True when billable charges in the window span more than one currency (not summable). */
            boolean chargesMixedCurrency) {}
}
