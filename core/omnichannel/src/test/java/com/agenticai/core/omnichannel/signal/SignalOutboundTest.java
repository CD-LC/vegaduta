package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Everything that leaves for Signal: the approved send, text-send registry entry, media, and status. */
class SignalOutboundTest {

    private static final String NUMBER = "+4915112345678";
    private static final SignalConnection CONNECTION =
            new SignalConnection("http://signal-cli:8080", NUMBER, "tok", UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);

    private SignalCredentialService credentials;
    private SignalApiClient api;
    private ChannelOutboundLogger logger;
    private SignalChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        credentials = mock(SignalCredentialService.class);
        api = mock(SignalApiClient.class);
        logger = mock(ChannelOutboundLogger.class);
        when(credentials.resolveActive()).thenReturn(CONNECTION);
        adapter = new SignalChannelAdapter(credentials, api, logger, 3500);
    }

    private static ApprovalRequest approved(String json) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getPayloadJson()).thenReturn(json);
        when(request.getRequestedBy()).thenReturn("system:signal-inbound:***0001");
        return request;
    }

    @Test
    void sendsAnApprovedReplyAndLogsIt() {
        adapter.execute(approved("{\"to\":\"+4917600000001\",\"body\":\"hello\"}"));

        verify(api).send(CONNECTION, "+4917600000001", "hello", List.of());
        verify(logger).recordSent(ChannelType.SIGNAL, "+4917600000001", "hello", "system:signal-inbound:***0001");
    }

    @Test
    void aGroupReplyGoesToTheGroupRecipientForm() {
        adapter.execute(approved("{\"to\":\"group:Z3JvdXBpZGdyb3VwaWQ=\",\"body\":\"hi all\"}"));

        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(api).send(any(), recipient.capture(), eq("hi all"), any());
        assertThat(recipient.getValue()).startsWith("group.");
    }

    @Test
    void aLongReplyIsSplitAtParagraphBoundaries() {
        String text = ("word ".repeat(300).strip()) + "\n\n" + ("more ".repeat(300).strip());

        List<String> parts = SignalChannelAdapter.chunks(text, 1600);

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(1600));
        assertThat(String.join(" ", parts).replaceAll("\\s+", " ")).isEqualTo(text.replaceAll("\\s+", " "));
        assertThat(SignalChannelAdapter.chunks("x".repeat(5000), 2000)).hasSize(3);
        assertThat(SignalChannelAdapter.chunks("short", 2000)).containsExactly("short");
    }

    @Test
    void networkAndServerFailuresAreRetriedButARejectedRecipientIsNot() {
        org.mockito.Mockito.doThrow(new SignalApiException(503, "down")).when(api).send(any(), any(), any(), any());
        assertThatThrownBy(() -> adapter.execute(approved("{\"to\":\"+4917600000001\",\"body\":\"x\"}")))
                .isInstanceOf(RetryableChannelDeliveryException.class);

        org.mockito.Mockito.doThrow(new SignalApiException(400, "Unregistered user")).when(api).send(any(), any(), any(), any());
        assertThatThrownBy(() -> adapter.execute(approved("{\"to\":\"+4917600000001\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
        verify(logger, never()).recordSent(any(), any(), any(), any());
    }

    @Test
    void anUnusablePayloadOrRecipientFailsPermanently() {
        assertThatThrownBy(() -> adapter.execute(approved(null))).isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(approved("not json"))).isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(approved("{\"to\":\"\",\"body\":\"\"}")))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> adapter.execute(approved("{\"to\":\"../x\",\"body\":\"hi\"}")))
                .isInstanceOf(ChannelDeliveryException.class);
        verify(api, never()).send(any(), any(), any(), any());
    }

    @Test
    void sendingWithNoConnectionIsAPermanentFailure() {
        when(credentials.resolveActive()).thenThrow(new SignalNotConfiguredException());

        assertThatThrownBy(() -> adapter.execute(approved("{\"to\":\"+4917600000001\",\"body\":\"x\"}")))
                .isInstanceOf(ChannelDeliveryException.class)
                .isNotInstanceOf(RetryableChannelDeliveryException.class);
    }

    @Test
    void theTextSendRegistryEntryMatchesTheAdapterAndGuardsTheRecipient() {
        SignalTextSend send = new SignalTextSend();

        assertThat(send.channel()).isEqualTo(ChannelType.SIGNAL);
        assertThat(send.actionType()).isEqualTo(adapter.actionType()).isEqualTo("channel.signal.send");
        assertThat(send.payload("+4917600000001", null, "hi")).isEqualTo(new SignalChannelAdapter.SendPayload("+4917600000001", "hi"));
        assertThat(send.summary("+4917600000001")).doesNotContain("+4917600000001");
        assertThatThrownBy(() -> send.payload("someone", null, "hi"))
                .isInstanceOf(ChannelTextSend.RecipientNotAddressableException.class);
    }

    @Test
    void mediaGoesToTheRecipientWithTheCaptionAsText() {
        SignalMediaSender media = new SignalMediaSender(credentials, api, 1024);

        media.send("+4917600000001", new byte[] {1, 2}, "image/png", "a.png", "look");

        ArgumentCaptor<List<SignalApiClient.Attachment>> files = ArgumentCaptor.forClass(List.class);
        verify(api).send(eq(CONNECTION), eq("+4917600000001"), eq("look"), files.capture());
        assertThat(files.getValue()).hasSize(1);
        assertThat(files.getValue().get(0).contentType()).isEqualTo("image/png");
        assertThat(media.channel()).isEqualTo(ChannelType.SIGNAL);
    }

    @Test
    void mediaRefusesEmptyOversizedAndUnaddressableSends() {
        SignalMediaSender media = new SignalMediaSender(credentials, api, 4);

        assertThatThrownBy(() -> media.send("+4917600000001", new byte[0], "image/png", "a", null))
                .isInstanceOf(ChannelConfigurationException.class);
        assertThatThrownBy(() -> media.send("+4917600000001", new byte[10], "image/png", "a", null))
                .isInstanceOf(ChannelConfigurationException.class);
        assertThatThrownBy(() -> media.send("nobody", new byte[1], "image/png", "a", null))
                .isInstanceOf(ChannelConfigurationException.class);
        verify(api, never()).send(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private SignalChannelConnection connection(SignalReceiveService receive) {
        ObjectProvider<SignalReceiveService> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> receive == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(receive));
        return new SignalChannelConnection(credentials, api, provider);
    }

    private static SignalCredentialResponse response(SignalCredentialStatus status) {
        return new SignalCredentialResponse(
                null, "http://signal-cli:8080", NUMBER, false, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE, status, null, null);
    }

    @Test
    void statusShowsTheNumberWhenHealthyAndTheProblemWhenNot() {
        when(credentials.current()).thenReturn(Optional.of(response(SignalCredentialStatus.ACTIVE)));
        SignalReceiveService receive = mock(SignalReceiveService.class);

        ChannelConnection.Summary healthy = connection(receive).summary();
        assertThat(healthy.state()).isEqualTo("CONNECTED");
        assertThat(healthy.detail()).isEqualTo(NUMBER);

        when(receive.problem(NUMBER)).thenReturn("signal-cli not reachable: timeout");
        ChannelConnection.Summary broken = connection(receive).summary();
        assertThat(broken.state()).isEqualTo("NEEDS_ATTENTION");
        assertThat(broken.detail()).contains("not reachable");
    }

    @Test
    void statusIsNotConnectedForADisconnectedOrMissingCredential() {
        when(credentials.current()).thenReturn(Optional.of(response(SignalCredentialStatus.DISCONNECTED)));
        assertThat(connection(null).summary().state()).isEqualTo("NOT_CONNECTED");

        when(credentials.current()).thenReturn(Optional.empty());
        assertThat(connection(null).summary().state()).isEqualTo("NOT_CONNECTED");
        assertThat(connection(null).key()).isEqualTo("signal");
    }

    @Test
    void probeProvesTheLiveConnectionAndMapsNoConnectionToNotConnected() {
        assertThat(connection(null).probe()).isEqualTo(NUMBER);
        verify(credentials, times(1)).verify(CONNECTION);

        when(credentials.resolveActive()).thenThrow(new SignalNotConfiguredException());
        assertThatThrownBy(() -> connection(null).probe()).isInstanceOf(ChannelNotConnectedException.class);
    }
}
