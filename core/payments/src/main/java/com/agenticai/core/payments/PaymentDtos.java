package com.agenticai.core.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for {@link PaymentController}. All amounts are integer paise - never floats. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreateMandateRequest(
        @NotNull UUID agentId,
        @NotNull MandateScope scope,
        @Positive long maxPerTxnPaise,
        @Positive long maxDailyPaise,
        @Positive long maxMonthlyPaise,
        List<String> allowedVpas,
        List<String> allowedMerchantCategories,
        @NotNull MandateApprovalPolicy approvalPolicy,
        Long approvalThresholdPaise,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil
    ) {
    }

    public record MandatePreviewResponse(String canonicalJson, String canonicalHashSha256) {
    }

    public record MandateResponse(
        UUID id,
        UUID agentId,
        String grantedByUserId,
        MandateScope scope,
        long maxPerTxnPaise,
        long maxDailyPaise,
        long maxMonthlyPaise,
        List<String> allowedVpas,
        List<String> allowedMerchantCategories,
        MandateApprovalPolicy approvalPolicy,
        Long approvalThresholdPaise,
        Instant validFrom,
        Instant validUntil,
        MandateStatus status,
        String canonicalHashSha256,
        Instant createdAt,
        Instant revokedAt,
        String revokedBy,
        String revokeReason
    ) {
    }

    public record RevokeMandateRequest(String reason) {
    }

    public record MandateSpendResponse(
        UUID mandateId,
        long maxPerTxnPaise,
        long maxDailyPaise,
        long maxMonthlyPaise,
        long dailySpendPaise,
        long monthlySpendPaise
    ) {
    }

    public record TransactionResponse(
        UUID id,
        UUID mandateId,
        UUID agentId,
        String sessionId,
        PaymentTransactionType type,
        long amountPaise,
        String currency,
        String vpa,
        String description,
        String merchantCategory,
        PaymentTransactionStatus status,
        UUID approvalId,
        String shortUrl,
        String receiptUrl,
        String gatewayPaymentId,
        String failureReason,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record RevokeResponse(UUID id, MandateStatus status) {
    }

    static MandateResponse toMandateResponse(PaymentMandate m, MandateCanonicalizer canonicalizer) {
        return new MandateResponse(
            m.getId(), m.getAgentId(), m.getGrantedByUserId(), m.getScope(),
            m.getMaxPerTxnPaise(), m.getMaxDailyPaise(), m.getMaxMonthlyPaise(),
            PaymentMandateService.readList(m.getAllowedVpas()),
            PaymentMandateService.readList(m.getAllowedMerchantCategories()),
            m.getApprovalPolicy(), m.getApprovalThresholdPaise(),
            m.getValidFrom(), m.getValidUntil(), m.getStatus(),
            canonicalizer.canonicalHash(m.getCanonicalJson()),
            m.getCreatedAt(), m.getRevokedAt(), m.getRevokedBy(), m.getRevokeReason());
    }

    static TransactionResponse toTransactionResponse(PaymentTransaction t) {
        return new TransactionResponse(
            t.getId(), t.getMandateId(), t.getAgentId(), t.getSessionId(), t.getType(),
            t.getAmountPaise(), t.getCurrency(), t.getVpa(), t.getDescription(),
            t.getMerchantCategory(), t.getStatus(), t.getApprovalId(), t.getShortUrl(),
            t.getReceiptUrl(), t.getGatewayPaymentId(), t.getFailureReason(),
            t.getExpiresAt(), t.getPaidAt(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
