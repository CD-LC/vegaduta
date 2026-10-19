package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.FreeTierProviderPoolEmptyException;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.loadtest.LoadTestChatClientFactory;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
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
     * load-test tenant is never on the hosted pool. {@code context} is the turn's context, with
     * the hosted chain's reservation guards as its budget when the turn is on the hosted pool
     * (every link carries the same guards; see {@link HostedChainLink}).
     */
    record Clients(
            ChatClient chatClient,
            List<HostedChainLink> hostedChain,
            TenantPlan hostedPlan,
            boolean usesHostedPool,
            boolean isLoadTestTenant,
            TurnContext context) {}

    private final AgentChatService svc;

    ChatClientSelection(AgentChatService svc) {
        this.svc = svc;
    }

    Outcome select(
            TurnContext context,
            ChatTurnPreflight.Turn turn,
            RlTurnPlan rl,
            String userMessage,
            String subjectKey) {
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
                                        + " Providers settings."));
            }
        } else {
            hostedChain = null;
        }
        ChatClient chatClient =
                isLoadTestTenant
                        ? LoadTestChatClientFactory.create()
                        : usesHostedPool
                                ? hostedChain.get(0).client()
                                : clientResolver.buildChatClientFor(
                                        agent,
                                        userMessage,
                                        rlModelOverride,
                                        currentTenantId,
                                        currentTenantPlan,
                                        effTemperature,
                                        effTopP);
        TurnContext turnContext =
                usesHostedPool && !hostedChain.isEmpty()
                        ? context.withBudget(hostedChain.get(0).guards())
                        : context;
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
