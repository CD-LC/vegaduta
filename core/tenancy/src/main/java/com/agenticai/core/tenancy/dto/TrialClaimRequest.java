package com.agenticai.core.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * {@code POST /api/tenant/trial/claim}'s body (event-triggered 7-day Premium
 * trial, PRICING-MONETIZATION-AUDIT-2026-07-31.md §5.4/B6). {@code trigger}
 * names the product moment that offered the trial - e.g. {@code "whatsapp-gate"}
 * (first WhatsApp-connect attempt on FREE) or {@code "edit-lock"} (first
 * edit-lock collision) - recorded verbatim for conversion analytics
 * ({@code tenant.event_trial_trigger}) and into the comp reason as
 * {@code "event-trial:<trigger>"}. Free-form within a tight charset/length on
 * purpose: the web app can introduce a new trigger moment without a backend
 * change, but the value lands in a VARCHAR(64) column and a 255-char reason
 * field, so it is slug-shaped, not arbitrary text.
 */
public record TrialClaimRequest(
    @NotBlank(message = "trigger is required")
    @Size(max = 64, message = "trigger is too long")
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*", message = "trigger must be a slug (letters, digits, '.', '_', '-')")
    String trigger
) {
    /** Trimmed + lowercased - one canonical spelling per trigger moment in analytics. */
    public String normalizedTrigger() {
        return trigger.trim().toLowerCase(Locale.ROOT);
    }
}
