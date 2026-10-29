package com.agenticai.core.omnichannel.x;

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
 * The approval-gate executor for {@code channel.x.send}: {@link XOAuthService#currentAccessToken}
 * mints a fresh token per send (X rotates refresh tokens on every use, so nothing may be cached),
 * the DM goes out as a {@code dm_conversation_id} send, failures are retryable.
 */
class XChannelAdapterTest {

    private XOAuthService oauthService;
    private XApiClient apiClient;
    private ChannelOutboundLogger outboundLogger;
    private XChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        oauthService = mock(XOAuthService.class);
        apiClient = mock(XApiClient.class);
        outboundLogger = mock(ChannelOutboundLogger.class);
        adapter = new XChannelAdapter(oauthService, apiClient, outboundLogger);
    }

    private static ApprovalRequest request(String payloadJson) {
        return new ApprovalRequest(XChannelAdapter.ACTION_TYPE, "Send X DM", payloadJson, "user-1");
    }

    @Test
    void actionTypeIsTheRegisteredApprovalActionType() {
        assertThat(adapter.actionType()).isEqualTo("channel.x.send");
    }

    @Test
    void executeMintsAFreshTokenSendsTheDmAndRecordsIt() {
        when(oauthService.currentAccessToken()).thenReturn("access-token-1");
        when(apiClient.sendDm("access-token-1", "dm-conv-1", "hello")).thenReturn("Sent X DM.");

        adapter.execute(request("{\"to\":\"dm-conv-1\",\"body\":\"hello\"}"));

        verify(apiClient).sendDm("access-token-1", "dm-conv-1", "hello");
        verify(outboundLogger).recordSent(ChannelType.X, "dm-conv-1", "hello", "user-1");
    }

    @Test
    void aMissingOrUnparseablePayloadIsANonRetryableFailure() {
        assertThatThrownBy(() -> adapter.execute(request(null)))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("no payload");
        assertThatThrownBy(() -> adapter.execute(request("{bad")))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("Could not parse X send payload");
        verifyNoInteractions(apiClient);
    }

    @Test
    void aSendFailureWrapsAsRetryable() {
        when(oauthService.currentAccessToken()).thenReturn("access-token-1");
        when(apiClient.sendDm("access-token-1", "dm-conv-1", "hi"))
                .thenThrow(new RuntimeException("HTTP 429"));

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"dm-conv-1\",\"body\":\"hi\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("X DM send failed");
    }

    @Test
    void anOAuthFailureAlsoWrapsAsRetryable() {
        when(oauthService.currentAccessToken()).thenThrow(new XNotConnectedException());

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"dm-conv-1\",\"body\":\"hi\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class);
        verifyNoInteractions(apiClient);
    }
}
