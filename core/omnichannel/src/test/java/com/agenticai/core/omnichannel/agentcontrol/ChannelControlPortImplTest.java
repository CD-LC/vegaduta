package com.agenticai.core.omnichannel.agentcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.messaging.ChannelControlPort.ChannelOverviewEntry;
import com.agenticai.core.agents.tools.messaging.ChannelControlPort.ChannelSessionActionResult;
import com.agenticai.core.agents.tools.messaging.ChannelControlPort.ChannelSessionInfo;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.status.ChannelStatusService;
import com.agenticai.core.omnichannel.status.ChannelStatusService.ChannelStatusEntry;
import com.agenticai.core.omnichannel.status.ChannelStatusService.ChannelStatusResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** The agent's channel overview, session listing and the three session actions. */
class ChannelControlPortImplTest {

    private ChannelStatusService status;
    private ChannelSessionRepository sessions;
    private ChannelSessionService sessionService;
    private ChannelControlPortImpl port;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        status = mock(ChannelStatusService.class);
        sessions = mock(ChannelSessionRepository.class);
        sessionService = mock(ChannelSessionService.class);
        port = new ChannelControlPortImpl(status, sessions, sessionService);
    }

    private ChannelSession session() {
        ChannelSession s =
                new ChannelSession(ChannelType.SLACK, "C1", "s", UUID.randomUUID(), "hello");
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    void theOverviewMapsEachChannelAndStringifiesTheDefaultAgent() {
        UUID agent = UUID.randomUUID();
        Instant now = Instant.now();
        when(status.status())
                .thenReturn(
                        new ChannelStatusResponse(
                                1,
                                2,
                                List.of(
                                        new ChannelStatusEntry(
                                                "slack", "Slack", "LIVE", "ok", 3, now, agent),
                                        new ChannelStatusEntry(
                                                "email", "Email", "OFF", "none", 0, null, null)),
                                List.of()));

        List<ChannelOverviewEntry> overview = port.overview();

        assertThat(overview).hasSize(2);
        assertThat(overview.get(0).defaultAgentId()).isEqualTo(agent.toString());
        assertThat(overview.get(1).defaultAgentId()).isNull();
    }

    @Test
    void sessionsAreFilteredByChannelAndCapped() {
        when(sessions.searchConversations(any(), any(), any(), eq(""), any()))
                .thenReturn(new PageImpl<>(List.of(session())));

        List<ChannelSessionInfo> rows = port.sessions("Slack", 500);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).channel()).isEqualTo("slack");
        assertThat(rows.get(0).paired()).isFalse();
        verify(sessions)
                .searchConversations(ChannelType.SLACK, null, null, "", PageRequest.of(0, 50));

        port.sessions(null, 0);
        verify(sessions)
                .searchConversations(
                        isNull(), isNull(), isNull(), eq(""), eq(PageRequest.of(0, 1)));
    }

    @Test
    void anUnknownChannelListsNothing() {
        assertThat(port.sessions("carrier-pigeon", 10)).isEmpty();
        verify(sessions, never()).searchConversations(any(), any(), any(), any(), any());
    }

    @Test
    void approveUnpairAndRebindReportWhatTheyDid() {
        ChannelSession s = session();
        when(sessionService.approvePairing(id)).thenReturn(s);
        when(sessionService.unpairSession(id)).thenReturn(s);
        UUID agent = UUID.randomUUID();

        assertThat(port.actOnSession(" Approve_Pairing ", id.toString(), null).detail())
                .contains("C1");
        assertThat(port.actOnSession("unpair", id.toString(), null).detail())
                .contains("new pairing code");
        ChannelSessionActionResult rebound =
                port.actOnSession("rebind_agent", id.toString(), " " + agent + " ");

        assertThat(rebound.ok()).isTrue();
        verify(sessionService).rebindAgent(id, agent);
    }

    @Test
    void badInputIsReportedRatherThanThrown() {
        assertThat(port.actOnSession("unpair", "not-a-uuid", null).ok()).isFalse();
        assertThat(port.actOnSession("rebind_agent", id.toString(), " ").detail())
                .contains("requires agentId");
        assertThat(port.actOnSession("rebind_agent", id.toString(), null).ok()).isFalse();
        assertThat(port.actOnSession("explode", id.toString(), null).detail())
                .contains("Unknown action");
        assertThat(port.actOnSession(null, id.toString(), null).ok()).isFalse();

        when(sessionService.approvePairing(id)).thenThrow(new IllegalStateException("gone"));
        ChannelSessionActionResult failed =
                port.actOnSession("approve_pairing", id.toString(), null);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.detail()).isEqualTo("gone");

        assertThat(port.actOnSession("rebind_agent", id.toString(), "nope").ok()).isFalse();
    }
}
