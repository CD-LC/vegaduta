package com.agenticai.core.omnichannel.discord;

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
 * The approval-gate executor for {@code channel.discord.send}: a plain REST send through {@link
 * DiscordApiClient} (outbound needs no Gateway connection), fail-fast payload parsing, retryable
 * send failures, and the outbound log on success.
 */
class DiscordChannelAdapterTest {

    private DiscordCredentialService credentialService;
    private DiscordApiClient discordApiClient;
    private ChannelOutboundLogger outboundLogger;
    private DiscordChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        credentialService = mock(DiscordCredentialService.class);
        discordApiClient = mock(DiscordApiClient.class);
        outboundLogger = mock(ChannelOutboundLogger.class);
        adapter = new DiscordChannelAdapter(credentialService, discordApiClient, outboundLogger);
    }

    private static ApprovalRequest request(String payloadJson) {
        return new ApprovalRequest(
                DiscordChannelAdapter.ACTION_TYPE, "Send Discord message", payloadJson, "user-1");
    }

    @Test
    void actionTypeIsTheRegisteredApprovalActionType() {
        assertThat(adapter.actionType()).isEqualTo("channel.discord.send");
    }

    @Test
    void executeSendsThroughTheApiClientAndRecordsTheOutboundMessage() {
        when(credentialService.resolveActive())
                .thenReturn(new DiscordCredentialService.DecryptedCredential("app-1", "bot-token"));
        when(discordApiClient.sendMessage("bot-token", "chan-1", "hi there"))
                .thenReturn("Sent Discord message.");

        adapter.execute(request("{\"to\":\"chan-1\",\"body\":\"hi there\"}"));

        verify(discordApiClient).sendMessage("bot-token", "chan-1", "hi there");
        verify(outboundLogger).recordSent(ChannelType.DISCORD, "chan-1", "hi there", "user-1");
    }

    @Test
    void aMissingOrUnparseablePayloadIsANonRetryableFailure() {
        assertThatThrownBy(() -> adapter.execute(request("")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("no payload");
        assertThatThrownBy(() -> adapter.execute(request("[1,2]")))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("Could not parse Discord send payload");
        verifyNoInteractions(discordApiClient);
    }

    @Test
    void aSendFailureWrapsAsRetryable() {
        when(credentialService.resolveActive())
                .thenReturn(new DiscordCredentialService.DecryptedCredential("app-1", "bot-token"));
        when(discordApiClient.sendMessage("bot-token", "chan-1", "hi"))
                .thenThrow(new RuntimeException("429"));

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"chan-1\",\"body\":\"hi\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("Discord send failed");
    }
}
