package com.agenticai.core.omnichannel.handoff;

import com.agenticai.core.omnichannel.handoff.OperatorInboxController.ConversationPageResponse;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.ConversationSummary;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.InboxSessionResponse;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.TranscriptMessageResponse;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing facade over {@link OperatorInboxService} and {@link HandoffEscalationService}: maps
 * {@link ChannelSession} / {@link ChannelMessageLog} entities to the controller's DTO records so
 * {@link OperatorInboxController} never touches JPA entities. Each method runs in one transaction
 * so lazily-read entity state is mapped before the session closes.
 */
@Service
public class OperatorInboxViewService {

    private final OperatorInboxService inboxService;
    private final HandoffEscalationService escalationService;

    public OperatorInboxViewService(
            OperatorInboxService inboxService, HandoffEscalationService escalationService) {
        this.inboxService = inboxService;
        this.escalationService = escalationService;
    }

    @Transactional(readOnly = true)
    public List<InboxSessionResponse> listNeedingAttention() {
        return inboxService.listNeedingAttention().stream().map(OperatorInboxViewService::toInbox).toList();
    }

    @Transactional(readOnly = true)
    public ConversationPageResponse searchConversations(
            ChannelType channel, HandoffState state, Boolean paired, String query, int page, int size) {
        Page<ChannelSession> result = inboxService.searchConversations(
            channel, state, paired, query,
            PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100)));
        return new ConversationPageResponse(
            result.getContent().stream().map(OperatorInboxViewService::toSummary).toList(),
            result.getTotalElements(), result.getNumber(), result.getTotalPages());
    }

    public OperatorInboxService.InboxStats stats() {
        return inboxService.stats();
    }

    @Transactional(readOnly = true)
    public List<TranscriptMessageResponse> transcript(UUID sessionId) {
        return inboxService.transcript(sessionId).stream().map(OperatorInboxViewService::toTranscript).toList();
    }

    @Transactional
    public InboxSessionResponse escalate(UUID sessionId, String reason, String subject) {
        return toInbox(escalationService.escalate(sessionId, reason, subject));
    }

    @Transactional
    public InboxSessionResponse takeOver(UUID sessionId, String subject) {
        return toInbox(inboxService.takeOver(sessionId, subject));
    }

    @Transactional
    public InboxSessionResponse reply(UUID sessionId, String body, String subject) {
        return toInbox(inboxService.reply(sessionId, body, subject));
    }

    @Transactional
    public InboxSessionResponse release(UUID sessionId, String subject) {
        return toInbox(inboxService.release(sessionId, subject));
    }

    private static InboxSessionResponse toInbox(ChannelSession session) {
        return new InboxSessionResponse(
            session.getId(),
            session.getChannel(),
            mask(session.getExternalId()),
            session.getAgentId(),
            session.getHandoffState(),
            session.getEscalationReason(),
            session.getEscalatedAt(),
            session.getTakenOverBy(),
            session.getTakenOverAt(),
            session.getLastMessagePreview(),
            session.getLastMessageAt(),
            session.getCreatedAt()
        );
    }

    private static ConversationSummary toSummary(ChannelSession session) {
        return new ConversationSummary(
            session.getId(),
            session.getChannel(),
            mask(session.getExternalId()),
            mask(session.getResolvedPhoneNumber()),
            session.isPaired(),
            session.isPaired() ? null : session.getPairingCode(),
            session.getAgentId(),
            session.getHandoffState(),
            session.getEscalationReason(),
            session.getEscalatedAt(),
            session.getTakenOverBy(),
            session.getTakenOverAt(),
            session.getLastMessagePreview(),
            session.getLastMessageAt(),
            session.getCreatedAt()
        );
    }

    private static TranscriptMessageResponse toTranscript(ChannelMessageLog log) {
        return new TranscriptMessageResponse(
            log.getId(), log.getDirection(), log.getAuthor(), log.getBody(), log.getCreatedAt());
    }

    private static String mask(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
