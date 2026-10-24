package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.requireOwnAttachmentKey;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The pre-model half of one chat turn, moved verbatim out of {@link AgentChatService#chat}: the
 * tenant kill-switch, experiment routing, the C1 version pin, the attachment-key check, inbound
 * moderation and guardrails, the BEFORE_MODEL hook and the two response-cache lookups. Everything
 * here runs synchronously on the request thread, before any client is built or budget spent, and
 * every short-circuit the original method had is returned as {@link Outcome#earlyReturn()}.
 *
 * <p>Plain package-private object built once by the service's constructor; it reads the service's
 * fields at call time (several are setter-injected after construction), so it holds the service
 * rather than copies of its fields.
 */
final class ChatTurnPreflight {

    /** Either an early reply (refusal, cache hit) or the resolved turn to continue with. */
    record Outcome(Flux<String> earlyReturn, Turn turn) {
        static Outcome earlyReturn(Flux<String> flux) {
            return new Outcome(flux, null);
        }

        static Outcome proceed(Turn turn) {
            return new Outcome(null, turn);
        }
    }

    /**
     * What the rest of the turn needs from this stage. {@code agentId} is the experiment-routed id
     * every later read uses; {@code memorySessionId} is the SEC-004 scoped id session memory and
     * the cancel flag use (events, logs and metrics keep the raw {@code sessionId}). The tenant is
     * read from {@code context} (ADR-0022 stage 2/3); the turn no longer carries it as a string.
     */
    record Turn(
            TenantPlan currentTenantPlan,
            UUID agentId,
            String memorySessionId,
            Agent agent,
            String safeAttachmentKey,
            com.agenticai.core.agents.hooks.HookRun hookRun,
            boolean cacheEligible,
            boolean semanticEligible,
            SemanticResponseCacheService semanticCache,
            Map<String, String> activeFlags,
            TurnContext context,
            String cacheConversationKey) {}

    private final AgentChatService svc;

    /** DPDP-oriented notice-and-consent step; set by {@link ConsentGateWiring}, null = none. */
    volatile com.agenticai.core.agents.consent.ConversationConsentGate consentGate;

    ChatTurnPreflight(AgentChatService svc) {
        this.svc = svc;
    }

    Outcome run(
            TurnContext context,
            long ttftStartNanos,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            AgentStepEventEmitter stepEvents) {
        // Read at call time, not construction: several are setter-injected (volatile) on the
        // service after this object exists.
        TenantStatusGuard tenantStatusGuard = svc.tenantStatusGuard;
        ChatHotpath hotpath = svc.hotpath;
        TenantLimits tenantLimits = svc.tenantLimits;
        AgentService agentService = svc.agentService;
        ObjectProvider<com.agenticai.core.agents.version.AgentVersionResolutionService>
                versionResolver = svc.versionResolver;
        ChatGuardrailGate guardrails = svc.guardrails;
        com.agenticai.core.agents.hooks.AgentHookRuntime agentHooks = svc.agentHooks;
        LlmResponseCacheService responseCacheService = svc.responseCacheService;
        SessionMemoryService sessionMemoryService = svc.sessionMemoryService;
        AgentMessageEvents events = svc.events;
        ChatTtftRecorder ttft = svc.ttft;
        ObjectProvider<SemanticResponseCacheService> semanticResponseCacheService =
                svc.semanticResponseCacheService;
        // #815 Governance follow-up (Fail-safe: platform + per-tenant kill
        // switch) - re-runs the SAME check TenantContextFilter already
        // enforces for JWT-authenticated requests (web console/direct API),
        // because this method is also the one universal chokepoint every
        // omnichannel webhook (WhatsApp, Telegram, email, Slack, Discord,
        // X, etc.) calls - and those requests never pass through that
        // filter at all (no JWT, no Spring Security auth). Without this,
        // stopping a tenant or the whole platform via
        // TenantEmergencyControlService would silently do nothing for every
        // channel except the web console. Thrown synchronously, before the
        // Flux even begins, same as agentService.getEntity's existing
        // not-found check below.
        // ADR-0022 stage 1: tenant, agent and session come from the explicit TurnContext, which
        // AgentChatService#chat built from this same thread's TenantContext a moment earlier.
        UUID requestedAgentId = context.requestedAgentId();
        String sessionId = context.sessionId();
        // What the screening below judges: on the continuation after an approval, the person's
        // original goal, not the platform-written message wrapped around the approved tool's output
        // (the output was already checked by the tool guard when the approved call ran).
        final String screenedMessage = context.decisionMessage(userMessage);
        final boolean continuation = context.continuation() != null;
        String currentTenantId = context.tenantId();
        if (currentTenantId != null && !tenantStatusGuard.isUsable(currentTenantId)) {
            throw new TenantUnavailableException(
                    "This tenant is not usable right now (emergency-stopped, or the platform-wide"
                            + " kill switch is on).");
        }

        // Dedupe fix (2026-08-06 audit): this is the SAME DB row every one of
        // moderateInbound/usesPlatformLlm/the hostedPool branch/the vision-usage
        // call/buildChatClientFor used to re-query independently (3-4 redundant
        // tenantLimits.plan() round trips per turn). Resolved ONCE here and
        // threaded through every one of those call sites below instead - no
        // logic change, just removing the repeat reads within this one request.
        TenantPlan currentTenantPlan =
                currentTenantId != null ? hotpath.plan(tenantLimits, currentTenantId) : null;

        // Cross-user resource sharing retrofit (Round 2) - deliberately NO
        // requireAccess check here. This method is the UNIVERSAL chat entry
        // point: besides AgentController#chat (a real, authenticated tenant
        // member - that call site DOES enforce RUNNER, see its own comment),
        // it is also invoked directly by every omnichannel webhook adapter
        // (WhatsApp/Telegram/Discord/Slack/Meta/etc.), each of which
        // authenticates via its own synthetic per-channel TenantContext
        // subject (e.g. "channel-requested-by:<phoneNumberId>" - see
        // MetaWhatsAppWebhookController/TelegramWebhookController) that will
        // never match a real owner/grantee subject. Gating requireAccess in
        // THIS shared method would 404 every single inbound customer message
        // to any newly-created private agent bound to a channel - the exact
        // same "don't route an autonomous/external trigger through a check
        // that assumes a human caller" reasoning this task also applies to
        // WorkflowService#run's webhook/schedule-triggered path. A channel
        // message isn't a tenant member trying to access a private resource;
        // it's an external customer using the channel the tenant itself
        // already configured this agent onto - the channel binding is its
        // own authorization, not a resource-sharing question.
        //
        // A/B experiments on agent versions (migration V265) - resolved
        // BEFORE the agent config is loaded, into a NEW final local variable
        // (never a reassignment of the `requestedAgentId` parameter - this
        // method is ~3000 lines and captures its agent id in numerous
        // lambdas further down, which requires effectively-final; renaming
        // the parameter and shadowing it with this final local is what keeps
        // every one of those captures compiling unchanged while still
        // routing them all to whichever agent this turn was actually
        // bucketed onto: tool resolution, memory, LlmCallLog's
        // AGENT_ID_PARAM, step events - every read of `agentId` below this
        // line sees the routed id for free). `requestedAgentId`
        // is kept ONLY for the attachment-key check just below: an
        // attachmentKey was minted server-side against the CALLER-VISIBLE
        // agent id at upload time (ChatAttachmentService#upload), never
        // against a hidden treatment clone, so re-deriving it against the
        // routed id would reject a legitimate attachment on a turn that got
        // bucketed into TREATMENT.
        final UUID agentId = svc.agentRouting.resolveExperimentAgentId(requestedAgentId, sessionId);
        final String memorySessionId =
                SessionMemoryService.scopedSessionId(
                        currentTenantId,
                        requestedAgentId,
                        subjectKey,
                        sessionId); // SEC-004: session memory and the cancel flag use only this id
        // (SessionMemoryService#scopedSessionId); events, logs, metrics keep the raw one.
        Agent liveAgentRow = agentService.getEntity(agentId);
        // C1 versioning: resolve the version this turn pins and - under the
        // versioning_explicit_publish tenant flag - execute the PRODUCTION
        // bundle rather than the draft row. Auto mode is a strict no-op:
        // PRODUCTION tracks the live row, so effectiveAgent returns it
        // untouched and the pin merely stamps observability columns.
        // (final: the dispatchTurn/sync-heal lambdas far below capture it.)
        // The tenant's flags are evaluated ONCE here (version pin, effective-agent overlay and,
        // later,
        // the experiment-variants string all used to evaluate them separately: three flag reads a
        // turn).
        // Only when versioning is wired; without it the prompt assembler evaluates them itself.
        final boolean versioned =
                versionResolver != null && versionResolver.getIfAvailable() != null;
        final Map<String, String> activeFlags =
                versioned ? svc.featureFlagService.evaluateActiveFlags(currentTenantId) : null;
        final Agent agent =
                versioned
                        ? svc.agentRouting.pinAndApplyEffectiveAgent(agentId, liveAgentRow, activeFlags)
                        : liveAgentRow;

        // Cross-tenant object-read fix (security audit 2026-07-29 §2 item 3).
        // attachmentKey arrives verbatim from the request body (ChatRequest)
        // and used to be handed straight to FileStorageService.get() with no
        // validation anywhere - so "attachmentKey":"tenant-exports/<other
        // tenant's uuid>/<millis>.json" missed under this caller's tenant
        // prefix and then RESOLVED against the legacy un-prefixed fallback in
        // S3FileStorageService, serving another tenant's object into this
        // tenant's prompt. Validated (and re-derived server-side) here, at the
        // top of the one entry point that ever receives a caller-supplied key
        // - before any cache lookup, budget spend or LLM client resolution, so
        // a probe costs the attacker a 400 and nothing else. Re-checked again
        // at the storage boundary in loadAttachment; see
        // requireOwnAttachmentKey for the exact rule. Uses requestedAgentId,
        // not the (possibly experiment-routed) agentId - see the comment above.
        String safeAttachmentKey = requireOwnAttachmentKey(requestedAgentId, attachmentKey);

        // DPDP-oriented notice-and-consent step (docs/compliance/dpdp-obligations.md). First, so
        // that for an agent with the step switched on, an inbound channel message from a contact
        // who has not consented reaches no cache, memory recall, tool or model. A non-null result
        // replaces the whole turn. Not fail-open: see ConversationConsentGate#intercept.
        com.agenticai.core.agents.consent.ConversationConsentGate consentGate = this.consentGate;
        if (consentGate != null) {
            Flux<String> consentReply =
                    consentGate.intercept(requestedAgentId, sessionId, subjectKey, screenedMessage);
            if (consentReply != null) {
                return Outcome.earlyReturn(consentReply);
            }
        }

        // P6 content moderation (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §5) -
        // the inbound-user-message gate, placed BEFORE any cache lookup or LLM
        // call so a disallowed message never reaches a model and never returns a
        // cached answer. Off by default (moderation.enabled=FALSE) so this is a
        // no-op for every existing turn; BLOCK / QUEUE_FOR_REVIEW short-circuit
        // with a safe message and never build a ChatClient. Fail-open on any
        // subsystem error (see moderateInbound).
        // First progress signal of a step-event turn. Everything above is a few DB reads; from
        // here on moderation, guardrails, hooks and the cache lookups can each call out, so the
        // client is told the turn is in its safety checks rather than left on "connecting".
        stepEvents.phase("guardrails");
        Flux<String> moderationRefusal =
                guardrails.moderateInbound(
                        agentId,
                        sessionId,
                        subjectKey,
                        screenedMessage,
                        currentTenantId,
                        currentTenantPlan);
        if (moderationRefusal != null) {
            return Outcome.earlyReturn(moderationRefusal);
        }

        // Per-agent content-guardrail enforcement, INPUT path (write-only-config
        // fix, 2026-07-29) - sits directly behind the platform-admin moderation
        // gate above, same placement discipline: BEFORE any cache lookup or LLM
        // client resolution, so a blocked message never returns a cached answer,
        // never consumes hosted-pool budget, and never reaches a model. Unlike
        // moderation (platform-admin, default-off), this enforces the TENANT'S
        // own per-agent toggles (Agent.enabledGuardrails) - a no-op for every
        // agent with none enabled. Fail-open on any engine/subsystem error;
        // fail-closed (refusal, turn never starts) on an actual violation.
        Flux<String> guardrailRefusal =
                guardrails.enforceInboundGuardrails(
                        agent, agentId, sessionId, screenedMessage, currentTenantId, currentTenantPlan);
        if (guardrailRefusal != null) {
            // Step-event vocabulary (2026-09-09): a step-event turn also gets a
            // typed `guardrail` event on the side channel, so a trace UI / the
            // AG-UI mapper can tell "the agent refused" apart from "the agent
            // answered with a sentence that happens to say it refused". The
            // refusal text IS the block reason (see enforceInboundGuardrails),
            // so it is reused verbatim rather than re-deriving it here. Turns
            // without step events return the identical Flux as before.
            if (stepEvents != null && stepEvents.isEnabled()) {
                return Outcome.earlyReturn(
                        guardrailRefusal.doOnNext(reason -> stepEvents.guardrail("input", reason)));
            }
            return Outcome.earlyReturn(guardrailRefusal);
        }

        // Agent lifecycle hooks (app.agent-hooks.enabled, default off): BEFORE_MODEL runs here -
        // after the input
        // guardrails, before any cache, provider spend or budget reservation. hookRun is null
        // unless the flag is
        // on AND a model/run-level hook is configured for this agent, so the lines that use it are
        // no-ops otherwise.
        com.agenticai.core.agents.hooks.HookRun hookRun =
                agentHooks == null ? null : agentHooks.beginRun(agentId, sessionId, stepEvents);
        String hookRefusal = hookRun == null ? null : hookRun.beforeModel(screenedMessage);
        if (hookRefusal != null) {
            return Outcome.earlyReturn(hookRun.refusal(hookRefusal));
        }

        // Master Roadmap 2026 item #18 (cost optimization: routing + semantic
        // caching, §G). Only the plain default turn shape is cache-eligible -
        // an attachment or per-call extra delegation tools both mean this
        // turn isn't a "the same question always gets the same answer" case,
        // so a cache lookup wouldn't even find anything reusable across
        // callers anyway. See LlmResponseCacheService's javadoc for the full
        // exact-match-only reasoning and the live-tool-data caveat.
        // Never for a continuation: its message is unique to one approved result, so a hit is
        // impossible, and caching an answer built from a person's tool output helps nobody.
        boolean cacheEligible =
                agent.isResponseCachingEnabled()
                        && !continuation
                        && (hookRun == null || !hookRun.needsFreshModelReply())
                        && safeAttachmentKey == null
                        && (inlineAttachments == null || inlineAttachments.isEmpty())
                        && (extraTools == null || extraTools.isEmpty());
        // Gated semantic (embedding-similarity) cache - a stronger, SEPARATE
        // opt-in tried only after an exact-match miss. Present only under the
        // "knowledge" profile (ObjectProvider), and consulted only when this
        // agent opted in AND the turn is the plain cacheable shape (no
        // attachment, no per-call extra tools). A hit is recorded and returned
        // exactly like an exact-match hit. See SemanticResponseCacheService
        // for the quality gates that keep this from returning a wrong answer.
        SemanticResponseCacheService semanticCache = semanticResponseCacheService.getIfAvailable();
        boolean semanticEligible =
                agent.isSemanticResponseCachingEnabled()
                        && !continuation
                        && semanticCache != null
                        && (hookRun == null || !hookRun.needsFreshModelReply())
                        && safeAttachmentKey == null
                        && (inlineAttachments == null || inlineAttachments.isEmpty())
                        && (extraTools == null || extraTools.isEmpty());
        // What the caches may do with this turn beyond the shape above (app.chat.cache.*): an agent that
        // offers tools is not cacheable (a turn that read live data must not be answered from, or stored
        // in, a cache), and the key includes the conversation before the message. Looked up only for a
        // turn that could use a cache at all, so an agent without caching pays nothing.
        ChatCachePolicy cachePolicy = svc.turns.cachePolicy;
        String cacheConversationKey = "";
        if ((cacheEligible || semanticEligible)
                && !cachePolicy.eligibleWithTools(context.scope(), agentId)
                && offersTools(agentId, sessionId, subjectKey)) {
            cacheEligible = false;
            semanticEligible = false;
        }
        if ((cacheEligible || semanticEligible) && cachePolicy.keyOnConversation()) {
            cacheConversationKey = cachePolicy.conversationKey(
                            sessionMemoryService.getHistory(memorySessionId), context.scope(), agentId);
        }
        if (cacheEligible) {
            Optional<String> cached =
                    responseCacheService.get(
                            currentTenantId, agentId, subjectKey, userMessage, cacheConversationKey);
            if (cached.isPresent()) {
                // Recorded exactly like a live turn so later messages in this
                // session still see a coherent back-and-forth - the caller
                // (and every downstream reader of session history) has no
                // way to tell a cache hit from a live answer.
                sessionMemoryService.appendTurn(memorySessionId, ChatTurn.user(userMessage));
                sessionMemoryService.appendTurn(memorySessionId, ChatTurn.assistant(cached.get()));
                events.emitAgentMessageCompleted(
                        agentId, sessionId, currentTenantId, cached.get(), "cache");
                return Outcome.earlyReturn(
                        ttft.record(
                                hookRun == null
                                        ? Flux.just(cached.get())
                                        : hookRun.wrap(Flux.just(cached.get())),
                                ChatTtftRecorder.Mode.CACHE_HIT,
                                ttftStartNanos));
            }
        }
        if (semanticEligible) {
            Optional<String> semanticHit =
                    semanticCache.get(agentId, subjectKey, userMessage, cacheConversationKey);
            if (semanticHit.isPresent()) {
                sessionMemoryService.appendTurn(memorySessionId, ChatTurn.user(userMessage));
                sessionMemoryService.appendTurn(
                        memorySessionId, ChatTurn.assistant(semanticHit.get()));
                events.emitAgentMessageCompleted(
                        agentId, sessionId, currentTenantId, semanticHit.get(), "semantic-cache");
                return Outcome.earlyReturn(
                        ttft.record(
                                hookRun == null
                                        ? Flux.just(semanticHit.get())
                                        : hookRun.wrap(Flux.just(semanticHit.get())),
                                ChatTtftRecorder.Mode.CACHE_HIT,
                                ttftStartNanos));
            }
        }
        return Outcome.proceed(
                new Turn(
                        currentTenantPlan,
                        agentId,
                        memorySessionId,
                        agent,
                        safeAttachmentKey,
                        hookRun,
                        cacheEligible,
                        semanticEligible,
                        semanticCache,
                        activeFlags,
                        context,
                        cacheConversationKey));
    }

    /**
     * Whether the agent would be offered at least one tool: the same merged, allow-list-filtered candidate
     * set a turn resolves its tools from, without the per-message trimming. A failure to tell is answered
     * "yes": the turn is then not cacheable, never cached on a guess.
     */
    private boolean offersTools(UUID agentId, String sessionId, String subjectKey) {
        try {
            List<ToolCallback> candidates = svc.agentToolProvider.resolveToolCandidatesFor(agentId, sessionId, subjectKey);
            return candidates != null && !candidates.isEmpty();
        } catch (RuntimeException e) {
            AgentChatService.log.warn(
                    "Could not tell whether agent {} offers tools - treating the turn as not cacheable: {}",
                    agentId,
                    e.toString());
            return true;
        }
    }
}
