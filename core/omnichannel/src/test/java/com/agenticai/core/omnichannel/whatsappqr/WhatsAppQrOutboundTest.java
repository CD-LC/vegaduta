package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** The approval-gated outbound path: payload parsing, chunking, send and failure mapping. */
class WhatsAppQrOutboundTest {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String KEY = "3f6f1b0c-7d4a-4b2c-9e8f-1a2b3c4d5e6f";

    private final WhatsAppQrService service = mock(WhatsAppQrService.class);
    private final WhatsAppQrClient client = mock(WhatsAppQrClient.class);
    private final ChannelOutboundLogger outboundLogger = mock(ChannelOutboundLogger.class);
    private final WhatsAppQrChannelAdapter adapter =
            new WhatsAppQrChannelAdapter(service, client, outboundLogger, 4000);

    private ApprovalRequest request(String payloadJson) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getPayloadJson()).thenReturn(payloadJson);
        when(request.getRequestedBy()).thenReturn("admin");
        return request;
    }

    private void connected() {
        when(service.resolveActive()).thenReturn(new WhatsAppQrHandle(KEY, "4915112345678"));
    }

    @Test
    void sendsTheApprovedTextThroughTheBridge() throws Exception {
        connected();
        String payload =
                MAPPER.writeValueAsString(
                        new WhatsAppQrChannelAdapter.SendPayload("4915100000000", "hello there"));

        adapter.execute(request(payload));

        verify(client).send(KEY, "4915100000000", "hello there");
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(outboundLogger)
                .recordSent(
                        eq(ChannelType.WHATSAPP_QR), to.capture(), eq("hello there"), eq("admin"));
        assertThat(to.getValue()).isEqualTo("4915100000000");
    }

    @Test
    void aLidRecipientGoesThroughUnchanged() throws Exception {
        connected();
        String payload =
                MAPPER.writeValueAsString(
                        new WhatsAppQrChannelAdapter.SendPayload("9988776655@lid", "hi"));

        adapter.execute(request(payload));

        verify(client).send(KEY, "9988776655@lid", "hi");
    }

    @Test
    void missingConnectionIsNotRetryable() {
        when(service.resolveActive()).thenThrow(new WhatsAppQrNotConfiguredException());

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"1\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
    }

    @Test
    void aServerErrorIsRetryableButAClientErrorIsNot() throws Exception {
        connected();
        org.mockito.Mockito.doThrow(new WhatsAppQrApiException(503, "down"))
                .when(client)
                .send(any(), any(), any());

        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"1\",\"body\":\"x\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class);

        org.mockito.Mockito.doThrow(new WhatsAppQrApiException(400, "bad recipient"))
                .when(client)
                .send(any(), any(), any());
        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"1\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
    }

    @Test
    void aBlankOrMissingPayloadNeverReachesTheBridge() {
        assertThatThrownBy(() -> adapter.execute(request(null)))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(request("{\"to\":\"1\"}")))
                .isInstanceOf(ChannelDeliveryException.class);
        verify(client, never()).send(any(), any(), any());
    }

    @Test
    void longRepliesAreChunkedAtBoundaries() {
        connected();
        String longBody = "word ".repeat(2000).trim(); // ~10k chars, spaces between words
        try {
            String payload =
                    MAPPER.writeValueAsString(
                            new WhatsAppQrChannelAdapter.SendPayload("4915", longBody));
            adapter.execute(request(payload));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ArgumentCaptor<String> parts = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.atLeast(2)).send(eq(KEY), eq("4915"), parts.capture());
        assertThat(parts.getAllValues()).allMatch(p -> p.length() <= 4000);
    }
}
