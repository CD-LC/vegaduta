package com.agenticai.core.omnichannel.discord.dto;

/**
 * Body the {@code channels/discord} Node Gateway service posts to {@code DiscordWebhookController}
 * for every inbound {@code MESSAGE_CREATE} event it receives. {@code applicationId} is the bot's
 * own application ID (used to resolve which tenant this message belongs to - see {@code
 * ExternalAccountProvider#DISCORD}); {@code channelId} is where the reply must be sent (a DM
 * channel or guild text channel, both look the same to the REST API); {@code authorId} identifies
 * the human who sent it.
 *
 * <p>{@code messageId} is the Discord message's own snowflake id ({@code message.id} on the {@code
 * MESSAGE_CREATE} event) - globally unique, the Discord equivalent of Meta's wamid / Twilio's
 * MessageSid - used by {@code DiscordWebhookController} for inbound dedup. Nullable, stated
 * honestly: as of this field being added, {@code channels/discord}'s {@code server.js} does not
 * forward it yet (that service is a separate Node codebase outside this change's scope), so today's
 * payloads carry null and dedup is a documented no-op (see {@link
 * com.agenticai.core.omnichannel.session.InboundMessageDedupService#tryClaim}'s null contract)
 * until the Node side sends {@code message.id} along.
 */
public record DiscordInboundMessage(
        String applicationId,
        String channelId,
        String authorId,
        String content,
        String messageId) {}
