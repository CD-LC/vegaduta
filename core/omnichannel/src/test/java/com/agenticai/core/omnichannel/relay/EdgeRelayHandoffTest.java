package com.agenticai.core.omnichannel.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.ChannelMessageDirection;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelMessageLogRepository;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EdgeRelayHandoffTest {

    private final EdgeRelayService relay = mock(EdgeRelayService.class);
    private final ChannelMessageLogRepository messageLog = mock(ChannelMessageLogRepository.class);
    private final EdgeRelayHandoff handoff = new EdgeRelayHandoff(relay, messageLog, 3);
    private final UUID sessionId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private ChannelSession session;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant_a", "webhook", Set.of()));
        session = mock(ChannelSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getAgentId()).thenReturn(agentId);
        when(session.getExternalId()).thenReturn("+15550001111");
        when(relay.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void noRelayOnlineMeansHostedWithoutTouchingTheQueue() {
        when(relay.isOnline("tenant_a", ChannelType.SMS)).thenReturn(false);
        assertTrue(handoff.answer(ChannelType.SMS, session, "hi").isEmpty());
        verify(relay, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onlineRelayGetsTheMessageWithRecentHistoryOldestFirstAndItsAnswerIsReturned() {
        when(relay.isOnline("tenant_a", ChannelType.SMS)).thenReturn(true);
        // Rows are built BEFORE the when() below: the helper stubs its own mocks, and Mockito
        // forbids interleaved stubbing inside a pending when().
        List<ChannelMessageLog> newestFirst = List.of(
                row("what time do you open?", ChannelMessageDirection.INBOUND),   // the current message
                row("Hello! How can I help?", ChannelMessageDirection.OUTBOUND),
                row("hello", ChannelMessageDirection.INBOUND),
                row("old reply", ChannelMessageDirection.OUTBOUND),
                row("very old", ChannelMessageDirection.INBOUND));
        when(messageLog.findTop200ByChannelSessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(newestFirst);
        RelayJob job = new RelayJob("j1", "tenant_a", ChannelType.SMS, sessionId, agentId, "***1111", "m", List.of(), 0L, RelayJob.QUEUED, null);
        when(relay.enqueue(eq("tenant_a"), eq(ChannelType.SMS), eq(sessionId), eq(agentId), eq("***1111"), eq("what time do you open?\n\n[hint]"), any()))
                .thenReturn(Optional.of(job));
        when(relay.awaitReply("tenant_a", "j1")).thenReturn(Optional.of("We open at 9."));

        Optional<String> answer = handoff.answer(ChannelType.SMS, session, "what time do you open?\n\n[hint]");

        assertEquals(Optional.of("We open at 9."), answer);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelayJob.RelayTurn>> history = ArgumentCaptor.forClass(List.class);
        verify(relay).enqueue(any(), any(), any(), any(), any(), any(), history.capture());
        assertEquals(List.of("old reply", "hello", "Hello! How can I help?"),
                history.getValue().stream().map(RelayJob.RelayTurn::text).toList());
        assertEquals(List.of("assistant", "user", "assistant"),
                history.getValue().stream().map(RelayJob.RelayTurn::role).toList());
    }

    private static ChannelMessageLog row(String body, ChannelMessageDirection direction) {
        ChannelMessageLog row = mock(ChannelMessageLog.class);
        when(row.getBody()).thenReturn(body);
        when(row.getDirection()).thenReturn(direction);
        return row;
    }
}
