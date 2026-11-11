package com.agenticai.core.omnichannel.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.omnichannel.handoff.HandoffEscalationService.HandoffResult;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionNotFoundException;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Who may be escalated, what is audited, and the never-throws contract of the agent tool. */
class HandoffEscalationServiceTest {

    private ChannelSessionRepository sessions;
    private NotificationService notifications;
    private AuditLogService audit;
    private HandoffEscalationService service;

    @BeforeEach
    void setUp() {
        sessions = mock(ChannelSessionRepository.class);
        notifications = mock(NotificationService.class);
        audit = mock(AuditLogService.class);
        service = new HandoffEscalationService(sessions, notifications, audit);
    }

    private ChannelSession session(ChannelType channel, boolean paired) {
        ChannelSession s =
                new ChannelSession(channel, "+919812345678", "agent-sess", UUID.randomUUID());
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        if (paired) {
            s.approvePairing();
        }
        when(sessions.findById(s.getId())).thenReturn(Optional.of(s));
        when(sessions.findFirstBySessionId("agent-sess")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void anUnknownSessionIs404() {
        assertThatThrownBy(() -> service.escalate(UUID.randomUUID(), "r", "op"))
                .isInstanceOf(ChannelSessionNotFoundException.class);
    }

    @Test
    void voiceAndUnpairedConversationsCannotBeEscalated() {
        ChannelSession voice = session(ChannelType.VOICE, true);
        assertThatThrownBy(() -> service.escalate(voice.getId(), "r", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VOICE");

        ChannelSession unpaired = session(ChannelType.WHATSAPP, false);
        assertThatThrownBy(() -> service.escalate(unpaired.getId(), "r", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pairing");
        verify(audit, never())
                .record(anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void escalationFlagsTheSessionAuditsAndNotifiesEveryAdmin() {
        ChannelSession s = session(ChannelType.WHATSAPP, true);

        service.escalate(s.getId(), "wants a refund", "op-1");

        assertThat(s.getHandoffState()).isEqualTo(HandoffState.AWAITING_HUMAN);
        verify(audit)
                .record(
                        eq("op-1"),
                        eq("channel_session.escalate_to_human"),
                        eq("channel_session"),
                        eq(s.getId().toString()),
                        eq(AuditOutcome.SUCCESS),
                        contains("to AWAITING_HUMAN - reason: wants a refund"));
        verify(notifications)
                .create(
                        isNull(),
                        eq(NotificationCategory.SYSTEM),
                        eq("A conversation needs a human"),
                        contains("***5678 was escalated: wants a refund"),
                        eq(NotificationSeverity.WARNING),
                        eq("/admin/inbox"),
                        isNull());
    }

    @Test
    void aBlankRequesterIsAuditedAsTheSystemAndABlankReasonIsOmitted() {
        ChannelSession s = session(ChannelType.WHATSAPP, true);

        service.escalate(s.getId(), " ", " ");

        verify(audit)
                .record(
                        eq("system:handoff"),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(d -> !d.contains("reason:")));
        verify(notifications)
                .create(
                        any(),
                        any(),
                        anyString(),
                        org.mockito.ArgumentMatchers.endsWith("was escalated."),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    void anAlreadyHeldConversationIsAuditedWithoutNotifyingAnyone() {
        ChannelSession s = session(ChannelType.WHATSAPP, true);
        s.takeOver("op-2");

        service.escalate(s.getId(), "again", "op-1");

        assertThat(s.getHandoffState()).isEqualTo(HandoffState.HUMAN);
        verify(audit)
                .record(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        contains("no state change"));
        verify(notifications, never())
                .create(any(), any(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aFailingNotificationNeverFailsTheEscalation() {
        ChannelSession s = session(ChannelType.WHATSAPP, true);
        doThrow(new IllegalStateException("bell down"))
                .when(notifications)
                .create(any(), any(), anyString(), anyString(), any(), anyString(), any());

        service.escalate(s.getId(), "r", "op-1");

        assertThat(s.getHandoffState()).isEqualTo(HandoffState.AWAITING_HUMAN);
    }

    @Test
    void theAgentToolEscalatesPairedChannelConversations() {
        ChannelSession s = session(ChannelType.WHATSAPP, true);

        HandoffResult result = service.requestHumanForAgentSession("agent-sess", "needs a person");

        assertThat(result.escalated()).isTrue();
        assertThat(result.detail()).contains("team member has been notified");
        assertThat(s.getHandoffState()).isEqualTo(HandoffState.AWAITING_HUMAN);
        verify(audit)
                .record(
                        eq("agent-session:agent-sess"),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void theAgentToolExplainsWhyItCannotEscalate() {
        assertThat(service.requestHumanForAgentSession("playground", "r").detail())
                .contains("not on a messaging channel");

        session(ChannelType.VOICE, true);
        assertThat(service.requestHumanForAgentSession("agent-sess", "r").detail())
                .contains("voice call");

        ChannelSession unpaired =
                new ChannelSession(ChannelType.WHATSAPP, "x1234", "unpaired", UUID.randomUUID());
        when(sessions.findFirstBySessionId("unpaired")).thenReturn(Optional.of(unpaired));
        HandoffResult result = service.requestHumanForAgentSession("unpaired", "r");
        assertThat(result.escalated()).isFalse();
        assertThat(result.detail()).contains("not been approved");
    }

    @Test
    void theAgentToolNeverThrows() {
        when(sessions.findFirstBySessionId("boom")).thenThrow(new IllegalStateException("db down"));

        HandoffResult result = service.requestHumanForAgentSession("boom", "r");

        assertThat(result.escalated()).isFalse();
        assertThat(result.detail()).contains("could not be completed");
    }
}
