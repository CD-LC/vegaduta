package com.agenticai.core.omnichannel.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.devices.MobileDeviceRepository;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgentRepository;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Channels grid lists a tile only for a channel that has a {@link ChannelConnection} bean in
 * this deployment. A lean install registers no Slack, Discord, Teams or X beans, and must not show
 * a "+ connect" tile that can never work.
 */
class ChannelStatusServiceTest {

    private static ChannelConnection connection(String key, ChannelConnection.Summary summary) {
        ChannelConnection c = mock(ChannelConnection.class);
        when(c.key()).thenReturn(key);
        when(c.summary()).thenReturn(summary);
        return c;
    }

    private static ChannelStatusService service(List<ChannelConnection> connections) {
        return new ChannelStatusService(
                new WhatsAppDispatchService(List.of(), mock(WhatsAppSendPolicy.class)),
                connections,
                mock(ChannelSessionRepository.class),
                mock(ChannelDefaultAgentRepository.class),
                mock(MobileDeviceRepository.class),
                mock(AgentRepository.class));
    }

    private static List<String> keys(ChannelStatusService service) {
        return service.status().channels().stream()
                .map(ChannelStatusService.ChannelStatusEntry::key)
                .toList();
    }

    @Test
    void channelWithNoConnectionBeanGetsNoTile() {
        ChannelStatusService service =
                service(
                        List.of(
                                connection("telegram", ChannelConnection.Summary.connected("@bot")),
                                connection(
                                        "imessage", ChannelConnection.Summary.notConnected("+ connect"))));

        List<String> keys = keys(service);

        assertEquals(List.of("whatsapp", "telegram", "imessage", "mobile-sdk"), keys);
        for (String absent : List.of("slack", "discord", "teams", "x")) {
            assertFalse(keys.contains(absent), absent + " has no connection bean, so no tile");
        }
    }

    @Test
    void totalCountsOnlyTheTilesThatAreShown() {
        ChannelStatusService service =
                service(List.of(connection("telegram", ChannelConnection.Summary.connected("@bot"))));

        ChannelStatusService.ChannelStatusResponse response = service.status();

        assertEquals(3, response.totalCount());
        assertEquals(1, response.liveCount());
    }

    @Test
    void everyChannelWithAConnectionBeanKeepsItsTileInGridOrder() {
        List<String> all =
                List.of(
                        "whatsappqr",
                        "telegram",
                        "instagram",
                        "messenger",
                        "slack",
                        "discord",
                        "x",
                        "teams",
                        "email",
                        "voice",
                        "sms",
                        "signal",
                        "matrix",
                        "imessage");
        ChannelStatusService service =
                service(
                        all.stream()
                                .map(
                                        k ->
                                                connection(
                                                        k, ChannelConnection.Summary.notConnected("+ connect")))
                                .toList());

        List<String> keys = keys(service);

        assertEquals("whatsapp", keys.get(0));
        assertEquals(all, keys.subList(1, 1 + all.size()));
        assertEquals("mobile-sdk", keys.get(keys.size() - 1));
        assertTrue(keys.containsAll(List.of("slack", "discord", "teams", "x")));
    }
}
