package com.agenticai.core.omnichannel.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The approval-gate executor for {@code channel.slack.send}: the payload JSON is parsed fail-fast
 * (a malformed payload is NOT retryable), the send goes through {@link SlackApiClient} with the
 * tenant's decrypted bot token, a send failure IS retryable, and a success is recorded in the
 * outbound log.
 */
class SlackChannelAdapterTest {

    private SlackCredentialService credentialService;
    private SlackApiClient slackApiClient;
    private ChannelOutboundLogger outboundLogger;
    private SlackChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        credentialService = mock(SlackCredentialService.class);
        slackApiClient = mock(SlackApiClient.class);
        outboundLogger = mock(ChannelOutboundLogger.class);
        adapter = new SlackChannelAdapter(credentialService, slackApiClient, outboundLogger);
    }

    private static ApprovalRequest request(String payloadJson) {
        return new ApprovalRequest(
                SlackChannelAdapter.ACTION_TYPE, "Send Slack message", payloadJson, "user-1");
    }

    @Test
    void actionTypeIsTheRegisteredApprovalActionType() {
        assertThat(adapter.actionType()).isEqualTo("channel.slack.send");
    }

    @Test
    void executeSendsThroughTheApiClientAndRecordsTheOutboundMessage() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                "T123", "UBOT", "xoxb-token", "signing-secret"));
        when(slackApiClient.sendMessage("xoxb-token", "C0123", "hello"))
                .thenReturn("Sent Slack message to C0123.");

        adapter.execute(request("{\"to\":\"C0123\",\"body\":\"hello\"}"));

        verify(slackApiClient).sendMessage("xoxb-token", "C0123", "hello");
        verify(outboundLogger).recordSent(ChannelType.SLACK, "C0123", "hello", "user-1");
    }

    @Test
    void aMissingPayloadIsANonRetryableDeliveryFailure() {
        assertThatThrownBy(() -> adapter.execute(request(null)))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("no payload");
        assertThatThrownBy(() -> adapter.execute(request("   ")))
                .isInstanceOf(ChannelDeliveryException.class);
        verifyNoInteractions(credentialService, slackApiClient);
    }

    @Test
    void anUnparseablePayloadIsANonRetryableDeliveryFailure() {
        assertThatThrownBy(() -> adapter.execute(request("{not json")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("Could not parse");
        verifyNoInteractions(slackApiClient);
    }

    @Test
    void aSendFailureWrapsAsRetryable() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                "T123", "UBOT", "xoxb-token", "signing-secret"));
        when(slackApiClient.sendMessage("xoxb-token", "C0123", "hi"))
                .thenThrow(new RuntimeException("rate limited"));

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"C0123\",\"body\":\"hi\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("Slack send failed");
    }
}
