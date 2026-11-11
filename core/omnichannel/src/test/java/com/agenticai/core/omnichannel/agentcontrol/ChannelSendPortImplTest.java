package com.agenticai.core.omnichannel.agentcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.messaging.ChannelSendPort.ChannelSendResult;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.ImpersonationContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How an agent's channel_send_message call is resolved, serialized and gated. */
class ChannelSendPortImplTest {

    record Payload(String to, String body) {}

    private HumanApprovalGateService gate;
    private ChannelTextSend slack;
    private ChannelSendPortImpl port;

    @BeforeEach
    void setUp() {
        gate = mock(HumanApprovalGateService.class);
        slack = mock(ChannelTextSend.class);
        when(slack.channel()).thenReturn(ChannelType.SLACK);
        when(slack.actionType()).thenReturn("channel.slack.send");
        when(slack.summary(anyString()))
                .thenAnswer(inv -> "Send Slack message to " + inv.getArgument(0));
        when(slack.payload(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenAnswer(inv -> new Payload(inv.getArgument(0), inv.getArgument(2)));
        port = new ChannelSendPortImpl(gate, new ChannelTextSends(List.of(slack)));
    }

    @AfterEach
    void tearDown() {
        ImpersonationContext.clear();
    }

    @Test
    void anAutoApprovedSendReportsApprovedWithoutARequestId() {
        when(gate.evaluateInTurn(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());

        ChannelSendResult result = port.send(" Slack ", "C1", null, "hi", "agent:1");

        assertThat(result.approved()).isTrue();
        assertThat(result.pendingRequestId()).isNull();
        assertThat(result.summary()).isEqualTo("Send Slack message to C1");
        verify(gate)
                .evaluateInTurn(
                        "channel.slack.send",
                        "channel_send_message",
                        "Send Slack message to C1",
                        "{\"to\":\"C1\",\"body\":\"hi\"}",
                        "agent:1");
    }

    @Test
    void aSensitiveSendIsReportedPendingWithItsRequestId() {
        UUID request = UUID.randomUUID();
        when(gate.evaluateInTurn(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.pending(request));

        ChannelSendResult result = port.send("slack", "C1", null, "hi", "agent:1");

        assertThat(result.approved()).isFalse();
        assertThat(result.pendingRequestId()).isEqualTo(request.toString());
    }

    @Test
    void unknownBlankVoiceAndUnregisteredChannelsAreRefusedBeforeTheGate() {
        for (String channel : new String[] {"carrier-pigeon", "", null, "voice", "telegram"}) {
            assertThatThrownBy(() -> port.send(channel, "x", null, "hi", "a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown channel");
        }
        verify(gate, never())
                .evaluateInTurn(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anUnaddressableRecipientBecomesAnIllegalArgument() {
        when(slack.payload(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenThrow(
                        new ChannelTextSend.RecipientNotAddressableException("never messaged us"));

        assertThatThrownBy(() -> port.send("slack", "C1", null, "hi", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("never messaged us");
    }

    @Test
    void aPayloadThatCannotBeSerializedIsAChannelError() {
        when(slack.payload(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(
                        new Object() {
                            public String getBoom() {
                                throw new IllegalStateException("boom");
                            }
                        });

        assertThatThrownBy(() -> port.send("slack", "C1", null, "hi", "a"))
                .isInstanceOf(ChannelPayloadSerializationException.class)
                .hasMessageContaining("slack");
    }

    @Test
    void aViewAsSessionCannotSend() {
        ImpersonationContext.set(
                new ImpersonationContext.Session(UUID.randomUUID(), "admin", "a@x", "acme", "u1"));

        assertThatThrownBy(() -> port.send("slack", "C1", null, "hi", "a"))
                .isInstanceOf(RuntimeException.class);
        verify(gate, never())
                .evaluateInTurn(anyString(), anyString(), anyString(), anyString(), eq("a"));
    }
}
