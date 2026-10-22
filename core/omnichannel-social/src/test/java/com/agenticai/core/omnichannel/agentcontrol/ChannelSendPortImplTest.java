package com.agenticai.core.omnichannel.agentcontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.discord.DiscordChannelAdapter;
import com.agenticai.core.omnichannel.discord.DiscordTextSend;
import com.agenticai.core.omnichannel.email.EmailChannelAdapter;
import com.agenticai.core.omnichannel.email.EmailTextSend;
import com.agenticai.core.omnichannel.meta.MetaInstagramChannelAdapter;
import com.agenticai.core.omnichannel.meta.MetaInstagramTextSend;
import com.agenticai.core.omnichannel.meta.MetaMessengerChannelAdapter;
import com.agenticai.core.omnichannel.meta.MetaMessengerTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.slack.SlackChannelAdapter;
import com.agenticai.core.omnichannel.slack.SlackTextSend;
import com.agenticai.core.omnichannel.teams.TeamsChannelAdapter;
import com.agenticai.core.omnichannel.teams.TeamsCredentialService;
import com.agenticai.core.omnichannel.teams.TeamsTextSend;
import com.agenticai.core.omnichannel.telegram.TelegramChannelAdapter;
import com.agenticai.core.omnichannel.telegram.TelegramTextSend;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppChannelAdapter;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppTextSend;
import com.agenticai.core.omnichannel.x.XChannelAdapter;
import com.agenticai.core.omnichannel.x.XTextSend;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent sends reach the approval gate with each channel's own action type, SendPayload JSON and
 * summary - now supplied by the channel packages' ChannelTextSend beans instead of a switch over
 * the adapters.
 */
class ChannelSendPortImplTest {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private HumanApprovalGateService gate;
    private TeamsCredentialService teams;
    private ChannelSendPortImpl port;

    @BeforeEach
    void setUp() {
        gate = mock(HumanApprovalGateService.class);
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        teams = mock(TeamsCredentialService.class);
        port =
                new ChannelSendPortImpl(
                        gate,
                        new ChannelTextSends(
                                List.of(
                                        new TwilioWhatsAppTextSend(),
                                        new TelegramTextSend(),
                                        new SlackTextSend(),
                                        new DiscordTextSend(),
                                        new TeamsTextSend(teams),
                                        new EmailTextSend(),
                                        new MetaMessengerTextSend(),
                                        new MetaInstagramTextSend(),
                                        new XTextSend())));
    }

    private void assertSent(String channel, String actionType, Object payload, String summary)
            throws Exception {
        ChannelSendPort.ChannelSendResult result =
                port.send(channel, "to-1", "Subj", "hello", "agent:1");
        verify(gate).evaluate(actionType, summary, MAPPER.writeValueAsString(payload), "agent:1");
        assertTrue(result.approved(), channel);
        assertEquals(summary, result.summary());
    }

    @Test
    void everyChannelSendsItsOwnActionTypePayloadAndSummary() throws Exception {
        when(teams.serviceUrlFor("to-1")).thenReturn(Optional.of("https://smba.example/"));
        assertSent(
                " WhatsApp ",
                TwilioWhatsAppChannelAdapter.ACTION_TYPE,
                new TwilioWhatsAppChannelAdapter.SendPayload("to-1", "hello"),
                "Send WhatsApp message to to-1");
        assertSent(
                "telegram",
                TelegramChannelAdapter.ACTION_TYPE,
                new TelegramChannelAdapter.SendPayload("to-1", "hello"),
                "Send Telegram message to to-1");
        assertSent(
                "slack",
                SlackChannelAdapter.ACTION_TYPE,
                new SlackChannelAdapter.SendPayload("to-1", "hello"),
                "Send Slack message to to-1");
        assertSent(
                "discord",
                DiscordChannelAdapter.ACTION_TYPE,
                new DiscordChannelAdapter.SendPayload("to-1", "hello"),
                "Send Discord message to to-1");
        assertSent(
                "teams",
                TeamsChannelAdapter.ACTION_TYPE,
                new TeamsChannelAdapter.SendPayload("https://smba.example/", "to-1", "hello", null),
                "Send Teams message to to-1");
        assertSent(
                "email",
                EmailChannelAdapter.ACTION_TYPE,
                new EmailChannelAdapter.SendPayload("to-1", "Subj", "hello"),
                "Send email to to-1");
        assertSent(
                "messenger",
                MetaMessengerChannelAdapter.ACTION_TYPE,
                new MetaMessengerChannelAdapter.SendPayload("to-1", "hello"),
                "Send Messenger message to to-1");
        assertSent(
                "instagram",
                MetaInstagramChannelAdapter.ACTION_TYPE,
                new MetaInstagramChannelAdapter.SendPayload("to-1", "hello"),
                "Send Instagram DM to to-1");
        assertSent(
                "x",
                XChannelAdapter.ACTION_TYPE,
                new XChannelAdapter.SendPayload("to-1", "hello"),
                "Send X DM to to-1");
    }

    @Test
    void unknownChannelsAndVoiceAreRejectedWithTheSameMessage() {
        for (String channel : new String[] {"sms", "voice", "", null}) {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> port.send(channel, "to-1", null, "hello", "agent:1"));
            assertEquals(
                    "Unknown channel '"
                            + channel
                            + "' - expected one of: whatsapp, telegram, "
                            + "slack, discord, teams, email, messenger, instagram, x",
                    e.getMessage());
        }
    }

    @Test
    void aTeamsConversationWithNoRouteIsAClientError() {
        when(teams.serviceUrlFor("to-1")).thenReturn(Optional.empty());
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> port.send("teams", "to-1", null, "hello", "agent:1"));
        assertTrue(e.getMessage().startsWith("Unknown Teams conversation id"), e.getMessage());
    }
}
