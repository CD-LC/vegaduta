package com.agenticai.core.omnichannel.handoff;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionNotFoundException;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flips a channel conversation to {@link HandoffState#AWAITING_HUMAN} and
 * tells the tenant's operators about it - the "escalation" half of the live
 * human handoff build-out (migration V214; the "takeover" half is
 * {@link OperatorInboxService}).
 *
 * <p><b>Deliberately NOT routed through {@code HumanApprovalGateService}</b>,
 * despite the audit recommendation that suggested it. That gate exists for
 * deferred ACTIONS: it records a request whose approval later runs a
 * registered {@code ApprovalActionExecutor}. An escalation has no deferred
 * action - there is nothing an executor could {@code execute()}, so routing
 * it there would mint PENDING approval rows that are structurally incapable
 * of doing what they appear to promise (approving one would hit
 * "no registered executor" and do nothing), with approve/reject semantics
 * that do not map onto escalated/claimed/released at all. The meaningful
 * state is conversation state on {@code channel_session}; the operator-facing
 * signal is a {@link NotificationService} broadcast (the bell every
 * tenant-admin already watches) plus an {@link AuditLogService} record -
 * both of which already exist for exactly this shape of "tell the humans"
 * problem. The one place the gate IS right is the operator's outbound
 * reply, which is a real channel send - see {@code OperatorInboxService#reply}.
 *
 * <p><b>Two entry points, two contracts.</b> {@link #escalate(UUID, String,
 * String)} is the operator/console path: it throws typed, centrally-mapped
 * exceptions because an HTTP caller wants a 404/400. {@link
 * #requestHumanForAgentSession} is the seam for the designed-but-not-yet-
 * written {@code request_human} agent tool ({@code core/agents/.../tools} is
 * another workstream's scope): it NEVER throws, mirroring the
 * {@code ChannelMediaSendPort} never-throws contract, because a tool-call
 * result that surfaces an exception kills the whole model turn (the
 * documented 2026-08-13 Gemini incident). Its {@link HandoffResult#detail}
 * is written to be safe for a model to paraphrase to an end customer -
 * never an internal id or operator name.
 *
 * <p>{@code TenantContext} is read implicitly (via the schema-routed
 * repositories), never set here: both entry points run on threads that
 * already carry it - the operator path via {@code TenantContextFilter}, the
 * future tool path via {@code guardAndLog}'s
 * {@code TenantContextPropagatingToolCallback} wrapper, which is precisely
 * why that wrapper is mandatory for the tool registration.
 */
@Service
public class HandoffEscalationService {

    private static final Logger log = LoggerFactory.getLogger(HandoffEscalationService.class);

    private final ChannelSessionRepository sessionRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public HandoffEscalationService(
        ChannelSessionRepository sessionRepository,
        NotificationService notificationService,
        AuditLogService auditLogService
    ) {
        this.sessionRepository = sessionRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    /**
     * Console/operator path - escalates one conversation by its channel
     * session id.
     *
     * @param sessionId   the {@code channel_session} primary key
     * @param reason      why a human is needed - shown verbatim in the
     *                    operator inbox (truncated to 500 chars by the entity)
     * @param requestedBy who asked (an operator subject, or a system label) -
     *                    recorded in the audit trail
     * @return the updated session
     * @throws ChannelSessionNotFoundException for an unknown id (mapped 404)
     * @throws IllegalArgumentException for a VOICE or unpaired session
     *         (mapped 400 - see {@link HandoffState}'s javadoc for why VOICE
     *         is excluded, and note an unpaired sender is still a stranger:
     *         escalating them would leapfrog the pairing trust gate)
     */
    @Transactional
    public ChannelSession escalate(UUID sessionId, String reason, String requestedBy) {
        ChannelSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ChannelSessionNotFoundException(sessionId));
        requireEscalatable(session);
        applyEscalation(session, reason, requestedBy);
        return session;
    }

    /**
     * The seam for the designed {@code request_human} agent tool - resolves
     * the agent-facing conversation-memory session id back to its channel
     * session and escalates it. Never throws (see the class javadoc); the
     * returned {@link HandoffResult#detail} is safe to read out loud to the
     * end customer.
     *
     * <p>When the {@code core/agents} workstream lands {@code
     * HumanHandoffPort} + {@code RequestHumanToolCallback}, the port
     * implementation in this module should be a one-line delegate to this
     * method - the shape here (and the {@link HandoffResult} record) already
     * matches the designed port contract exactly.
     */
    @Transactional
    public HandoffResult requestHumanForAgentSession(String agentSessionId, String reason) {
        try {
            ChannelSession session = sessionRepository.findFirstBySessionId(agentSessionId).orElse(null);
            if (session == null) {
                // The chat-UI / playground case: the agent's session id has no
                // channel session behind it, so there is no operator inbox to
                // escalate into. Honest and calm, not an error.
                return new HandoffResult(false,
                    "This conversation is not on a messaging channel, so it cannot be handed to the operator inbox.");
            }
            if (session.getChannel() == ChannelType.VOICE) {
                return new HandoffResult(false,
                    "A live voice call cannot be handed off this way.");
            }
            if (!session.isPaired()) {
                return new HandoffResult(false,
                    "This conversation has not been approved by an administrator yet.");
            }
            applyEscalation(session, reason, "agent-session:" + agentSessionId);
            return new HandoffResult(true,
                "A team member has been notified and will join this conversation shortly.");
        } catch (RuntimeException e) {
            // Never-throws contract - a failed escalation must degrade to an
            // honest "couldn't" answer for the model, not a dead HTTP turn.
            log.warn("request_human escalation failed for agent session {}: {}", agentSessionId, e.getMessage());
            return new HandoffResult(false,
                "The request could not be completed right now - please try again in a moment.");
        }
    }

    private void requireEscalatable(ChannelSession session) {
        if (session.getChannel() == ChannelType.VOICE) {
            throw new IllegalArgumentException(
                "A live VOICE call cannot be escalated to the operator inbox - voice handoff is a warm-transfer "
                    + "problem, not a state flag. See HandoffState's javadoc.");
        }
        if (!session.isPaired()) {
            throw new IllegalArgumentException(
                "This conversation is still awaiting pairing approval - approve the pairing first "
                    + "(escalating an unapproved stranger would leapfrog the pairing trust gate).");
        }
    }

    private void applyEscalation(ChannelSession session, String reason, String requestedBy) {
        boolean alreadyHeld = session.isHeldByHuman();
        session.escalateToHuman(reason);

        // Audit first (in-transaction, must not be lost), notification after
        // and best-effort - a bell-write failure must never fail the
        // escalation itself, same posture NotificationService's own javadoc
        // prescribes for call sites.
        auditLogService.record(
            requestedBy == null || requestedBy.isBlank() ? "system:handoff" : requestedBy,
            "channel_session.escalate_to_human",
            "channel_session",
            session.getId().toString(),
            AuditOutcome.SUCCESS,
            "Escalated " + session.getChannel() + " conversation with ***" + lastFour(session.getExternalId())
                + (alreadyHeld ? " (already held by an operator - no state change)" : " to AWAITING_HUMAN")
                + (reason == null || reason.isBlank() ? "" : " - reason: " + reason)
        );

        if (!alreadyHeld) {
            try {
                // recipientUserId null = broadcast to every tenant-admin.
                // Category SYSTEM rather than a dedicated HANDOFF value:
                // NotificationCategory lives in core/audit (another
                // workstream's scope), so adding the cleaner enum value is a
                // flagged follow-up, not something to reach across the
                // boundary for. Severity WARNING - louder than "your
                // workflow finished", quieter than a platform error.
                notificationService.create(
                    null,
                    NotificationCategory.SYSTEM,
                    "A conversation needs a human",
                    session.getChannel() + " conversation with ***" + lastFour(session.getExternalId())
                        + " was escalated" + (reason == null || reason.isBlank() ? "." : ": " + reason),
                    NotificationSeverity.WARNING,
                    "/admin/inbox",
                    null
                );
            } catch (RuntimeException e) {
                log.warn("Failed to create operator notification for escalated session {} - the escalation itself stands: {}",
                    session.getId(), e.getMessage());
            }
        }
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }

    /**
     * Mirror of the designed {@code HumanHandoffPort.HandoffResult} record
     * (see docs/AGENT-COORDINATION.md's handoff dependency entry): {@code
     * detail} goes back to the model verbatim, which will paraphrase it to
     * the end customer - so it must always read as something safe to say out
     * loud, never an internal id or operator name.
     */
    public record HandoffResult(boolean escalated, String detail) {
    }
}
