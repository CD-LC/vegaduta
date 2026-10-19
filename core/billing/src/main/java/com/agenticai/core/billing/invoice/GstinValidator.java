package com.agenticai.core.billing.invoice;

import java.util.regex.Pattern;

/**
 * GSTIN (Goods and Services Tax Identification Number) format + checksum
 * validation - PRICING-MONETIZATION-AUDIT-2026-07-31 §10 item D6 (GSTIN
 * capture on the tenant billing profile).
 *
 * <p>A GSTIN is 15 characters: 2-digit state code, 10-character PAN
 * (5 letters, 4 digits, 1 letter), 1 entity code ({@code 1-9A-Z}), the
 * literal {@code Z}, and a mod-36 checksum character. Validation here is
 * the structural regex plus the actual checksum computation (Luhn mod 36
 * variant: alternate weights 1/2 over the first 14 characters, sum of
 * quotient+remainder of each product by 36, check char = {@code (36 -
 * total % 36) % 36}) - verified against the canonical published sample
 * {@code 27AAPFU0939F1ZV}.
 *
 * <p>Pure static utility, no Spring wiring - used by the billing-profile
 * endpoint and directly unit-testable.
 */
public final class GstinValidator {

    /** State code (01-38 range not hard-enforced; digits required) + PAN + entity + Z + checksum. */
    private static final Pattern STRUCTURE =
        Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private GstinValidator() {
    }

    /**
     * True only for a structurally valid GSTIN whose checksum character is
     * correct. Null/blank is NOT valid here - callers treat blank as "no
     * GSTIN" before ever calling this.
     */
    public static boolean isValid(String gstin) {
        if (gstin == null) {
            return false;
        }
        String candidate = gstin.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STRUCTURE.matcher(candidate).matches()) {
            return false;
        }
        return candidate.charAt(14) == checksumChar(candidate.substring(0, 14));
    }

    private static char checksumChar(String first14) {
        int total = 0;
        for (int i = 0; i < first14.length(); i++) {
            int value = ALPHABET.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            total += product / 36 + product % 36;
        }
        return ALPHABET.charAt((36 - total % 36) % 36);
    }
}
