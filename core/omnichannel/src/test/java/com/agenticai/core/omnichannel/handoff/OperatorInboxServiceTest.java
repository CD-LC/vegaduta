package com.agenticai.core.omnichannel.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelMessageDirection;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelMessageLogRepository;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionNotFoundException;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** Claim, release and reply rules of the operator inbox, including the self-approving gate. */
class OperatorInboxServiceTest {

    private ChannelSessionRepository sessions;
    private ChannelMessageLogRepository log;
    private HumanApprovalGateService gate;
    private AuditLogService audit;
    private ChannelTextSend slack;
    private OperatorInboxService service;

    @BeforeEach
    void setUp() {
        sessions = mock(ChannelSessionRepository.class);
        log = mock(ChannelMessageLogRepository.class);
        gate = mock(HumanApprovalGateService.class);
        audit = mock(AuditLogService.class);
        slack = mock(ChannelTextSend.class);
        when(slack.channel()).thenReturn(ChannelType.SLACK);
        when(slack.actionType()).thenReturn("channel.slack.send");
        when(slack.payload(anyString(), anyString(), anyString()))
                .thenReturn(new Payload("C0123456789"));
        service =
                new OperatorInboxService(
                        sessions, log, gate, audit, new ChannelTextSends(List.of(slack)));
    }

    record Payload(String to) {}

