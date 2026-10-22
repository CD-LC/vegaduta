package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.chat.StreamEndExecutor;
import com.agenticai.core.guardrails.ContentGuardrailCheckResult;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.GuardrailFailMode;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationContext;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.logging.LogSafe;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Inbound moderation gate and per-agent content-guardrail enforcement for a chat turn, extracted
 * verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatGuardrailGate {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /**
     * Shown when a check could not run and {@link GuardrailFailMode} is closed. Nothing is
     * persisted for the refused turn, same as a violation.
     */
    static final String CHECK_UNAVAILABLE_NOTICE =
            "I can't process this right now: a safety check could not be completed. Please try"
                    + " again in a moment.";

    private final ObjectProvider<ModerationService> moderationService;
    private final ObjectProvider<ContentGuardrailEngine> contentGuardrailEngine;
    private final ObjectProvider<GuardrailViolationLogger> guardrailViolationLogger;
    private final AgentGuardrailService agentGuardrailService;

    ChatGuardrailGate(
            ObjectProvider<ModerationService> moderationService,
            ObjectProvider<ContentGuardrailEngine> contentGuardrailEngine,
            ObjectProvider<GuardrailViolationLogger> guardrailViolationLogger,
            AgentGuardrailService agentGuardrailService) {
        this.moderationService = moderationService;
        this.contentGuardrailEngine = contentGuardrailEngine;
        this.guardrailViolationLogger = guardrailViolationLogger;
        this.agentGuardrailService = agentGuardrailService;
    }

    /**
     * P6 content moderation (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §5) - the
     * inbound-user-message gate. Returns a short-circuit refusal {@link Flux} when this turn's
     * message must NOT reach the LLM ({@code BLOCK} or {@code QUEUE_FOR_REVIEW}), or {@code null}
     * to let the turn proceed unchanged ({@code ALLOW}).
     *
     * <p><b>Default-off.</b> {@link ModerationService#moderate} returns {@code ALLOW} whenever the
     * master switch {@code moderation.enabled} is false (its default) or no packs are enabled, so
     * this method returns {@code null} and the turn is completely unaffected until an admin opts in
     * - a genuine no-op, the pattern layer is only consulted once a pack is on. It stays
     * default-off deliberately: its model-backed packs call an external moderation model, which
     * costs money per turn.
     *
     * <p><b>Prompt-injection screening does NOT depend on this gate.</b> Since 2026-09-28 every
     * agent-chat turn is screened by {@code LlmGatewayContentPolicyAdvisor} (core/chat gateway), a
     * {@code GatewayGovernanceAdvisor} attached to both BYOK clients ({@code
     * LlmGatewayService#resolveClient}) and platform-hosted-pool clients ({@code
     * FreeTierChatClientService}). It is ON by default in FLAG mode (audit-log only; tenants can
     * switch to BLOCK or off via {@code /api/tenant/llm-gateway/content-policy}), pattern-based
     * with no model call, and covers English plus Hindi/Hinglish, Spanish, French, German,
     * Portuguese, Chinese and Japanese phrase families. So "moderation.enabled = false" means no
     * paid content moderation - not "no injection screening".
     *
     * <p><b>Failure handling.</b> Per {@link GuardrailFailMode}: a {@link RuntimeException} from
     * the moderation call refuses the turn when closed (the default) and is logged and treated as
     * {@code ALLOW} when open. The {@code ModerationService} bean being absent is "not configured"
     * and always allows (the {@code null} guard).
     *
     * <p><b>Audit / session.</b> The moderation layer already writes every {@code BLOCK}/{@code
     * QUEUE} decision to the audit log, so this gate records nothing further and - crucially -
     * persists neither the blocked user message nor any assistant answer to session memory (it
     * returns before {@link #chat}'s {@code appendTurn} call), beyond the single safe refusal it
     * hands back to the caller.
     *
     * @param tenantId the current tenant id, or {@code null} when there is no request tenant
     *     context; drives per-tenant/plan pack resolution
     */
    Flux<String> moderateInbound(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String tenantId,
            TenantPlan tenantPlan) {
        ModerationService moderation = moderationService.getIfAvailable();
        if (moderation == null) {
            return null;
        }
        ModerationOutcome outcome;
        try {
            // Plan token (FREE/PREMIUM/...) for per-plan pack enablement; null
            // when the tenant is unknown, in which case only tenant-level and
            // global config apply (see ModerationPolicyService). subjectKey is
            // the actor stamped on audit/review rows; direction labels where in
            // the flow this content sits, for audit context only. tenantPlan is
            // resolved ONCE per turn by the caller (see chat()'s currentTenantPlan) -
            // reused here instead of re-querying tenantLimits.plan().
            String plan = tenantPlan != null ? tenantPlan.name() : null;
            ModerationContext context =
                    new ModerationContext(
                            tenantId != null ? tenantId : "unknown",
                            plan,
                            agentId,
                            sessionId,
                            subjectKey,
                            "user_input");
            outcome = moderation.moderate(userMessage, context);
        } catch (RuntimeException e) {
            if (GuardrailFailMode.isClosed()) {
                log.warn(
                        "Content moderation failed for agent {} session {} - failing closed"
                                + " (refusing the turn)",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId),
                        e);
                return Flux.just(CHECK_UNAVAILABLE_NOTICE);
            }
            log.warn(
                    "Content moderation failed for agent {} session {} - failing open (allowing the"
                            + " turn)",
                    LogSafe.clean(agentId),
                    LogSafe.clean(sessionId),
                    e);
            return null;
        }
        return switch (outcome.decision()) {
            case ALLOW -> null;
            case BLOCK -> {
                log.info(
                        "Content moderation BLOCKED an inbound message for agent {} session {}"
                                + " (pack={}, layer={})",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId),
                        LogSafe.clean(outcome.pack()),
                        LogSafe.clean(outcome.decidingLayer()));
                yield Flux.just(
                        "I can't help with that request - it was flagged by our content policy and"
                                + " can't be processed.");
            }
            case QUEUE_FOR_REVIEW -> {
                log.info(
                        "Content moderation QUEUED an inbound message for review for agent {}"
                                + " session {} (reviewItem={})",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId),
                        LogSafe.clean(outcome.reviewItemId()));
                yield Flux.just(
                        "This request needs a manual review before it can be answered. It has been"
                                + " sent to our team, and you'll hear back once it's been reviewed.");
            }
        };
    }

    // Content-guardrail path split (write-only-config fix, 2026-07-29). Not
    // every configured guardrail makes sense on both directions of a turn:
    // JAILBREAK is input-only by definition (it detects a USER trying to hijack
    // the agent; the agent's own answer legitimately discussing injection
    // phrases must not trip it), and PII is output-only by design (its enum doc:
    // "in responses. Prevents data leakage from agent outputs" - blocking a user
    // for typing their OWN email/phone into a support chat would break every
    // normal conversation). PROFANITY/POLICY_VIOLATION/TOXICITY are direction-
    // agnostic content checks and run on both. CUSTOM is now in BOTH sets: it
    // used to be in neither, correctly, because no ContentGuardrail bean
    // implemented it and the engine would only have logged a "not available"
    // warn per turn - CustomRuleContentGuardrail is that missing bean, and it
    // runs the tenant's own guardrail_rule patterns, which are as
    // direction-agnostic as PROFANITY is. A tenant BLOCK rule fires on both
    // paths; a REDACT rule can only rewrite on the output path (see that
    // class's javadoc and enforceInboundGuardrails below).
    private static final Set<ContentGuardrailType> INPUT_PATH_GUARDRAILS =
            EnumSet.of(
                    ContentGuardrailType.PROFANITY,
                    ContentGuardrailType.JAILBREAK,
                    ContentGuardrailType.POLICY_VIOLATION,
                    ContentGuardrailType.TOXICITY,
                    ContentGuardrailType.CUSTOM);
    private static final Set<ContentGuardrailType> OUTPUT_PATH_GUARDRAILS =
            EnumSet.of(
                    ContentGuardrailType.PROFANITY,
                    ContentGuardrailType.PII,
                    ContentGuardrailType.POLICY_VIOLATION,
                    ContentGuardrailType.TOXICITY,
                    ContentGuardrailType.CUSTOM);

    /**
     * The agent's configured guardrails, narrowed to what should actually run: plan-gated via
     * {@link AgentGuardrailService#resolveEnforceableGuardrails} (a downgraded tenant's
     * stored-but-locked toggles are dropped, not evaluated) and filtered to the given direction set
     * above.
     */
    private Set<ContentGuardrailType> enforceableGuardrails(
            Agent agent,
            String tenantId,
            TenantPlan knownPlan,
            Set<ContentGuardrailType> pathFilter) {
        Set<String> configured = agent.getEnabledGuardrails();
        if (configured == null || configured.isEmpty()) {
            return EnumSet.noneOf(ContentGuardrailType.class);
        }
        Set<ContentGuardrailType> types =
                agentGuardrailService.resolveEnforceableGuardrails(configured, tenantId, knownPlan);
        types.retainAll(pathFilter);
        return types;
    }

    /**
     * Per-agent content-guardrail enforcement, INPUT path (write-only-config fix, 2026-07-29) - the
     * seam that finally invokes {@link ContentGuardrailEngine} on the real request path. Same
     * contract shape as {@link #moderateInbound} directly above it in {@link #chat}: returns a
     * short-circuit refusal {@link Flux} when the user message violates one of this agent's enabled
     * guardrails, or {@code null} to let the turn proceed. Like the moderation gate, a blocked turn
     * persists NOTHING to session memory (it returns before chat()'s {@code appendTurn}).
     *
     * <p><b>Failure handling follows {@link GuardrailFailMode}.</b> An unexpected {@link
     * RuntimeException} from the engine/plan lookup refuses the turn when closed (the default) and
     * is logged with the turn proceeding when open - same posture as {@link #moderateInbound} and
     * {@code SemanticGuardrailService}'s tool-output path. An actual violation is always a hard
     * block.
     *
     * <p><b>Semantic-guardrail limitation, stated honestly:</b> the {@code ChatClient} argument is
     * deliberately {@code null} here, because no client exists yet at this point in the turn - and
     * it must not: this gate sits BEFORE the response-cache lookup and the hosted-pool budget
     * chain, so building a client just for the check would spend budget on messages that may be
     * about to be refused. Consequence: TOXICITY (the one wired guardrail whose {@code
     * requiresChatClient()} is true) passes trivially on the input path and is actually enforced on
     * the OUTPUT path, where the turn's real client is in scope. Inbound toxicity screening would
     * need a dedicated cheap classifier client - a deliberate follow-up, not silently faked here.
     *
     * <p><b>No redaction on this path, deliberately:</b> a tenant guardrail rule whose action is
     * REDACT cannot rewrite the user's message here. This gate runs before the response-cache
     * lookup, and the message text IS the cache key; rewriting it would also mean the transcript no
     * longer shows what the person actually typed. {@code CustomRuleContentGuardrail} therefore
     * does not even evaluate REDACT rules on the INPUT path (it is told the direction - see {@code
     * ContentGuardrail#evaluate(String, ChatClient, GuardrailEvaluationPath)}), so nothing is
     * computed and silently dropped. BLOCK rules are fully enforced here.
     *
     * <p><b>Audit:</b> every block is recorded through {@link
     * GuardrailViolationLogger#logInputViolation} (action {@code guardrail.input.blocked}, outcome
     * REJECTED) - the pre-existing audit path built for exactly this event, itself best-effort
     * wrapped so an audit-write failure downgrades the block to a log line rather than breaking the
     * turn.
     */
    Flux<String> enforceInboundGuardrails(
            Agent agent, UUID agentId, String sessionId, String userMessage, String tenantId) {
        return enforceInboundGuardrails(agent, agentId, sessionId, userMessage, tenantId, null);
    }

    /**
     * As above, with the plan the caller already resolved for this turn ({@code null} reads it), so
     * the plan gate costs no lookup of its own.
     */
    Flux<String> enforceInboundGuardrails(
            Agent agent,
            UUID agentId,
            String sessionId,
            String userMessage,
            String tenantId,
            TenantPlan knownPlan) {
        ContentGuardrailEngine engine = contentGuardrailEngine.getIfAvailable();
        if (engine == null) {
            return null;
        }
        ContentGuardrailCheckResult result;
        try {
            Set<ContentGuardrailType> enabled =
                    enforceableGuardrails(agent, tenantId, knownPlan, INPUT_PATH_GUARDRAILS);
            if (enabled.isEmpty()) {
                return null;
            }
            result =
                    engine.evaluate(
                            userMessage,
                            enabled,
                            ContentGuardrailEngine.GuardrailEvaluationContext.input(
                                    agentId, sessionId, null),
                            null);
        } catch (RuntimeException e) {
            if (GuardrailFailMode.isClosed()) {
                log.warn(
                        "Content-guardrail input check failed for agent {} session {} - failing"
                                + " closed (refusing the turn)",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId),
                        e);
                return Flux.just(CHECK_UNAVAILABLE_NOTICE);
            }
            log.warn(
                    "Content-guardrail input check failed for agent {} session {} - failing open"
                            + " (allowing the turn)",
                    LogSafe.clean(agentId),
                    LogSafe.clean(sessionId),
                    e);
            return null;
        }
        if (result.isPassed()) {
            return null;
        }
        auditGuardrailViolation(agentId, sessionId, result, true);
        String reason = result.getBlockReason();
        return Flux.just(
                reason != null && !reason.isBlank()
                        ? reason
                        : "This message was blocked by this agent's content guardrails.");
    }

    /**
     * Per-agent content-guardrail enforcement, OUTPUT path for the buffered (non-streaming)
     * reasoning depths - CAREFUL/RIGOROUS ({@link #verifyThenRespond}) and PLANNED ({@link
     * #planThenRespond}), where the complete answer exists before anything is shown to the user, so
     * a violating answer can genuinely be withheld. Returns the answer to use: the original on pass
     * or any infrastructure failure (fail-open), or a short replacement notice on a violation
     * (fail-closed - the violating text never reaches the caller, and since callers persist THIS
     * return value, never reaches session memory either). The turn's real {@code chatClient} is
     * passed through, so the semantic TOXICITY guardrail is fully live here. Every block is audited
     * via {@link GuardrailViolationLogger#logOutputViolation}.
     */
    String enforceOutboundGuardrails(
            Agent agent,
            UUID agentId,
            String sessionId,
            String tenantId,
            ChatClient chatClient,
            String answer) {
        ContentGuardrailEngine engine = contentGuardrailEngine.getIfAvailable();
        if (engine == null || answer == null || answer.isEmpty()) {
            return answer;
        }
        ContentGuardrailCheckResult result;
        try {
            Set<ContentGuardrailType> enabled =
                    enforceableGuardrails(agent, tenantId, null, OUTPUT_PATH_GUARDRAILS);
            if (enabled.isEmpty()) {
                return answer;
            }
            result =
                    engine.evaluate(
                            answer,
                            enabled,
                            ContentGuardrailEngine.GuardrailEvaluationContext.output(
                                    agentId, sessionId, null),
                            chatClient);
        } catch (RuntimeException e) {
            if (GuardrailFailMode.isClosed()) {
                log.warn(
                        "Content-guardrail output check failed for agent {} session {} - failing"
                                + " closed (withholding the answer)",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId),
                        e);
                return CHECK_UNAVAILABLE_NOTICE;
            }
            log.warn(
                    "Content-guardrail output check failed for agent {} session {} - failing open"
                            + " (returning the answer)",
                    LogSafe.clean(agentId),
                    LogSafe.clean(sessionId),
                    e);
            return answer;
        }
        if (result.isPassed()) {
            // A guardrail may have rewritten rather than blocked (a tenant
            // REDACT rule - see CustomRuleContentGuardrail). The redacted text
            // is what the caller persists to session memory and returns to the
            // user, so it has to come from the result, not from our own copy.
            String sanitized = result.getSanitizedContent();
            if (sanitized != null && !sanitized.equals(answer)) {
                log.info(
                        "Content guardrails redacted part of the answer for agent {} session {}",
                        LogSafe.clean(agentId),
                        LogSafe.clean(sessionId));
                return sanitized;
            }
            return answer;
        }
        auditGuardrailViolation(agentId, sessionId, result, false);
        ContentGuardrailType blockedBy = result.getBlockedByGuardrail();
        return "The agent's response was withheld: it was blocked by this agent's "
                + (blockedBy != null ? blockedBy.getDisplayName() : "content guardrail")
                + " check. Please try rephrasing your request.";
    }

    /**
     * OUTPUT-path guardrail handling for the token-streaming FAST depth - detection and audit ONLY,
     * never suppression. <b>Documented tradeoff, chosen deliberately:</b> by the time the complete
     * answer exists on the FAST path, every token has already been delivered to the client (that is
     * the entire point of FAST - see {@link #streamFast}'s javadoc), so the only alternatives were
     * (a) buffer the whole stream and destroy the streaming UX for every agent whether or not
     * guardrails are even enabled, or (b) this: evaluate the completed answer, write the same
     * {@code guardrail.output.blocked} audit row the buffered paths write, and log - giving tenants
     * full violation visibility on FAST while keeping live streaming intact. A tenant who wants
     * outbound blocking to genuinely withhold text can switch the agent to
     * CAREFUL/RIGOROUS/PLANNED, which is already the documented "verified before shown" trade in
     * this class. The already-streamed answer IS still persisted to session memory by the caller
     * (withholding it would desync the model from what the user already read). {@code ChatClient}
     * is deliberately {@code null} here as well: this runs inside {@code doOnComplete} on the
     * stream-completion thread (often a reactor/HTTP-client worker), and the semantic TOXICITY
     * check is a blocking LLM call that must not stall that thread - so FAST outbound coverage is
     * the pattern-based guardrails (PROFANITY/PII/POLICY_VIOLATION); semantic toxicity enforcement
     * is a non-streaming-depth feature. {@code capturedTenant} is re-applied around the check (same
     * capture-then-reapply the semantic-cache write and RL-episode close in {@link #chat} already
     * do) so the plan lookup and audit write run under the right tenant despite the foreign thread.
     * Entirely best-effort: any failure logs and the completion callback continues.
     */
    void auditStreamedOutboundViolation(
            Agent agent,
            UUID agentId,
            String sessionId,
            String tenantId,
            String fullAnswer,
            Optional<TenantContext> capturedTenant) {
        // Inline unless app.chat.stream-end.async is on; the tenant travels as data, so either
        // thread works.
        streamEnd.run(
                () ->
                        auditStreamedOutboundViolationNow(
                                agent, agentId, sessionId, tenantId, fullAnswer, capturedTenant));
    }

    /** Set by {@link StreamEndWiring}; inline until then (hand-built fixtures, flag off). */
    private volatile StreamEndExecutor streamEnd = StreamEndExecutor.INLINE;

    void setStreamEnd(StreamEndExecutor streamEnd) {
        this.streamEnd = streamEnd == null ? StreamEndExecutor.INLINE : streamEnd;
    }

    private void auditStreamedOutboundViolationNow(
            Agent agent,
            UUID agentId,
            String sessionId,
            String tenantId,
            String fullAnswer,
            Optional<TenantContext> capturedTenant) {
        ContentGuardrailEngine engine = contentGuardrailEngine.getIfAvailable();
        if (engine == null || fullAnswer == null || fullAnswer.isEmpty()) {
            return;
        }
        Optional<TenantContext> previous = TenantContext.current();
        capturedTenant.ifPresent(TenantContext::set);
        try {
            Set<ContentGuardrailType> enabled =
                    enforceableGuardrails(agent, tenantId, null, OUTPUT_PATH_GUARDRAILS);
            if (enabled.isEmpty()) {
                return;
            }
            ContentGuardrailCheckResult result =
                    engine.evaluate(
                            fullAnswer,
                            enabled,
                            // ...AlreadyDelivered, not output(): nothing returned here can
                            // change what the reader already saw, so a guardrail that would
                            // rewrite the text is told not to waste the pass. Detection and
                            // the audit row are the entire point of this call.
                            ContentGuardrailEngine.GuardrailEvaluationContext
                                    .outputAlreadyDelivered(agentId, sessionId, null),
                            null);
            if (!result.isPassed()) {
                log.warn(
                        "Streamed answer for agent {} session {} violated the {} guardrail AFTER"
                                + " delivery (FAST depth streams tokens live; see"
                                + " auditStreamedOutboundViolation's javadoc for the tradeoff)",
                        agentId,
                        sessionId,
                        result.getBlockedByGuardrail());
                auditGuardrailViolation(agentId, sessionId, result, false);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Post-stream content-guardrail check failed for agent {} session {} - skipping"
                            + " (best-effort)",
                    agentId,
                    sessionId,
                    e);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * Best-effort audit of a content-guardrail block through the pre-existing {@link
     * GuardrailViolationLogger} path ({@code guardrail.input.blocked} / {@code
     * guardrail.output.blocked}, outcome REJECTED, via {@code AuditLogService}) - wrapped so an
     * audit-subsystem failure can never turn a correctly-blocked turn into a 500.
     */
    private void auditGuardrailViolation(
            UUID agentId, String sessionId, ContentGuardrailCheckResult result, boolean inputPath) {
        GuardrailViolationLogger logger = guardrailViolationLogger.getIfAvailable();
        if (logger == null) {
            return;
        }
        try {
            if (inputPath) {
                logger.logInputViolation(agentId, sessionId, result);
            } else {
                logger.logOutputViolation(agentId, sessionId, result);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to audit content-guardrail violation for agent {} session {}",
                    LogSafe.clean(agentId),
                    LogSafe.clean(sessionId),
                    e);
        }
    }
}
