package com.agenticai.core.omnichannel.telegram.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * Just the Bot Token, obtained from @BotFather - bot id/username come back
 * from Telegram's own {@code getMe} during activation, the same
 * "prove it before persisting" pattern every other channel credential here
 * uses (see {@code TelegramCredentialService#activate}).
 */
public record TelegramActivationRequest(@NotBlank @Size(max = 4096) String botToken) {
}
