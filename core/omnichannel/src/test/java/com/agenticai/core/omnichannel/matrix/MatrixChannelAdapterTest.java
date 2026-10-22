package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatrixChannelAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeHomeserver server;
    private MatrixCredentialService credentials;
    private ChannelOutboundLogger logger;
    private MatrixChannelAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeHomeserver();
        credentials = mock(MatrixCredentialService.class);
        logger = mock(ChannelOutboundLogger.class);
        when(credentials.resolveActive())
                .thenReturn(
                        new MatrixCredentialService.ActiveConnection(
                                server.endpoint("tok"),
                                "@bot:hs",
                                null,
                                new MatrixSenderPolicy(
                                        "@bot:hs",
                                        MatrixSenderMode.PAIRING,
                                        MatrixJoinPolicy.OFF,
                                        "")));
        adapter = new MatrixChannelAdapter(credentials, server.client(), logger);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static ApprovalRequest request(UUID id, String payload) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(id);
        when(request.getPayloadJson()).thenReturn(payload);
        when(request.getRequestedBy()).thenReturn("system:matrix-inbound:!r:hs");
        return request;
    }

    @Test
    void sendsTheApprovedMessageWithAnIdempotentTransactionIdAndLogsTheTranscript()
            throws Exception {
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{\"event_id\":\"$ok\"}");
        UUID id = UUID.randomUUID();

        adapter.execute(
                request(
                        id,
                        "{\"to\":\"!r:hs\",\"body\":\"hello\",\"replyToEventId\":\"$q\",\"threadRootEventId\":null}"));

        FakeHomeserver.Request sent = server.requests.get(0);
        assertThat(sent.rawPath()).endsWith("/send/m.room.message/vd-" + id);
        JsonNode body = MAPPER.readTree(sent.body());
        assertThat(body.path("body").asText()).isEqualTo("hello");
        assertThat(body.path("m.relates_to").path("m.in_reply_to").path("event_id").asText())
                .isEqualTo("$q");
        verify(logger).recordSent(eq(ChannelType.MATRIX), eq("!r:hs"), eq("hello"), any());
    }

    @Test
    void aPayloadWithoutExtraFieldsStillWorks() {
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{\"event_id\":\"$ok\"}");

        adapter.execute(request(UUID.randomUUID(), "{\"to\":\"!r:hs\",\"body\":\"plain\"}"));

        assertThat(server.requests).hasSize(1);
    }

    @Test
    void aForbiddenSendIsNotRetriedButAServerErrorIs() {
        server.on(
                "PUT /_matrix/client/v3/rooms/!no:hs",
                403,
                "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"You are not in this room\"}");
        server.on("PUT /_matrix/client/v3/rooms/!down:hs", 502, "{}");

        assertThatThrownBy(
                        () ->
                                adapter.execute(
                                        request(
                                                UUID.randomUUID(),
                                                "{\"to\":\"!no:hs\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
        assertThatThrownBy(
                        () ->
                                adapter.execute(
                                        request(
                                                UUID.randomUUID(),
                                                "{\"to\":\"!down:hs\",\"body\":\"x\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class);
    }

    @Test
    void anEmptyOrMalformedPayloadIsRejectedUpFront() {
        assertThatThrownBy(() -> adapter.execute(request(UUID.randomUUID(), "")))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(
                        () -> adapter.execute(request(UUID.randomUUID(), "{\"body\":\"no room\"}")))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(request(UUID.randomUUID(), "not json")))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThat(server.requests).isEmpty();
    }

    @Test
    void theTextSendProducesAPayloadThisAdapterAccepts() throws Exception {
        MatrixTextSend textSend = new MatrixTextSend();
        String json = MAPPER.writeValueAsString(textSend.payload("!r:hs", null, "hi"));
        MatrixChannelAdapter.SendPayload parsed =
                MAPPER.readValue(json, MatrixChannelAdapter.SendPayload.class);

        assertThat(parsed.to()).isEqualTo("!r:hs");
        assertThat(textSend.actionType()).isEqualTo(MatrixChannelAdapter.ACTION_TYPE);
        assertThat(textSend.channel()).isEqualTo(ChannelType.MATRIX);
    }
}
