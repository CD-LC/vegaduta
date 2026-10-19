package com.agenticai.core.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * E.164-ish validation ("+" then 8-15 digits) - loose on purpose. Twilio
 * itself is the real validator (a malformed number fails the send with a
 * clear ChannelDeliveryException); this just catches the obvious "forgot
 * the country code" mistake before it's saved.
 */
public record UpdatePhoneNumberRequest(
    @NotBlank
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Use international format, e.g. +14155552671")
    String phoneNumber
) {
}
