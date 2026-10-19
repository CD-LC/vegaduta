package com.agenticai.core.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * {@code POST /api/tenant/referral/redeem}'s body (referral program,
 * PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3). Codes are generated
 * uppercase alphanumeric ({@code ReferralService}), but a friend typing one from
 * a WhatsApp message shouldn't fail on casing - accepted case-insensitively and
 * normalized before lookup. Max length matches the V159 {@code referral_code
 * VARCHAR(16)} column.
 */
public record ReferralRedeemRequest(
    @NotBlank(message = "code is required")
    @Size(max = 16, message = "code is too long")
    @Pattern(regexp = "\\s*[A-Za-z0-9]+\\s*", message = "code must be letters and digits only")
    String code
) {
    /** Trimmed + uppercased - the canonical stored form ({@code ReferralService#CODE_ALPHABET}). */
    public String normalizedCode() {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
