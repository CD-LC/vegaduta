package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The outbound path end to end against a fake BlueBubbles server: approval payload in, REST call
 * out.
 */
class ImessageChannelAdapterTest {

    private static ApprovalRequest request(String payload) {
        ApprovalRequest r = mock(ApprovalRequest.class);
        when(r.getPayloadJson()).thenReturn(payload);
        when(r.getRequestedBy()).thenReturn("system:imessage-inbound:***0100");
        return r;
    }

    @Test
    void anApprovedSendReachesTheServerAndIsLoggedToTheTranscript() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on("POST /api/v1/message/text", 200, "{\"status\":200,\"data\":{}}")) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            when(credentials.resolveActive())
                    .thenReturn(
                            new ImessageCredentialService.DecryptedCredential(
                                    bb.url(), FakeBlueBubbles.PASSWORD));
            ChannelOutboundLogger logger = mock(ChannelOutboundLogger.class);
            ImessageChannelAdapter adapter =
                    new ImessageChannelAdapter(
                            credentials,
                            bb.client(),
                            logger,
                            new ImessageRateLimiter(20, 30, System::currentTimeMillis));

            adapter.execute(
                    request(
                            JsonMappers.plain()
                                    .writeValueAsString(
                                            new ImessageChannelAdapter.SendPayload(
                                                    "iMessage;-;+15550100100", "hello back"))));

            JsonNode body = JsonMappers.plain().readTree(bb.requests.get(0).bodyText());
            assertThat(body.path("chatGuid").asString()).isEqualTo("iMessage;-;+15550100100");
            assertThat(body.path("message").asString()).isEqualTo("hello back");
            verify(logger)
                    .recordSent(
                            ChannelType.IMESSAGE,
                            "iMessage;-;+15550100100",
                            "hello back",
                            "system:imessage-inbound:***0100");
        }
    }

    @Test
    void aServerFailureIsRetryableAndNothingIsLoggedAsSent() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles()) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            when(credentials.resolveActive())
                    .thenReturn(
                            new ImessageCredentialService.DecryptedCredential(
                                    bb.url(), FakeBlueBubbles.PASSWORD));
            ChannelOutboundLogger logger = mock(ChannelOutboundLogger.class);
            ImessageChannelAdapter adapter =
                    new ImessageChannelAdapter(
                            credentials,
                            bb.client(),
                            logger,
                            new ImessageRateLimiter(20, 30, System::currentTimeMillis));

            assertThatThrownBy(
                            () ->
                                    adapter.execute(
                                            request(
                                                    "{\"to\":\"iMessage;+;chat1\",\"body\":\"x\"}")))
                    .isInstanceOf(RetryableChannelDeliveryException.class);
            verify(logger, never())
                    .recordSent(
                            ChannelType.IMESSAGE,
                            "iMessage;+;chat1",
                            "x",
                            "system:imessage-inbound:***0100");
        }
    }

    @Test
    void theOutboundRateLimitFailsRetryablyWithoutCallingTheServer() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on("POST /api/v1/message/text", 200, "{\"status\":200,\"data\":{}}")) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            when(credentials.resolveActive())
                    .thenReturn(
                            new ImessageCredentialService.DecryptedCredential(
                                    bb.url(), FakeBlueBubbles.PASSWORD));
            ImessageChannelAdapter adapter =
                    new ImessageChannelAdapter(
                            credentials,
                            bb.client(),
                            mock(ChannelOutboundLogger.class),
                            new ImessageRateLimiter(20, 1, System::currentTimeMillis));
            ApprovalRequest r = request("{\"to\":\"+15550100100\",\"body\":\"x\"}");

            adapter.execute(r);
            assertThatThrownBy(() -> adapter.execute(r))
                    .isInstanceOf(RetryableChannelDeliveryException.class);
            assertThat(bb.requests).hasSize(1);
        }
    }

    @Test
    void aDisconnectedChannelIsAPermanentFailureNotARetry() {
        ImessageCredentialService credentials = mock(ImessageCredentialService.class);
        when(credentials.resolveActive()).thenThrow(new ImessageNotConfiguredException());
        ImessageChannelAdapter adapter =
                new ImessageChannelAdapter(
                        credentials,
                        mock(ImessageApiClient.class),
                        mock(ChannelOutboundLogger.class),
                        new ImessageRateLimiter(20, 30, System::currentTimeMillis));

        assertThatThrownBy(
                        () -> adapter.execute(request("{\"to\":\"+15550100100\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
    }

    @Test
    void aBadPayloadIsAPermanentFailure() {
        ImessageChannelAdapter adapter =
                new ImessageChannelAdapter(
                        mock(ImessageCredentialService.class),
                        mock(ImessageApiClient.class),
                        mock(ChannelOutboundLogger.class),
                        new ImessageRateLimiter(20, 30, System::currentTimeMillis));

        assertThatThrownBy(() -> adapter.execute(request("not json")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(request(" ")))
                .isInstanceOf(ChannelDeliveryException.class);
    }

    @Test
    void theMediaSenderUploadsThroughTheSameServer() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on(
                                "POST /api/v1/message/attachment",
                                200,
                                "{\"status\":200,\"data\":{}}")) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            when(credentials.resolveActive())
                    .thenReturn(
                            new ImessageCredentialService.DecryptedCredential(
                                    bb.url(), FakeBlueBubbles.PASSWORD));
            ImessageMediaSender sender =
                    new ImessageMediaSender(
                            credentials,
                            bb.client(),
                            new ImessageRateLimiter(20, 30, System::currentTimeMillis));

            sender.send(
                    "iMessage;-;+15550100100", new byte[] {1, 2, 3}, "image/jpeg", "p.jpg", null);

            assertThat(sender.channel()).isEqualTo(ChannelType.IMESSAGE);
            assertThat(bb.requests).hasSize(1);
            assertThat(bb.requests.get(0).path()).isEqualTo("/api/v1/message/attachment");
        }
    }

    @Test
    void theTextSendDescribesTheSameActionType() {
        ImessageTextSend send = new ImessageTextSend();
        assertThat(send.actionType()).isEqualTo(ImessageChannelAdapter.ACTION_TYPE);
        assertThat(send.channel()).isEqualTo(ChannelType.IMESSAGE);
        assertThat(send.payload("+15550100100", null, "hi"))
                .isEqualTo(new ImessageChannelAdapter.SendPayload("+15550100100", "hi"));
    }
}
