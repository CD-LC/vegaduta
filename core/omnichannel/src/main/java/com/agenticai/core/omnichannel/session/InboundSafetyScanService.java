package com.agenticai.core.omnichannel.session;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.ContentGuardrailCheckResult;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailType;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Inbound prompt-injection scan for channel messages (V242) - runs at the
 * {@code ChannelSessionService#resolveOrCreate} chokepoint, which all ten
 * inbound entry points already funnel through BEFORE dispatching to an
 * agent, so one seam covers WhatsApp/Telegram/Slack/Discord/Email/Teams/X/
 * Meta/web equally.
 *
 * <p><b>Why this exists even though {@code AgentChatService} enforces
 * inbound guardrails:</b> that enforcement only applies once a message
 * actually reaches an agent, and only for the guardrail types that
 * individual agent has enabled - a channel default agent with no jailbreak
 * guardrail configured gets nothing, and a flagged message leaves no
 * visible trace anywhere an operator would look. This scan is
 * channel-policy, not agent-config: every inbound text on a channel whose
 * routing row says FLAG or HOLD is checked with the platform's pattern
 * detector, independent of which agent eventually answers.
 *
 * <p>Modes (tenant-configured per channel, see {@link InboundSafetyMode}):
 * <ul>
 *   <li>{@code FLAG} (default) - the conversation is {@code
 *       escalateToHuman}'d so it surfaces in the operator inbox and the
 *       flag is audit-logged, but the agent still answers. Deliberately
 *       fail-open: pattern matching has false positives and dead air for a
 *       real customer is the worse failure (same reasoning as
 *       AWAITING_HUMAN itself).</li>
 *   <li>{@code HOLD} - the session is {@code takeOver("system:safety-scan")}'d
 *       so the existing held-by-human suppression in {@code resolveOrCreate}
 *       silences the agent for this and subsequent messages until an
 *       operator releases. Strict mode for public-facing channels.</li>
 *   <li>{@code OFF} - no scan.</li>
 * </ul>
 *
 * <p>VOICE sessions degrade HOLD to FLAG: a live phone call cannot be
 * handoff-held (see {@link HandoffState}'s javadoc) - flagging it for the
 * inbox is the honest maximum.
 */
@Service
public class InboundSafetyScanService {

    private static final Logger log = LoggerFactory.getLogger(InboundSafetyScanService.class);

    /** Who shows as holding a safety-suppressed conversation in the operator inbox. */
    public static final String SAFETY_HOLD_ACTOR = "system:safety-scan";

    /**
     * The scan deliberately covers only the injection/jailbreak detector:
     * pattern-based (no LLM call per inbound message), bounded by the ReDoS
     * fix in {@code JailbreakDetectorGuardrail}, and the specific attack this
     * seam exists for. Profanity/toxicity moderation of inbound text is a
     * different feature (policy packs), not this one.
     */
    private static final Set<ContentGuardrailType> SCANNED = Set.of(ContentGuardrailType.JAILBREAK);

    private final ChannelDefaultAgentRepository defaultAgentRepository;
    private final AuditLogService auditLogService;
    // Optional injection: a build/tests fixture without the guardrails bean
    // wired simply never flags anything - same graceful-absence pattern as
    // AgentMemoryService in ChannelSessionService.
    private final ObjectProvider<ContentGuardrailEngine> guardrailEngine;

    public InboundSafetyScanService(
        ChannelDefaultAgentRepository defaultAgentRepository,
        AuditLogService auditLogService,
        ObjectProvider<ContentGuardrailEngine> guardrailEngine
    ) {
        this.defaultAgentRepository = defaultAgentRepository;
        this.auditLogService = auditLogService;
        this.guardrailEngine = guardrailEngine;
    }

    /**
     * Scans one inbound message body and applies the channel's safety mode
     * to the session. Called from {@code resolveOrCreate} AFTER the message
     * is logged (the transcript must record what was flagged) and BEFORE the
     * held-by-human gate, so a HOLD flag suppresses the agent on this very
     * message. Never throws - a scanner failure must not drop real customer
     * messages.
     */
    public void apply(ChannelSession session, String messagePreview) {
        if (session == null || messagePreview == null || messagePreview.isBlank()) {
            return;
        }
        InboundSafetyMode mode = modeFor(session.getChannel());
        if (mode == InboundSafetyMode.OFF) {
            return;
        }
        ContentGuardrailEngine engine = guardrailEngine.getIfAvailable();
        if (engine == null) {
            return;
        }
        try {
            ContentGuardrailCheckResult result = engine.evaluate(
                messagePreview, SCANNED,
                ContentGuardrailEngine.GuardrailEvaluationContext.input(
                    session.getAgentId(), session.getId().toString(), null));
            if (result.isPassed()) {
                return;
            }
            String reason = result.getBlockReason() == null ? "injection pattern match" : result.getBlockReason();
            auditLogService.record(
                SAFETY_HOLD_ACTOR, "channel.inbound.safety_flag", "channel_session",
                session.getId().toString(), AuditOutcome.FAILURE,
                "Inbound " + session.getChannel() + " message flagged (mode " + mode + "): " + reason);
            if (mode == InboundSafetyMode.HOLD && session.getChannel() != ChannelType.VOICE) {
                // The held-by-human gate at the bottom of resolveOrCreate
                // throws ConversationHeldByHumanException right after this -
                // suppressing the agent for THIS message too, with the
                // transcript row still committed (noRollbackFor).
                session.takeOver(SAFETY_HOLD_ACTOR);
            } else {
                session.escalateToHuman("Inbound message flagged by safety scan: " + reason);
            }
        } catch (RuntimeException e) {
            log.warn("Inbound safety scan failed for session {} - failing open", session.getId(), e);
        }
    }

    private InboundSafetyMode modeFor(ChannelType channel) {
        return defaultAgentRepository.findByChannel(channel)
            .map(row -> InboundSafetyMode.valueOf(InboundSafetyMode.parse(row.getInboundSafetyMode())))
            .orElse(InboundSafetyMode.FLAG);
    }
}
