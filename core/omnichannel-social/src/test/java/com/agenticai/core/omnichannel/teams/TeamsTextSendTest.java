package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link ChannelTextSend} half of Teams: the kernel-facing contract (channel, action type,
 * summary) plus the one Teams-specific rule - a conversation only becomes addressable once the
 * platform has learned its serviceUrl from an inbound activity.
 */
class TeamsTextSendTest {

    private TeamsCredentialService credentialService;
    private TeamsTextSend send;

    @BeforeEach
    void setUp() {
        credentialService = mock(TeamsCredentialService.class);
        send = new TeamsTextSend(credentialService);
    }

    @Test
    void exposesTheKernelContract() {
        assertThat(send.channel()).isEqualTo(ChannelType.TEAMS);
        assertThat(send.actionType()).isEqualTo(TeamsChannelAdapter.ACTION_TYPE);
        assertThat(send.summary("conv-1")).isEqualTo("Send Teams message to conv-1");
    }

    @Test
    void payloadUsesTheRememberedServiceUrlForTheConversation() {
        when(credentialService.serviceUrlFor("conv-1"))
                .thenReturn(Optional.of("https://smba.trafficmanager.net/amer/"));

        Object payload = send.payload("conv-1", "ignored-subject", "hello team");

        assertThat(payload)
                .isEqualTo(
                        new TeamsChannelAdapter.SendPayload(
                                "https://smba.trafficmanager.net/amer/",
                                "conv-1",
                                "hello team",
                                null));
    }

    @Test
    void anUnknownConversationCannotBeAddressed() {
        when(credentialService.serviceUrlFor("never-seen")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> send.payload("never-seen", null, "hi"))
                .isInstanceOf(ChannelTextSend.RecipientNotAddressableException.class)
                .hasMessageContaining("serviceUrl");
    }
}
