package com.agenticai.core.agents.tools.commerce;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionStatus;
import com.agenticai.core.json.JsonMappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared helpers for the two commerce tool callbacks - the same "route error text through Jackson,
 * never string-concat it" JSON conventions {@code RequestHumanToolCallback}/{@code
 * RememberFactToolCallback} follow, plus the one money conversion both tools need done identically.
 */
final class CommerceToolSupport {

    static final ObjectMapper MAPPER = JsonMappers.plain();

    private CommerceToolSupport() {}

    /**
     * INR major amount (as the model supplies it: a number or numeric string like {@code 499},
     * {@code 499.50}, {@code "1,250"}) to paise. Rejects more than two decimal places rather than
     * rounding: silently turning {@code 10.005} into either 1000 or 1001 paise is exactly the class
     * of "fabricated number" money handling this codebase refuses elsewhere.
     *
     * @throws IllegalArgumentException with a model-readable reason
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

    /**
     * Lenient phone normalisation for a pre-fill field: keeps digits and a leading {@code +}; a
     * bare 10-digit Indian mobile number gets {@code +91}. Anything that doesn't look like a phone
     * number at all is rejected rather than sent to the gateway as garbage. Null/blank passes
     * through as null (the field is optional).
     */
    static String normalisePhone(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        boolean plus = text.startsWith("+");
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() == 10 && !plus) {
            return "+91" + digits;
        }
        if (digits.length() >= 10 && digits.length() <= 15) {
            return "+" + digits;
        }
        throw new IllegalArgumentException(
                "customer_phone does not look like a phone number: '" + text + "'");
    }

    /**
     * The tool-facing view of a session - ACP-style lowercase status, amounts in both units, and a
     * plain {@code paid} flag the model can branch on.
     */
    static Map<String, Object> sessionJson(CheckoutSession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkoutSessionId", s.id().toString());
        out.put("status", s.status().wireValue());
        out.put("paid", s.status() == CheckoutSessionStatus.COMPLETED);
        out.put("currency", s.currency());
        out.put("amountInr", s.amountMajor().toPlainString());
        out.put("amountPaise", s.amountPaise());
        out.put("amount", "₹" + s.amountMajor().toPlainString());
        out.put("description", s.description());
        if (s.customerName() != null) {
            out.put("customerName", s.customerName());
        }
        if (s.customerPhone() != null) {
            out.put("customerPhone", s.customerPhone());
        }
        if (s.shortUrl() != null) {
            out.put("shortUrl", s.shortUrl());
        }
        out.put("expiresAt", iso(s.expiresAt()));
        out.put("paidAt", iso(s.paidAt()));
        if (s.paymentId() != null) {
            out.put("paymentId", s.paymentId());
        }
        if (s.reviewNote() != null) {
            out.put("reviewNote", s.reviewNote());
        }
        return out;
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

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
