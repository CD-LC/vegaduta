package com.agenticai.core.payments.agenttools;

import com.agenticai.core.payments.PaymentToolService.PaymentOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for the mandate-aware payment tool callbacks - the same conventions {@code
 * CommerceToolSupport} established (error text through Jackson, never concatenated;
 * INR-with-at-most-2-decimals -&gt; paise, never floats on money). Duplicated rather than shared
 * across packages because {@code CommerceToolSupport} is package-private to the v0 commerce tools
 * by its own design.
 */
final class PaymentToolSupport {

    static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private PaymentToolSupport() {}

    /**
     * INR major amount (number or string like {@code 499.50}) to paise; rejects &gt;2dp rather than
     * rounding.
     */
    static long inrToPaise(Object amountInr) {
        if (amountInr == null) {
            throw new IllegalArgumentException("amount_inr is required");
        }
        String text =
                amountInr
                        .toString()
                        .trim()
                        .replace(",", "")
                        .replace("₹", "")
                        .replace("Rs.", "")
                        .replace("Rs", "")
                        .trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("amount_inr is required");
        }
        BigDecimal major;
        try {
            major = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "amount_inr must be a number in rupees, e.g. 499 or 499.50 - got '"
                            + amountInr
                            + "'");
        }
        if (major.signum() <= 0) {
            throw new IllegalArgumentException("amount_inr must be greater than zero");
        }
        if (major.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "amount_inr may have at most two decimal places (paise) - got "
                            + major.toPlainString());
        }
        return major.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return errorJson("Could not serialise the tool result: " + e.getMessage());
        }
    }

    static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    static Map<String, Object> outcomeJson(PaymentOutcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", outcome.transactionId().toString());
        out.put("status", outcome.status().name());
        out.put(
                "paid",
                outcome.status() == com.agenticai.core.payments.PaymentTransactionStatus.CAPTURED);
        if (outcome.shortUrl() != null) {
            out.put("shortUrl", outcome.shortUrl());
        }
        if (outcome.approvalId() != null) {
            out.put("approvalId", outcome.approvalId().toString());
        }
        if (outcome.replayed()) {
            out.put("replayed", true);
        }
        out.put("message", outcome.message());
        return out;
    }
}
