package com.agenticai.core.omnichannel.teams;

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
 * The approval-gate executor for {@code channel.teams.send}: the payload carries the two Bot
 * Framework coordinates (serviceUrl + conversationId) plus an optional replyToId; sends go out
 * with the tenant's decrypted app credentials.
 */
class TeamsChannelAdapterTest {

    private TeamsCredentialService credentialService;
    private TeamsApiClient apiClient;
    private ChannelOutboundLogger outboundLogger;
    private TeamsChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        credentialService = mock(TeamsCredentialService.class);
        apiClient = mock(TeamsApiClient.class);
        outboundLogger = mock(ChannelOutboundLogger.class);
        adapter = new TeamsChannelAdapter(credentialService, apiClient, outboundLogger);
    }

    private static ApprovalRequest request(String payloadJson) {
        return new ApprovalRequest(
                TeamsChannelAdapter.ACTION_TYPE, "Send Teams message", payloadJson, "user-1");
    }

    @Test
    void actionTypeIsTheRegisteredApprovalActionType() {
        assertThat(adapter.actionType()).isEqualTo("channel.teams.send");
    }

    @Test
    void executeSendsTheActivityAndRecordsTheOutboundMessage() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new TeamsCredentialService.DecryptedCredential(
                                "app-id", "app-secret", "aad-tenant"));
        when(apiClient.sendActivity(
                        "app-id",
                        "app-secret",
                        "aad-tenant",
                        "https://smba.trafficmanager.net/amer/",
                        "conv-1",
                        "hello",
                        "act-9"))
                .thenReturn("Sent Teams activity.");

        adapter.execute(
                request(
                        "{\"serviceUrl\":\"https://smba.trafficmanager.net/amer/\","
                                + "\"conversationId\":\"conv-1\",\"body\":\"hello\","
                                + "\"replyToId\":\"act-9\"}"));

        verify(outboundLogger).recordSent(ChannelType.TEAMS, "conv-1", "hello", "user-1");
    }

    @Test
    void aMissingOrUnparseablePayloadIsANonRetryableFailure() {
        assertThatThrownBy(() -> adapter.execute(request(null)))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(request("{oops")))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("Could not parse Teams send payload");
        verifyNoInteractions(apiClient);
    }

    @Test
    void aSendFailureWrapsAsRetryable() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new TeamsCredentialService.DecryptedCredential(
                                "app-id", "app-secret", "aad-tenant"));
        when(apiClient.sendActivity(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("connector 5xx"));

        assertThatThrownBy(
                        () ->
                                adapter.execute(
                                        request(
                                                "{\"serviceUrl\":\"https://s.example/\","
                                                        + "\"conversationId\":\"c\",\"body\":\"b\","
                                                        + "\"replyToId\":null}")))
                .isInstanceOf(RetryableChannelDeliveryException.class)
                .hasMessageContaining("Teams send failed");
    }
}
