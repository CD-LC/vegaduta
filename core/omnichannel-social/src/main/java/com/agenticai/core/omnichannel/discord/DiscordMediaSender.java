package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for Discord - the media counterpart to {@link DiscordChannelAdapter}'s
 * text-send path, delegating the actual multipart request to {@link
 * DiscordApiClient#sendMediaMessage}. Credential resolution follows the exact same path as {@link
 * DiscordChannelAdapter} (and, per that class's javadoc, {@code ApprovalActionExecutor.execute()}
 * generally): {@link DiscordCredentialService#resolveActive()} - a media forward has no live
 * inbound Gateway event to piggyback a token off of, so it must resolve the tenant's own active Bot
 * Token from storage.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class DiscordMediaSender implements ChannelMediaSender {

    private final DiscordCredentialService credentialService;
    private final DiscordApiClient discordApiClient;

    public DiscordMediaSender(
            DiscordCredentialService credentialService, DiscordApiClient discordApiClient) {
        this.credentialService = credentialService;
        this.discordApiClient = discordApiClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.DISCORD;
    }

    /**
     * {@code to} is a Discord channel ID (including DM channel IDs) - same as {@link
     * DiscordChannelAdapter.SendPayload#to()}.
     */
    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption)
            throws Exception {
        DiscordCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        discordApiClient.sendMediaMessage(
                credential.botToken(), to, bytes, contentType, filename, caption);
    }
}
