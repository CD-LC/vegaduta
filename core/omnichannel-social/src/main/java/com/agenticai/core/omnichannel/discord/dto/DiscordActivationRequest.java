package com.agenticai.core.omnichannel.discord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Just the Bot Token - unlike Meta/Slack, there's no second secret to collect here (see {@code
 * DiscordCredential}'s javadoc). Application ID and bot username are both fetched from Discord's
 * own API during {@link com.agenticai.core.omnichannel.discord.DiscordCredentialService#activate}
 * rather than asked of the tenant, the same "prove it before persisting" pattern every other
 * channel credential here uses.
 */
public record DiscordActivationRequest(@NotBlank @Size(max = 4096) String botToken) {}
