package com.agenticai.core.omnichannel.handoff;

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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator's side of live human handoff (migration V214): list the conversations that need a
 * person, read one conversation's transcript, claim it ({@link #takeOver}), speak as a human
 * ({@link #reply}), and hand it back ({@link #release}). The inbound-suppression counterpart lives
 * in {@code ChannelSessionService#resolveOrCreate} (see {@code ConversationHeldByHumanException}).
 *
 * <p><b>The reply path reuses the one outbound chain every channel already has</b> - {@link
 * HumanApprovalGateService#evaluate} with the SAME per-channel {@code ACTION_TYPE} + payload record
 * each webhook controller uses for agent replies - because that chain is where audit rows, retry
 * with backoff and dead-lettering live, and because inventing a parallel "operator send" action
 * type is a documented trap: it would not match the {@code channel.*.send} sensitivity glob (so no
 * approval by default) but would ALSO have no registered executor, so nothing would ever actually
 * send.
 *
 * <p><b>The double-gate problem, and why reply() approves its own request.</b> {@code
 * channel.*.send} is DEFAULT-SENSITIVE ({@code ApprovalPolicyService}), so on an out-of-the-box
 * tenant an operator's reply would land in the Approvals queue as PENDING - awaiting a human,
 * authored by that same human. The approval policy exists to insert a person into automated sends;
 * this call site IS the person, authenticated as a tenant-admin, typing the text themselves. So
 * when {@code evaluate} returns pending, this service immediately calls {@link
 * HumanApprovalGateService#approve} with the operator as decider. This deliberately does NOT bypass
 * the gate: the {@code ApprovalRequest} row is still recorded (requested-by AND decided-by naming
 * the operator), execution still flows through the gate's single {@code
 * ApprovalActionExecutor#execute} chokepoint (whose structural invariant - only the gate ever
 * executes - stays intact), and retry/backoff on a flaky channel API is inherited for free. An
 * admin deciding their own request is the explicitly-sanctioned single-admin-tenant flow in {@code
 * ApprovalController#rejectSelfApproval}'s javadoc; the controller above this service restricts
 * reply() to tenant-admins for exactly that consistency.
 *
 * <p><b>reply() is deliberately not {@code @Transactional}</b>: the send is a network call to a
 * third-party channel API, and holding a pooled DB connection across it is the anti-pattern {@code
 * HumanApprovalGateService}'s own javadoc documents being restructured to avoid. Each DB write
 * inside (the gate's recorder, the transcript append) manages its own transaction.
 */
@Service
public class OperatorInboxService {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final ChannelSessionRepository sessionRepository;
    private final ChannelMessageLogRepository messageLogRepository;
    private final HumanApprovalGateService gateService;
    private final AuditLogService auditLogService;
    // Each channel's action type and SendPayload (ADR-0011 rule a: the
    // channel packages own them). Teams resolves its per-conversation
    // Bot Framework serviceUrl inside its own ChannelTextSend.
    private final ChannelTextSends textSends;

    public OperatorInboxService(
            ChannelSessionRepository sessionRepository,
            ChannelMessageLogRepository messageLogRepository,
            HumanApprovalGateService gateService,
            AuditLogService auditLogService,
            ChannelTextSends textSends) {
        this.sessionRepository = sessionRepository;
        this.messageLogRepository = messageLogRepository;
        this.gateService = gateService;
        this.auditLogService = auditLogService;
        this.textSends = textSends;
    }

    /**
     * Every conversation an operator should look at - escalated (AWAITING_HUMAN) and already-held
     * (HUMAN) sessions, newest activity first. Held sessions stay listed on purpose: they ARE the
     * operator's live workload, not something to hide once claimed.
     */
    @Transactional(readOnly = true)
    public List<ChannelSession> listNeedingAttention() {
        return sessionRepository.findByHandoffStateInOrderByLastMessageAtDesc(
                EnumSet.of(HandoffState.AWAITING_HUMAN, HandoffState.HUMAN));
    }

    /**
     * The full conversations table behind the inbox page (2026-09-23 rebuild) - every channel
     * session, not only the handoff queue, with the controller's view params already resolved to
     * repository filter values. Delegates straight to {@code searchConversations}; the
     * null-means-unconstrained contract is documented on the finder - with one deliberate exception
     * it explains: {@code q} goes down as the empty string, never null, because a null String param
     * in a LIKE pattern binds as bytea on Postgres and the whole query 500s.
     */
    @Transactional(readOnly = true)
    public Page<ChannelSession> searchConversations(
            ChannelType channel, HandoffState state, Boolean paired, String q, Pageable pageable) {
        return sessionRepository.searchConversations(
                channel, state, paired, q == null ? "" : q, pageable);
    }

    /**
     * The inbox stats strip: one count per operator-relevant bucket. {@code agentHandled} counts
     * paired AGENT sessions only - an unpaired session's handoff state is technically AGENT but it
     * belongs to the pending-pairing bucket, and double-counting it in both would make the strip
     * disagree with the filtered lists it sits above.
     */
    @Transactional(readOnly = true)
    public InboxStats stats() {
        return new InboxStats(
                sessionRepository.countByHandoffState(HandoffState.AWAITING_HUMAN),
                sessionRepository.countByHandoffState(HandoffState.HUMAN),
                sessionRepository.countByPairedFalse(),
                sessionRepository.countByHandoffStateAndPairedTrue(HandoffState.AGENT),
                sessionRepository.count());
    }

    public record InboxStats(
            long waitingHuman, long held, long pendingPairing, long agentHandled, long total) {}

    /**
     * One conversation's transcript in display (oldest-first) order, capped at the newest 200 rows
     * (see the repository finder's javadoc for the bounded-read reasoning). Inbound rows are the
     * customer; OUTBOUND rows are operator replies. Agent replies are absent - an honest,
     * documented gap (their send sites are other workstreams' scope; V214's header).
     */
    @Transactional(readOnly = true)
    public List<ChannelMessageLog> transcript(UUID sessionId) {
        requireSession(sessionId); // 404 for an unknown id rather than a silent empty transcript
        List<ChannelMessageLog> newestFirst =
                messageLogRepository.findTop200ByChannelSessionIdOrderByCreatedAtDesc(sessionId);
        return newestFirst.reversed();
    }

    /**
     * Claims a conversation for {@code operatorSubject}: from here the agent and the channel
     * workflow trigger stay silent (enforced in {@code ChannelSessionService#resolveOrCreate})
     * until {@link #release}. Idempotent for the same operator; a conversation held by a DIFFERENT
     * operator is refused - two people typing into one customer chat is the exact confusion
     * takeover exists to prevent. (400 today via the centrally-mapped {@link
     * IllegalArgumentException}; 409 would be the more precise status, but the 409-capable
     * exception mappings live in {@code GlobalExceptionHandler}, another workstream's scope -
     * flagged as a follow-up rather than reached for.)
     */
    @Transactional
    public ChannelSession takeOver(UUID sessionId, String operatorSubject) {
        ChannelSession session = requireSession(sessionId);
        if (session.getChannel() == ChannelType.VOICE) {
            throw new IllegalArgumentException(
                    "A live VOICE call cannot be taken over by a state flag - see HandoffState's javadoc.");
        }
        if (!session.isPaired()) {
            throw new IllegalArgumentException(
                    "This conversation is still awaiting pairing approval - approve the pairing first.");
        }
        if (session.isHeldByHuman() && !operatorSubject.equals(session.getTakenOverBy())) {
            throw new IllegalArgumentException(
                    "This conversation is already held by another operator - ask them to release it first.");
        }
        boolean alreadyMine = session.isHeldByHuman();
        session.takeOver(operatorSubject);
        if (!alreadyMine) {
            auditLogService.record(
                    operatorSubject,
                    "channel_session.take_over",
                    "channel_session",
                    session.getId().toString(),
                    AuditOutcome.SUCCESS,
                    "Operator took over "
                            + session.getChannel()
                            + " conversation with ***"
                            + lastFour(session.getExternalId())
                            + " - agent replies suppressed until release.");
        }
        return session;
    }

    /**
     * Hands a conversation back to the agent (also clears a pending escalation - releasing means
     * "no human needed here after all", both for a held conversation and a merely-flagged one).
     * Idempotent: a session already in AGENT state returns unchanged, so a double-click or a stale
     * tab is a no-op rather than a 4xx.
     *
     * <p>Any tenant-admin may release, not only the holder: the holder may have gone home, and
     * "conversation permanently stuck in HUMAN because its owner is on holiday" is a worse failure
     * than a colleague handing it back - same reasoning as admin override in {@code
     * ResourceAccessService}.
     */
    @Transactional
    public ChannelSession release(UUID sessionId, String operatorSubject) {
        ChannelSession session = requireSession(sessionId);
        if (session.getHandoffState() == HandoffState.AGENT) {
            return session;
        }
        HandoffState before = session.getHandoffState();
        session.releaseToAgent();
        auditLogService.record(
                operatorSubject,
                "channel_session.release_to_agent",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.SUCCESS,
                "Operator released "
                        + session.getChannel()
                        + " conversation with ***"
                        + lastFour(session.getExternalId())
                        + " back to the agent (was "
                        + before
                        + ").");
        return session;
    }

    /**
     * Sends one operator-authored message to the conversation's external sender through the
     * channel's existing approval-gated send chain, then appends it to the transcript as an
     * OUTBOUND row. See the class javadoc for the evaluate-then-approve shape and why this method
     * holds no transaction.
     *
     * <p>Requires the conversation to be HELD (state HUMAN): replying into an agent-handled
     * conversation would interleave two authors with no warning to either - take it over first,
     * deliberately, so the agent is provably silent before a human starts typing. Any tenant-admin
     * may reply while it is held, not only the holder (multi-operator tenants hand conversations
     * around; the transcript row and audit record name the actual author, which is the
     * accountability that matters).
     *
     * <p>Failure honesty: if the channel send throws (a {@code ChannelDeliveryException} from the
     * adapter, or a retryable failure the gate has recorded for backoff), the exception propagates
     * to the operator as a mapped error and NO transcript row is written. One known imperfection,
     * documented rather than hidden: a retryable failure that later succeeds via {@code
     * RetryFailedActionsJob} will have delivered a message the transcript never shows - the durable
     * record of that delivery lives in the {@code approval_request} row. Writing the transcript row
     * optimistically before the send was considered and rejected: a transcript showing a reply the
     * customer never received misleads the NEXT operator, which is worse than the converse.
     */
    public ChannelSession reply(UUID sessionId, String body, String operatorSubject) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Reply body must not be blank.");
        }
        ChannelSession session = requireSession(sessionId);
        if (!session.isHeldByHuman()) {
            throw new IllegalArgumentException(
                    "Take this conversation over before replying - replies are only allowed while a human holds it, "
                            + "so the agent is provably silent when you type.");
        }

        String actionType = actionTypeFor(session.getChannel());
        String payloadJson = buildPayloadJson(session, body);
        String summary =
                "Operator reply on "
                        + session.getChannel()
                        + " to ***"
                        + lastFour(session.getExternalId());

        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        actionType, summary, payloadJson, "operator:" + operatorSubject);
        if (!evaluation.approved()) {
            // The double-gate resolution - see the class javadoc. The
            // operator is both the authenticated author and (as tenant-admin)
            // an authorized decider; the request row keeps both facts.
            gateService.approve(evaluation.pendingRequestId(), operatorSubject);
        }

        messageLogRepository.save(
                new ChannelMessageLog(
                        session.getChannel(),
                        session.getExternalId(),
                        session.getId(),
                        body,
                        ChannelMessageDirection.OUTBOUND,
                        operatorSubject));
        auditLogService.record(
                operatorSubject,
                "channel_session.operator_reply",
                "channel_session",
                session.getId().toString(),
                AuditOutcome.SUCCESS,
                "Operator replied on "
                        + session.getChannel()
                        + " to ***"
                        + lastFour(session.getExternalId())
                        + " ("
                        + body.length()
                        + " chars).");
        return session;
    }

    /**
     * The channel's registered {@code ApprovalActionExecutor} action type - the SAME constants the
     * webhook controllers use for agent replies, so the operator reply inherits the identical
     * executor, retry and dead-letter path. WHATSAPP maps to {@code channel.whatsapp.send}
     * regardless of how the tenant's WhatsApp is connected: {@code TwilioWhatsAppChannelAdapter}'s
     * executor routes through {@code WhatsAppDispatchService}, which picks Meta or Twilio per
     * tenant - exactly as the Meta inbound webhook already relies on for agent replies.
     */
    private String actionTypeFor(ChannelType channel) {
        if (channel == ChannelType.VOICE) {
            throw new IllegalArgumentException(
                    "VOICE has no queued send path - a voice reply is spoken live over the WebSocket bridge "
                            + "(see ChannelType's javadoc), and VOICE sessions can never be held anyway.");
        }
        return textSend(channel).actionType();
    }

    private ChannelTextSend textSend(ChannelType channel) {
        return textSends
                .forChannel(channel)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No send path is registered for channel " + channel + "."));
    }

    /**
     * Each channel's own {@code SendPayload} record, serialized exactly as that channel's send
     * controller/webhook does - the executor on the other side deserializes this shape and nothing
     * else. {@code session.getExternalId()} is always the reply-to address: every inbound path keys
     * its session on the stable routing identity (phone number, Slack channel id, Discord channel
     * id, Telegram chat id, X DM conversation id, email from-address, Meta sender id).
     */
    private String buildPayloadJson(ChannelSession session, String body) {
        String to = session.getExternalId();
        if (session.getChannel() == ChannelType.VOICE) {
            throw new IllegalArgumentException(
                    "VOICE sessions cannot be replied to from the inbox.");
        }
        String channelName = session.getChannel().name().toLowerCase(java.util.Locale.ROOT);
        Object payload;
        try {
            payload = textSend(session.getChannel()).payload(to, replySubjectFor(session), body);
        } catch (ChannelTextSend.RecipientNotAddressableException e) {
            throw new ChannelPayloadSerializationException(channelName, e.getMessage(), null);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ChannelPayloadSerializationException(
                    channelName,
                    "Failed to serialize operator reply payload for " + session.getChannel(),
                    e);
        }
    }

    /**
     * EMAIL is the one channel whose payload needs a subject. For email sessions {@code
     * lastMessagePreview} holds the most recent INBOUND subject line ({@code
     * EmailInboundPollingJob} passes the subject as the preview, and outbound rows never overwrite
     * it - only inbound {@code touch()} does), so "Re: <that>" threads the operator's reply where
     * the customer expects it.
     */
    private static String replySubjectFor(ChannelSession session) {
        String subject = session.getLastMessagePreview();
        if (subject == null || subject.isBlank()) {
            return "Re: your message";
        }
        return subject.regionMatches(true, 0, "re:", 0, 3) ? subject : "Re: " + subject;
    }

    private ChannelSession requireSession(UUID sessionId) {
        return sessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ChannelSessionNotFoundException(sessionId));
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }
}
