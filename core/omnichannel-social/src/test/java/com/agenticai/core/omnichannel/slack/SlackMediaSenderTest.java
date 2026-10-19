package com.agenticai.core.omnichannel.slack;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * {@link SlackMediaSender} itself has no HTTP logic - that's covered by {@link
 * SlackApiClientMediaUploadTest} - so this only pins the orchestration: credential resolution
 * happens once via {@link SlackCredentialService#resolveActive()} (the same path {@link
 * SlackChannelAdapter} uses for text sends), and the 3 {@link SlackApiClient} calls run in the
 * documented order with the upload-step's return values (upload_url, file_id) actually threaded
 * into the next call rather than, say, silently dropped or re-derived.
 */
class SlackMediaSenderTest {

    private static final SlackCredentialService.DecryptedCredential CREDENTIAL =
            new SlackCredentialService.DecryptedCredential(
                    "T1", "U1", "xoxb-token", "signing-secret");

    private SlackCredentialService credentialService;
    private SlackApiClient slackApiClient;
    private SlackMediaSender sender;

    private void setUp() {
        credentialService = mock(SlackCredentialService.class);
        slackApiClient = mock(SlackApiClient.class);
        sender = new SlackMediaSender(credentialService, slackApiClient);
    }

    @Test
    void
            sendResolvesCredentialsThenRunsAllThreeUploadStepsInOrderWithTheReturnedUploadUrlAndFileId()
                    throws Exception {
        setUp();
        when(credentialService.resolveActive()).thenReturn(CREDENTIAL);
        when(slackApiClient.getUploadUrl("xoxb-token", "photo.png", 3))
                .thenReturn(
                        new SlackApiClient.UploadUrlInfo(
                                "https://files.slack.com/upload/v1/abc", "F999"));

        byte[] bytes = {1, 2, 3};
        sender.send("C0123", bytes, "image/png", "photo.png", "a caption");

        var inOrder = org.mockito.Mockito.inOrder(credentialService, slackApiClient);
        inOrder.verify(credentialService).resolveActive();
        inOrder.verify(slackApiClient).getUploadUrl("xoxb-token", "photo.png", 3);
        inOrder.verify(slackApiClient)
                .uploadFileBytes("https://files.slack.com/upload/v1/abc", bytes);
        inOrder.verify(slackApiClient)
                .completeUpload("xoxb-token", "F999", "photo.png", "C0123", "a caption");
        verifyNoMoreInteractions(slackApiClient);
    }

    @Test
    void sendFallsBackToAGenericFilenameWhenNoneIsGiven() throws Exception {
        setUp();
        when(credentialService.resolveActive()).thenReturn(CREDENTIAL);
        when(slackApiClient.getUploadUrl(eq("xoxb-token"), eq("attachment"), eq(1)))
                .thenReturn(
                        new SlackApiClient.UploadUrlInfo(
                                "https://files.slack.com/upload/v1/xyz", "F1"));

        sender.send("C0123", new byte[] {9}, "application/octet-stream", null, null);

        verify(slackApiClient).completeUpload("xoxb-token", "F1", "attachment", "C0123", null);
    }

    @Test
    void sendPropagatesAnUnconfiguredSlackAsAnExceptionWithoutCallingTheApiClient() {
        setUp();
        when(credentialService.resolveActive()).thenThrow(new SlackNotConfiguredException());

        assertThrows(
                SlackNotConfiguredException.class,
                () -> sender.send("C0123", new byte[] {1}, "image/png", "x.png", null));
        verifyNoMoreInteractions(slackApiClient);
    }
}