    private ChannelSession session(ChannelType channel, boolean paired) {
        ChannelSession s = new ChannelSession(channel, "C0123456789", "sess-1", UUID.randomUUID());
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        if (paired) {
            s.approvePairing();
        }
        when(sessions.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void theInboxListsEscalatedAndHeldConversations() {
        ChannelSession one = session(ChannelType.SLACK, true);
        when(sessions.findByHandoffStateInOrderByLastMessageAtDesc(any())).thenReturn(List.of(one));

        assertThat(service.listNeedingAttention()).containsExactly(one);
    }

    @Test
    void searchNeverPassesANullQueryDown() {
        service.searchConversations(
                ChannelType.SLACK, HandoffState.HUMAN, true, null, PageRequest.of(0, 5));

        verify(sessions)
                .searchConversations(
                        ChannelType.SLACK, HandoffState.HUMAN, true, "", PageRequest.of(0, 5));
    }

    @Test
    void statsCountsEachBucketSeparately() {
        when(sessions.countByHandoffState(HandoffState.AWAITING_HUMAN)).thenReturn(2L);
        when(sessions.countByHandoffState(HandoffState.HUMAN)).thenReturn(1L);
        when(sessions.countByPairedFalse()).thenReturn(4L);
        when(sessions.countByHandoffStateAndPairedTrue(HandoffState.AGENT)).thenReturn(9L);
        when(sessions.count()).thenReturn(16L);

        assertThat(service.stats()).isEqualTo(new OperatorInboxService.InboxStats(2, 1, 4, 9, 16));
    }

    @Test
    void aTranscriptIsReturnedOldestFirstAndAnUnknownSessionIs404() {
        ChannelSession s = session(ChannelType.SLACK, true);
        ChannelMessageLog newest = mock(ChannelMessageLog.class);
        ChannelMessageLog oldest = mock(ChannelMessageLog.class);
        when(log.findTop200ByChannelSessionIdOrderByCreatedAtDesc(s.getId()))
                .thenReturn(List.of(newest, oldest));

        assertThat(service.transcript(s.getId())).containsExactly(oldest, newest);
        assertThatThrownBy(() -> service.transcript(UUID.randomUUID()))
                .isInstanceOf(ChannelSessionNotFoundException.class);
    }

    @Test
    void takeOverClaimsAPairedConversationAndAuditsOnlyTheFirstClaim() {
        ChannelSession s = session(ChannelType.SLACK, true);

        service.takeOver(s.getId(), "op-1");
        assertThat(s.isHeldByHuman()).isTrue();
        assertThat(s.getTakenOverBy()).isEqualTo("op-1");
        verify(audit)
                .record(
                        eq("op-1"),
                        eq("channel_session.take_over"),
                        eq("channel_session"),
                        eq(s.getId().toString()),
                        eq(AuditOutcome.SUCCESS),
                        contains("***6789"));

        service.takeOver(s.getId(), "op-1");
        verify(audit, org.mockito.Mockito.times(1))
                .record(
                        anyString(),
                        eq("channel_session.take_over"),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void takeOverRefusesVoiceUnpairedAndOtherOperatorsConversations() {
        ChannelSession voice = session(ChannelType.VOICE, true);
        assertThatThrownBy(() -> service.takeOver(voice.getId(), "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VOICE");

        ChannelSession unpaired = session(ChannelType.SLACK, false);
        assertThatThrownBy(() -> service.takeOver(unpaired.getId(), "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pairing");

        ChannelSession held = session(ChannelType.SLACK, true);
        held.takeOver("op-2");
        assertThatThrownBy(() -> service.takeOver(held.getId(), "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another operator");
        assertThat(held.getTakenOverBy()).isEqualTo("op-2");
    }

    @Test
    void releaseHandsBackAnyNonAgentStateAndIsANoOpForAgentOnes() {
        ChannelSession held = session(ChannelType.SLACK, true);
        held.takeOver("op-2");

        service.release(held.getId(), "op-9");

        assertThat(held.getHandoffState()).isEqualTo(HandoffState.AGENT);
        assertThat(held.getTakenOverBy()).isNull();
        verify(audit)
                .record(
                        eq("op-9"),
                        eq("channel_session.release_to_agent"),
                        anyString(),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        contains("was HUMAN"));

        service.release(held.getId(), "op-9");
        verify(audit, org.mockito.Mockito.times(1))
                .record(
                        anyString(),
                        eq("channel_session.release_to_agent"),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void aReplyNeedsABodyAndAHeldConversation() {
        ChannelSession s = session(ChannelType.SLACK, true);

        assertThatThrownBy(() -> service.reply(s.getId(), " ", "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> service.reply(s.getId(), null, "op-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reply(s.getId(), "hello", "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Take this conversation over");
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anAutoApprovedReplyIsSentLoggedAndAudited() {
        ChannelSession s = session(ChannelType.SLACK, true);
        s.takeOver("op-1");
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());

        service.reply(s.getId(), "On it!", "op-1");

        verify(gate)
                .evaluate(
                        eq("channel.slack.send"),
                        contains("Operator reply on SLACK to ***6789"),
                        eq("{\"to\":\"C0123456789\"}"),
                        eq("operator:op-1"));
        verify(gate, never()).approve(any(), anyString());
        ArgumentCaptor<ChannelMessageLog> saved = ArgumentCaptor.forClass(ChannelMessageLog.class);
        verify(log).save(saved.capture());
        assertThat(saved.getValue().getDirection()).isEqualTo(ChannelMessageDirection.OUTBOUND);
        verify(audit)
                .record(
                        eq("op-1"),
                        eq("channel_session.operator_reply"),
                        anyString(),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        contains("(6 chars)"));
    }

    @Test
    void aPendingReplyIsApprovedByTheOperatorWhoWroteIt() {
        ChannelSession s = session(ChannelType.SLACK, true);
        s.takeOver("op-1");
        UUID requestId = UUID.randomUUID();
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.pending(requestId));

        service.reply(s.getId(), "On it!", "op-1");

        verify(gate).approve(requestId, "op-1");
    }

    @Test
    void aFailedSendWritesNoTranscriptRow() {
        ChannelSession s = session(ChannelType.SLACK, true);
        s.takeOver("op-1");
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("channel down"));

        assertThatThrownBy(() -> service.reply(s.getId(), "hi", "op-1"))
                .isInstanceOf(IllegalStateException.class);
        verify(log, never()).save(any());
    }

    @Test
    void voiceChannelsAndChannelsWithoutASendPathCannotBeRepliedTo() {
        ChannelSession voice = session(ChannelType.VOICE, true);
        voice.takeOver("op-1");
        assertThatThrownBy(() -> service.reply(voice.getId(), "hi", "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VOICE");

        ChannelSession telegram = session(ChannelType.TELEGRAM, true);
        telegram.takeOver("op-1");
        assertThatThrownBy(() -> service.reply(telegram.getId(), "hi", "op-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No send path");
    }

    @Test
    void anUnaddressableRecipientBecomesASerializationError() {
        ChannelSession s = session(ChannelType.SLACK, true);
        s.takeOver("op-1");
        when(slack.payload(anyString(), anyString(), anyString()))
                .thenThrow(
                        new ChannelTextSend.RecipientNotAddressableException("never messaged us"));

        assertThatThrownBy(() -> service.reply(s.getId(), "hi", "op-1"))
                .isInstanceOf(ChannelPayloadSerializationException.class)
                .hasMessageContaining("never messaged us");
    }

    @Test
    void theEmailSubjectThreadsOnTheLastInboundSubject() {
        ChannelSession s = session(ChannelType.SLACK, true);
        s.takeOver("op-1");
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());

        service.reply(s.getId(), "x", "op-1");
        verify(slack).payload("C0123456789", "Re: your message", "x");

        s.touch("Invoice 12");
        service.reply(s.getId(), "x", "op-1");
        verify(slack).payload("C0123456789", "Re: Invoice 12", "x");

        s.touch("RE: Invoice 12");
        service.reply(s.getId(), "x", "op-1");
        verify(slack).payload("C0123456789", "RE: Invoice 12", "x");
    }
}
