package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for Slack - drives the current (2024+) 3-step upload flow ({@link
 * SlackApiClient#getUploadUrl}, {@link SlackApiClient#uploadFileBytes}, {@link
 * SlackApiClient#completeUpload}), the same "prove the channel is configured, then call the API"
 * shape as {@link SlackChannelAdapter}'s text-send path. Credential resolution follows that exact
 * path too - {@link SlackCredentialService#resolveActive()} - because, like {@code
 * ApprovalActionExecutor.execute()}, a media forward has no live inbound webhook request to
 * piggyback a token off of; it must resolve the tenant's own active Bot Token from storage.
 */
@Component
public class SlackMediaSender implements ChannelMediaSender {

    private final SlackCredentialService credentialService;
    private final SlackApiClient slackApiClient;

    public SlackMediaSender(
            SlackCredentialService credentialService, SlackApiClient slackApiClient) {
        this.credentialService = credentialService;
        this.slackApiClient = slackApiClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.SLACK;
    }

    /**
     * {@code to} is a Slack channel ID (e.g. {@code C0123...}) or DM channel ID (e.g. {@code
     * D0123...}) - same as {@link SlackChannelAdapter.SendPayload#to()}.
     */
    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption)
            throws Exception {
        SlackCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        String resolvedFilename =
                (filename == null || filename.isBlank()) ? "attachment" : filename;

        SlackApiClient.UploadUrlInfo uploadInfo =
                slackApiClient.getUploadUrl(credential.botToken(), resolvedFilename, bytes.length);
        slackApiClient.uploadFileBytes(uploadInfo.uploadUrl(), bytes);
        slackApiClient.completeUpload(
                credential.botToken(), uploadInfo.fileId(), resolvedFilename, to, caption);
    }
}
