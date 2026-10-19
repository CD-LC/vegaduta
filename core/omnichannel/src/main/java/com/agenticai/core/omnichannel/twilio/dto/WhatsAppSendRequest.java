package com.agenticai.core.omnichannel.twilio.dto;

import jakarta.validation.constraints.NotBlank;

public record WhatsAppSendRequest(@NotBlank String to, @NotBlank String message) {
}
