package com.agenticai.core.agents.hosted;

import com.agenticai.core.agents.budget.TurnReservationGuards;
import org.springframework.ai.chat.client.ChatClient;

/**
 * One rung of the platform-hosted pool's failover chain: a ready-to-use {@link
 * ChatClient} plus just enough identity to (a) log WHICH provider failed and
 * (b) attribute the outcome back to that pool row in {@link
 * HostedProviderHealth}.
 *
 * <p>Exists because the chain used to be a bare {@code List<ChatClient>}: a
 * Spring AI client is an opaque handle with no way to ask it "which pool row
 * built you", so a failure could only ever be logged as "provider #1" and
 * there was nothing to key a circuit breaker or a latency observation on.
 * Carrying the identity alongside the client (rather than in a parallel list)
 * makes index drift between the two structurally impossible.
 *
 * @param client ready-to-use client, advisors already attached.
 * @param healthKey stable identity for this pool ROW across turns - see
 *     {@code FreeTierChatClientService#healthKey}. Null only in tests that
 *     drive the chain control flow with marker clients.
 * @param label the admin-facing row label, for logs and issue records.
 * @param model the model id this row is configured with - the key {@code
 *     LlmPricingCatalog} prices against.
 * @param priority the row's admin-set {@code priority} column, kept as the
 *     final ordering tie-break (see {@code FreeTierChatClientService#orderForFailover}).
 * @param reservedTokens 2026-08-08 usage-counter audit fix - the token-budget
 *     reservation {@code FreeTierChatClientService#buildChainForFreeTenant}
 *     made for the WHOLE turn (same value repeated on every link in one
 *     turn's chain, not per-link) - carried here so {@code AgentChatService}
 *     can release it if every link in the chain ends up failing (see {@code
 *     FreeTierUsageLimiterService#reconcileTokenReservation}). 0 when
 *     nothing was reserved.
 * @param reservedMicroCents same idea as {@code reservedTokens}, for the
 *     per-user dollar-budget reservation ({@code
 *     PerUserBudgetService#checkAndReserveBudget}). 0 when nothing was
 *     reserved (no end-user identity for this turn, or the master switch
 *     was off).
 * @param guards 2026-08-12 fix - the turn's ONE {@link TurnReservationGuards}
 *     instance (same object on every link in one turn's chain, exactly like
 *     the two reservation amounts above), shared between the links' advisors
 *     and {@code AgentChatService}'s total-chain-failure release path so the
 *     reservations can never be released twice across an idle-timeout cancel
 *     plus a later failover/exhaustion - see that class's javadoc.
 */
public record HostedChainLink(
    ChatClient client, String healthKey, String label, String model, int priority,
    long reservedTokens, long reservedMicroCents, TurnReservationGuards guards,
    com.agenticai.core.chat.SamplingOptions sampling,
    // V501 - the row's small-model mode (PlatformHostedLlmProvider#effectiveSmallModel): the
    // turn offers at most six tools, uses the compact directives and runs at FAST depth.
    boolean smallModel,
    // The row's measured tool-calling grade (PlatformHostedLlmProvider#getToolCallGrade), null when not
    // measured: a TEXT or NONE row is a weak tool caller whose tool-less reply may be an announced step.
    com.agenticai.core.providers.ToolCallGrade toolCallGrade,
    // The row is this install's own local model server (HostedLlmChatClientFactory#isLocalOllamaRow). A local
    // server keeps one prompt cache, so the turn keeps its tool set stable (app.chat.local-model.stable-tools).
    boolean localModel,
    // V674 - the row's stream idle-timeout override (PlatformHostedLlmProvider#getResponseTimeoutMs);
    // null = app.llm.http.stream-idle-timeout. A slow local model needs longer than a hosted API's 90 s.
    java.time.Duration streamIdleTimeout,
    // V674 - the row's tool-deferral override (PlatformHostedLlmProvider#getToolDeferral): TRUE
    // forces on-demand tool loading (search_tools/call_tool) on this row's turns regardless of
    // app.tools.search.enabled; null/false = the configured default.
    Boolean toolDeferral
) {

    /** Pre-runtime-knobs shape (no idle-timeout or tool-deferral), kept for existing call sites. */
    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents, TurnReservationGuards guards,
        com.agenticai.core.chat.SamplingOptions sampling, boolean smallModel,
        com.agenticai.core.providers.ToolCallGrade toolCallGrade, boolean localModel
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, guards, sampling, smallModel, toolCallGrade, localModel, null, null);
    }

    /** Pre-local-flag shape, kept so every existing call site still compiles. */
    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents, TurnReservationGuards guards,
        com.agenticai.core.chat.SamplingOptions sampling, boolean smallModel,
        com.agenticai.core.providers.ToolCallGrade toolCallGrade
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, guards, sampling, smallModel, toolCallGrade, false);
    }

    /** Pre-grade shape (no tool-calling grade), kept so every existing call site still compiles. */
    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents, TurnReservationGuards guards,
        com.agenticai.core.chat.SamplingOptions sampling, boolean smallModel
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, guards, sampling, smallModel, null);
    }

    /** Pre-V501 shape (no small-model flag), kept so every existing call site still compiles. */
    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents, TurnReservationGuards guards,
        com.agenticai.core.chat.SamplingOptions sampling
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, guards, sampling, false, null);
    }

    public HostedChainLink {
        // Never null by construction - a link built outside
        // FreeTierChatClientService (tests, the client-only chain overload)
        // gets its own private guards, which is correct there: nothing else
        // holds those links' (zero) reservations.
        guards = guards == null ? new TurnReservationGuards() : guards;
        // The agent's sampling controls (stop/topK/penalties/seed), kept on the link so a
        // self-heal rebuild re-applies them instead of silently dropping them.
        sampling = sampling == null ? com.agenticai.core.chat.SamplingOptions.NONE : sampling;
    }

    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents, TurnReservationGuards guards
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, guards, null);
    }

    /** Convenience for tests and the client-only chain overload - private per-link guards. */
    public HostedChainLink(
        ChatClient client, String healthKey, String label, String model, int priority,
        long reservedTokens, long reservedMicroCents
    ) {
        this(client, healthKey, label, model, priority, reservedTokens, reservedMicroCents, null);
    }
}
