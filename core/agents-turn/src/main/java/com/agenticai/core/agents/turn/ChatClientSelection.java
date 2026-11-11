package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.FreeTierProviderPoolEmptyException;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.loadtest.LoadTestChatClientFactory;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.agents.text.AppContextText;
import com.agenticai.core.guardrails.SandboxApprovalGrants;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * Resolves the ChatClient one turn runs against - the load-test seam, the platform-hosted pool
 * chain (with the graceful empty-pool notice) or the BYOK client - moved verbatim out of {@link
 * AgentChatService#chat}.
 *
 * <p>ADR-0022 stage 2: the tenant comes from the explicit {@link TurnContext}, and a hosted turn's
 * {@code TurnReservationGuards} are stored on the context it hands onward ({@link
 * TurnContext#withBudget}); {@link Clients#context()} is the context every later stage reads.
 */
final class ChatClientSelection {

    /** Either the empty-pool notice or the resolved clients. */
    record Outcome(Flux<String> earlyReturn, Clients clients) {
        static Outcome earlyReturn(Flux<String> flux) {
            return new Outcome(flux, null);
        }

        static Outcome proceed(Clients clients) {
            return new Outcome(null, clients);
        }
    }

    /**
     * {@code hostedChain} and {@code hostedPlan} are null unless {@code usesHostedPool}; a
     * load-test tenant is never on the hosted pool. {@code context} is the turn's context, with the
     * hosted chain's reservation guards as its budget when the turn is on the hosted pool (every
     * link carries the same guards; see {@link HostedChainLink}).
     */
    record Clients(
            ChatClient chatClient,
            List<HostedChainLink> hostedChain,
            TenantPlan hostedPlan,
            boolean usesHostedPool,
            boolean isLoadTestTenant,
            TurnContext context) {}

    private final AgentChatService svc;
    private final FollowUpTools followUp;
    private final ContinuationProperties continuation;
    private final LocalModelTurnProperties localModel;

    ChatClientSelection(
            AgentChatService svc, FollowUpTools followUp, ContinuationProperties continuation) {
        this(svc, followUp, continuation, new LocalModelTurnProperties());
    }

    ChatClientSelection(
            AgentChatService svc,
            FollowUpTools followUp,
            ContinuationProperties continuation,
            LocalModelTurnProperties localModel) {
        this.svc = svc;
        this.followUp = followUp;
        this.continuation = continuation;
        this.localModel = localModel == null ? new LocalModelTurnProperties() : localModel;
    }

    /**
     * Whether this turn offers the agent's tool set unchanged (see {@link
     * TurnModelTraits#stableToolSet}): the model it starts on is served on the person's own machine
     * (the install's local pool row, or a bring-your-own provider in {@code
     * app.chat.local-model.providers}), {@code app.chat.local-model.stable-tools} is on, and the
     * model is not in small-model mode (that mode's short tool list matters more than a cached
     * prompt on a slow machine).
     */
    boolean stableToolSet(Agent agent, List<HostedChainLink> hostedChain) {
        if (!localModel.isStableTools()) {
            return false;
        }
        if (hostedChain != null && !hostedChain.isEmpty()) {
            HostedChainLink head = hostedChain.get(0);
            return head.localModel() && !head.smallModel();
        }
        return agent != null
                && agent.getProviderName() != null
                && localModel.isLocalProvider(agent.getProviderName().name());
    }

    /**
     * Whether the model this turn starts on is a weak tool caller (see {@link TurnModelTraits}): a
     * platform-hosted row in small-model mode or measured at a text-only tool-calling grade, or a
     * bring-your-own provider listed in {@code app.chat.continuation.heuristic-providers}.
     */
    boolean weakToolCaller(Agent agent, List<HostedChainLink> hostedChain) {
        if (hostedChain != null && !hostedChain.isEmpty()) {
            HostedChainLink head = hostedChain.get(0);
            return head.smallModel()
                    || (head.toolCallGrade() != null
                            && continuation
                                    .getHeuristicToolGrades()
                                    .contains(head.toolCallGrade()));
        }
        return agent.getProviderName() != null
                && continuation.getHeuristicProviders().contains(agent.getProviderName());
    }

    /**
     * Whether the model this turn starts on forces on-demand tool loading (see {@link
     * TurnModelTraits#toolDeferral}): the first hosted-chain link's own row setting (V674). A BYOK
     * provider has no pool row, so the global {@code app.tools.search.enabled} decides.
     */
    boolean toolDeferral(Agent agent, List<HostedChainLink> hostedChain) {
        return hostedChain != null
                && !hostedChain.isEmpty()
                && Boolean.TRUE.equals(hostedChain.get(0).toolDeferral());
    }

    Outcome select(
            TurnContext context,
            ChatTurnPreflight.Turn turn,
            RlTurnPlan rl,
            String userMessage,
            String subjectKey,
            List<String> runToolNames) {
        LoadTestTenantAllowlist loadTestTenantAllowlist = svc.loadTestTenantAllowlist;
        ChatClientResolver clientResolver = svc.clientResolver;
        FreeTierChatClientService freeTierChatClientService = svc.freeTierChatClientService;
        String currentTenantId = context.tenantId();
        TenantPlan currentTenantPlan = turn.currentTenantPlan();
        UUID agentId = turn.agentId();
        Agent agent = turn.agent();
        String rlModelOverride = rl.rlModelOverride;
        double effTemperature = rl.effTemperature;
        double effTopP = rl.effTopP;

        // P3 (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §6) - platform-hosted pool
        // fallback chain, merged with the §4 P1 per-user dollar budget. A tenant
        // on the hosted pool (FREE, or a paid tenant opted into usePlatformLlm)
        // resolves the FULL chain up front (per-tenant quota + per-user budget
        // checked ONCE inside, not per provider), passing this turn's subjectKey
        // + TEXT modality so the per-user dollar budget applies. Chain order is
        // no longer plain `priority` ASC - it is cheapest-then-fastest with
        // known-dead rows pushed to the back; see
        // FreeTierChatClientService#orderForFailover for exactly which data
        // backs each key. The chain head is the primary client (tools/memory/
        // dispatch) and EVERY hosted turn is wrapped in attemptHostedChain, a
        // one-row pool included. BYOK tenants keep buildChatClientFor's
        // cost-routing/credential path and are NOT budgeted (own key).
        // LOAD-TEST SEAM (default-off, tenant-gated) - checked FIRST, ahead of
        // usesHostedPool, so a load-test tenant's turn NEVER touches a real
        // provider credential, the hosted-pool per-user budget/rate limiter,
        // or FreeTierChatClientService's chain resolution. See
        // LoadTestTenantAllowlist for the single source of truth on which
        // tenants this can ever apply to: by default (no admin action) its
        // backing platform setting is empty, so isLoadTestTenant() is false
        // for every tenant, always - this branch is structurally unreachable
        // in normal operation. When an operator has explicitly opted a
        // tenant in, LoadTestChatClientFactory hands back a ChatClient
        // wrapping the canned, network-free LoadTestChatModel; usesHostedPool
        // stays false and hostedChain stays null for that tenant (identical
        // to the ordinary BYOK path below), so this turn is also NOT wrapped
        // in attemptHostedChain further down - there is no real provider
        // chain to fail over between. Everything downstream of this line
        // (advisors, tool resolution, streaming, session memory, guardrails)
        // is completely unaware the model call is fake.
        boolean isLoadTestTenant =
                currentTenantId != null
                        && loadTestTenantAllowlist.isLoadTestTenant(currentTenantId);
        boolean usesHostedPool =
                !isLoadTestTenant
                        && currentTenantId != null
                        && clientResolver.usesPlatformLlm(currentTenantId, currentTenantPlan);
        // Reuse currentTenantPlan (same value tenantLimits.plan(currentTenantId)
        // would have returned - usesHostedPool being true already implies
        // currentTenantId != null) instead of re-querying it a second time.
        TenantPlan hostedPlan = usesHostedPool ? currentTenantPlan : null;
        // Per explicit maintainer direction (2026-08-06 incident): an empty/disabled
        // platform pool is a PLATFORM-OPERATOR configuration gap, not a real
        // client-facing failure - it should never surface as a thrown
        // exception/hard error to the turn. Log it (the existing signal
        // GlobalExceptionHandler's own handler already emits, kept here too
        // since this path no longer reaches that handler) and return a
        // plain, honest, single-token reply instead - the same "graceful
        // canned reply, not a hang or a stack trace" posture this session
        // already applied to the empty-STT-transcript case in
        // llm_core_bridge.py. Short-circuits the ENTIRE rest of this method
        // (tools/memory/vision/streaming) deliberately - none of that has
        // anything to run against without a client.
        List<HostedChainLink> hostedChain;
        if (usesHostedPool) {
            try {
                hostedChain =
                        freeTierChatClientService.buildChainForFreeTenant(
                                currentTenantId,
                                hostedPlan,
                                agent.getMaxTokens(),
                                effTemperature,
                                effTopP,
                                subjectKey,
                                PerUserBudgetService.Modality.TEXT,
                                ChatClientResolver.samplingOptions(agent));
            } catch (FreeTierProviderPoolEmptyException e) {
                AgentChatService.log.warn(
                        "No platform LLM provider is enabled - agent {} (tenant {}) call answered"
                                + " with a graceful notice instead of failing the turn",
                        agentId,
                        currentTenantId);
                return Outcome.earlyReturn(
                        Flux.just(
                                AgentChatService.NO_PLATFORM_LLM_NOTICE_PREFIX
                                        + " for this workspace right now. Please contact support,"
                                        + " or switch this agent to your own provider (BYOK) in"
                                        + " Providers settings. On an install with a local model,"
                                        + " open Troubleshoot or restart VegaDūta: it repairs the"
                                        + " local model's entry on start."));
            }
        } else {
            hostedChain = null;
        }
        context.modelTraits().weakToolCaller(weakToolCaller(agent, hostedChain));
        context.modelTraits().stableToolSet(stableToolSet(agent, hostedChain));
        context.modelTraits().toolDeferral(toolDeferral(agent, hostedChain));
        // A short follow-up ("yes", "continue") carries no words to select tools or a model by: it
        // keeps
        // the tools the conversation already used and stays on the model that started the task. The
        // continuation after an approval has its own goal and tool list.
        Set<String> conversationTools =
                context.continuation() == null
                        ? followUp.stickyTools(
                                agentId,
                                context.sessionId(),
                                userMessage,
                                // The tenant's and the agent's chat.followUp.stickyTools* settings,
                                // asked only for a follow-up.
                                () -> {
                                    FollowUpTools.StickyPolicy platform = followUp.platformPolicy();
                                    return new FollowUpTools.StickyPolicy(
                                            svc.turns.behaviour.bool(
                                                    "chat.followUp.stickyToolsEnabled",
                                                    context.scope(),
                                                    agentId,
                                                    platform::enabled),
                                            svc.turns.behaviour.seconds(
                                                    "chat.followUp.stickyToolsWindow",
                                                    context.scope(),
                                                    agentId,
                                                    platform::window),
                                            svc.turns.behaviour.integer(
                                                    "chat.followUp.maxStickyTools",
                                                    context.scope(),
                                                    agentId,
                                                    platform::maxTools));
                                })
                        : Set.of();
        boolean keepPrimaryModel =
                !conversationTools.isEmpty()
                        && followUp.keepsPrimaryModel(
                                userMessage,
                                conversationTools,
                                svc.turns.behaviour.bool(
                                        "chat.followUp.keepPrimaryModel",
                                        context.scope(),
                                        agentId,
                                        followUp::platformKeepsPrimaryModel));
        ChatClient chatClient =
                isLoadTestTenant
                        ? LoadTestChatClientFactory.create()
                        : usesHostedPool
                                ? hostedChain.get(0).client()
                                : clientResolver.buildChatClientFor(
                                        agent,
                                        // Cost routing judges the TASK; on a continuation that is
                                        // the
                                        // original goal (a platform-written message is never
                                        // "simple").
                                        context.decisionMessage(userMessage),
                                        rlModelOverride,
                                        currentTenantId,
                                        currentTenantPlan,
                                        effTemperature,
                                        effTopP,
                                        keepPrimaryModel);
        // One approval state per turn, shared by reference with the tool guards (tool resolution
        // reads it from this context) and the FAST loop: it carries the scoped memory id the replay
        // envelope needs and tells the loop when a tool call has parked the turn on an approval.
        // The goal it records is the person's own message (the ORIGINAL one on a continuation), and
        // the tools the conversation already used ride along so tool selection keeps them on offer.
        ApprovalContinuation approvalContinuation = context.continuation();
        TurnApprovalState approvalState =
                new TurnApprovalState(
                        turn.memorySessionId(),
                        // The goal shown on the approval card must be the person's words, not the
                        // console's <app_context> preamble the decorated message still carries
                        // here.
                        AppContextText.withoutAppContext(context.decisionMessage(userMessage)),
                        context.requestedAgentId(),
                        subjectKey,
                        approvalContinuation == null
                                ? conversationTools
                                : Set.copyOf(approvalContinuation.stickyTools()),
                        // The run-scoped tool selection (null = not narrowed): the journal shows
                        // it, tool
                        // resolution keeps it out of relevance trimming and the approval gate
                        // passes it on to
                        // the continuation.
                        runToolNames);
        // An offline eval turn the server itself started under a sandbox auto-approval (registered
        // under this
        // turn's request id by whoever started the turn; see SandboxApprovalGrants). Every other
        // turn has none.
        approvalState.enableSandboxGrant(SandboxApprovalGrants.forTurn(context.requestId()));
        TurnContext withApproval = context.withApproval(approvalState);
        TurnContext turnContext =
                usesHostedPool && !hostedChain.isEmpty()
                        ? withApproval.withBudget(hostedChain.get(0).guards())
                        : withApproval;
        return Outcome.proceed(
                new Clients(
                        chatClient,
                        hostedChain,
                        hostedPlan,
                        usesHostedPool,
                        isLoadTestTenant,
                        turnContext));
    }
}
