package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Folds turns that have left {@link SessionMemoryService}'s replay window into a rolling,
 * natural-language summary of the conversation so far — the LLM half of "session-history
 * summarisation on window eviction".
 *
 * <p>Until 2026-09-28 the trigger was the STORAGE trim ({@link
 * SessionMemoryService#MAX_STORED_ENTRIES} entries) rather than the replay window, which left every
 * turn between the two neither replayed nor summarised (turns 1-60 of a 100-turn session with the
 * default 40-turn window). {@code SessionMemoryService} now claims a batch as soon as it leaves the
 * window; this class is unchanged in contract - it still just folds whatever batch it is handed
 * into the existing summary.
 *
 * <h2>Why this is a separate bean, not five more fields on SessionMemoryService</h2>
 *
 * {@link SessionMemoryService} is a three-dependency Redis wrapper that a slim test context (and
 * {@code AgentToolCallApprovalExecutor}, which takes it through an {@code ObjectProvider} precisely
 * so it stays cheap) can construct with three mocks. Resolving a {@link ChatClient} needs five more
 * collaborators — credential resolution, the gateway, the model catalog, plan lookup and the
 * free-tier hosted pool — and dragging all of that into the memory layer would make the cheapest
 * object in the chat hot path the most expensive one to stand up. So the LLM work lives here and
 * {@code SessionMemoryService} holds this as an {@code ObjectProvider}: when the bean is absent (a
 * slim context, or a deployment that has switched it off), the append path behaves exactly as it
 * did before this feature existed.
 *
 * <h2>ChatClient resolution mirrors AssistService, and for the same reason</h2>
 *
 * The plan branch in {@link #buildChatClient} is a deliberate copy of {@code
 * AssistService#buildChatClient} (which itself mirrors {@code
 * AgentChatService#buildChatClientFor}): FREE-plan tenants never have a BYOK provider to resolve,
 * so a pure {@link ProviderCredentialResolver} path would throw {@code NoActiveProviderException}
 * for every FREE tenant — which is exactly the population this feature is for, since the free
 * hosted pool is where the very long, cheap-to-run conversations live. That was a real, shipped
 * regression for "Ask AI" before {@code AssistService} grew the same branch; there is no reason to
 * re-learn it here.
 *
 * <p>{@code core/agents} cannot import {@code core/quality}'s {@code JudgeChatClientFactory} (the
 * module graph runs quality -> agents, not the other way round), so this is the pattern copied, not
 * the bean reused — the same break-glass duplication {@code DemoTranscriptEmailService} documents
 * for the Redis key prefix.
 *
 * <h2>Fail-open, always</h2>
 *
 * Every optional LLM-powered enhancement in this codebase degrades to a no-op rather than failing a
 * turn ({@code TaskPlanningService}, {@code ReflexionService}, {@code AnswerConsistencyChecker},
 * {@code LlmQualityJudgeService}). This one has a stronger obligation than those: it is invoked
 * from inside a Redis write on the chat path, so a throw here would not merely lose an enhancement,
 * it would lose the user's turn. {@link #foldAsync} therefore never throws — it validates, hands
 * everything to a background thread, and returns. On the background thread every failure (no active
 * provider, empty model catalog, gateway rejection, budget refusal, a blank or garbage completion)
 * is caught and logged at WARN, and the summary key is simply left as it was. That batch is then
 * missing from the summary (its turns are still stored, but no longer replayed) — the pre-existing
 * behaviour for those turns, not a new harm.
 *
 * <h2>Cost shape</h2>
 *
 * Nothing here runs for a short conversation. The first fold happens once a session has stored its
 * replay window ({@code history-max-turns}, default 40) plus {@link
 * SessionMemoryService#SUMMARY_FOLD_BATCH} more turns inside one TTL window (80 turns, i.e. ~40
 * exchanges, by default), and then once per {@link SessionMemoryService#SUMMARY_FOLD_BATCH} further
 * turns — one small, bounded, {@code temperature 0} completion per ~40 turns of a long session,
 * never one per turn. Moving the trigger from the 400-entry storage trim to the window made folds
 * happen in many more (ordinary long) sessions than before; the batching is what keeps that
 * affordable.
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    /**
     * Dedicated virtual-thread-per-task executor rather than {@code CompletableFuture.runAsync}'s
     * default {@code ForkJoinPool.commonPool()} (which is what {@code
     * LlmQualityJudgeService#sampleAsync} uses). The difference matters here: a fold is one
     * blocking HTTP call to an LLM provider that can legitimately park for tens of seconds, and the
     * common pool is sized to CPU count — a handful of simultaneously-folding sessions would starve
     * every other common-pool user on the JVM. Same reasoning, and the same "pure I/O wait, no pool
     * to size, no shutdown needed" properties, as {@code AgentChatService}'s {@code
     * preTurnExecutor}.
     */
    private final ExecutorService FOLD_EXECUTOR = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("session-summary-service-");

    /** Instance-owned (was static) so it is shut down with the bean (JAVA-026). */
    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        FOLD_EXECUTOR.shutdown();
    }

    private static final String SUMMARY_SYSTEM_PROMPT =
            """
        You maintain a running summary of a long conversation between a user and an AI assistant.
        The oldest turns are being dropped to keep the conversation within its memory budget, and
        your summary is the only thing that will survive to represent them.

        You will be given the existing running summary (if any) and the turns being dropped.
        Produce ONE updated running summary that merges them.

        Rules:
        - Preserve durable facts: who the user is, what they are working on, stated goals,
          constraints, preferences, decisions already made, identifiers, names, numbers, and
          anything the assistant promised or committed to.
        - Preserve unresolved threads - open questions, pending steps, things left half-done.
        - Drop pleasantries, restatements, and anything already superseded by a later turn.
        - Never invent anything that is not in the material you were given.
        - Write plain prose or short bullets in the third person ("the user ...", "the assistant ...").

        Respond with ONLY the updated summary text - no preamble, no headings, no markdown fences.
        """;

    private final ProviderCredentialResolver credentialResolver;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final String defaultModel;
    private final boolean enabled;
    private final int maxTokens;
    private final int maxSummaryChars;
    private final int maxTurnExcerptChars;
    private final int maxPromptChars;

    public SessionSummaryService(
            ProviderCredentialResolver credentialResolver,
            LlmGatewayService llmGatewayService,
            ProviderModelCatalogService providerModelCatalogService,
            TenantLimits tenantLimits,
            FreeTierChatClientService freeTierChatClientService,
            @Value("${app.agents.default-model}") String defaultModel,
            @Value("${app.chat.history-summary-enabled:true}") boolean enabled,
            @Value("${app.chat.history-summary-max-tokens:600}") int maxTokens,
            @Value("${app.chat.history-summary-max-chars:4000}") int maxSummaryChars,
            @Value("${app.chat.history-summary-turn-excerpt-chars:800}") int maxTurnExcerptChars,
            @Value("${app.chat.history-summary-max-prompt-chars:24000}") int maxPromptChars) {
        this.credentialResolver = credentialResolver;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.defaultModel = defaultModel;
        this.enabled = enabled;
        this.maxTokens = maxTokens;
        this.maxSummaryChars = maxSummaryChars;
        this.maxTurnExcerptChars = maxTurnExcerptChars;
        this.maxPromptChars = maxPromptChars;
    }

    /**
     * Master switch, read by {@link SessionMemoryService} BEFORE it does any of the extra Redis
     * work a fold needs ({@code LLEN}, the overflow {@code LRANGE}, the summary {@code GET}). Off
     * means the append path is byte-for-byte what it was before this feature: push, trim to {@link
     * SessionMemoryService#MAX_STORED_ENTRIES}, refresh TTL - no fold claim script, no marker key,
     * no summary read.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Folds {@code evictedTurns} into {@code existingSummary} on a background thread and hands the
     * result to {@code persist}. Returns immediately and NEVER throws — see the class javadoc for
     * why that is a hard requirement rather than a nicety: the caller is mid-way through a Redis
     * write on the chat path, and an exception escaping here would cost the user their turn.
     *
     * <p><b>{@code tenantSnapshot} is passed as data, not read from ambient state.</b> {@link
     * TenantContext} is a {@code ThreadLocal} and this method is reached from {@code
     * SessionMemoryService#appendTurn}, which on the assistant-turn path runs on the
     * stream-completion thread where the ambient context is already gone (the same reason {@code
     * AgentChatService#auditStreamedOutboundViolation} re-applies a snapshot captured back on the
     * request thread). Reading the ThreadLocal here would therefore be empty exactly when it
     * matters. Worse than empty: {@code TenantSchemaResolver} silently falls back to the {@code
     * public} schema when the context is missing, which is how every document-generation job on the
     * platform sat in {@code REQUESTED} for three days in 2026-08-10 — a 100% failure rate with no
     * error anywhere. So the snapshot travels as an argument, is applied before anything else on
     * the background thread, and an empty snapshot means we skip the fold entirely rather than
     * resolve credentials or a plan against the wrong tenant.
     *
     * @param sessionId only used for log correlation — the Redis keys are the caller's business,
     *     this service touches no Redis of its own.
     * @param tenantSnapshot the caller's {@code TenantContext}, captured on a thread that actually
     *     had one. Empty means no fold.
     * @param existingSummary the current rolling summary, or {@code null}/blank the first time a
     *     session ever overflows.
     * @param evictedTurns the batch of turns that just left the replay window, oldest first.
     * @param persist receives the folded summary. Invoked on the background thread, only on
     *     success, and only with non-blank text.
     */
    public void foldAsync(
            String sessionId,
            Optional<TenantContext> tenantSnapshot,
            String existingSummary,
            List<ChatTurn> evictedTurns,
            Consumer<String> persist) {
        if (!enabled || persist == null || evictedTurns == null || evictedTurns.isEmpty()) {
            return;
        }
        if (tenantSnapshot == null || tenantSnapshot.isEmpty()) {
            // Not an error: appendTurn legitimately runs on threads with no
            // ambient tenant (see method javadoc). Losing this fold costs the
            // pre-existing behaviour and nothing more, and the user turn that
            // follows will fold at the next batch boundary anyway.
            log.debug(
                    "Skipping session-history fold for session {} - no tenant context to run the summariser under",
                    sessionId);
            return;
        }

        // Defensive copy: the caller's list is derived from a Redis read it is
        // free to reuse or mutate once appendTurn returns, and this work
        // outlives that call by design.
        List<ChatTurn> turns = List.copyOf(evictedTurns);
        // TenantScope restores the executing thread's previous context (and clears it if there was
        // none) rather than a blind clear(), so it is correct on any executor.
        TenantScope tenant = TenantScope.of(tenantSnapshot);
        CompletableFuture.runAsync(
                tenant.wrapRunnable(
                        () -> {
                            try {
                                fold(sessionId, existingSummary, turns, persist);
                            } catch (Exception e) {
                                // Exception, not RuntimeException - the same breadth
                                // LlmQualityJudgeService#sampleAsync catches, because a
                                // provider SDK is free to surface anything and there is no
                                // failure here worth propagating to a thread nobody is
                                // waiting on.
                                log.warn(
                                        "Session-history summarisation failed for session {} ({} evicted turns) - "
                                                + "the rolling summary is left unchanged and those turns are dropped, as they were before this feature",
                                        sessionId,
                                        turns.size(),
                                        e);
                            }
                        }),
                FOLD_EXECUTOR);
    }

    private void fold(
            String sessionId,
            String existingSummary,
            List<ChatTurn> turns,
            Consumer<String> persist) {
        ChatClient chatClient = buildChatClient();
        String folded =
                chatClient
                        .prompt()
                        .system(SUMMARY_SYSTEM_PROMPT)
                        .user(buildFoldPrompt(existingSummary, turns))
                        .call()
                        .content();

        if (folded == null || folded.isBlank()) {
            // A blank completion is a failed fold, not an instruction to blank
            // the summary - overwriting a good summary with nothing would be
            // strictly worse than keeping a stale one.
            log.warn(
                    "Session-history summariser returned an empty summary for session {} - keeping the previous one",
                    sessionId);
            return;
        }
        String trimmed = folded.strip();
        if (trimmed.length() > maxSummaryChars) {
            // The rolling summary is replayed into EVERY subsequent turn of
            // this session, so an unbounded one would reintroduce the exact
            // unbounded-replay token leak the 2026-08-11 audit closed. maxTokens
            // already bounds this at the provider; this is the belt-and-braces
            // bound for a provider that ignores it.
            trimmed = trimmed.substring(0, maxSummaryChars);
        }
        persist.accept(trimmed);
        log.debug(
                "Folded {} evicted turns into the rolling summary for session {} ({} chars)",
                turns.size(),
                sessionId,
                trimmed.length());
    }

    /**
     * Renders the fold request. Turns are rendered newest-first into the budget and then reversed
     * back to chronological order, so if the batch is too large for {@code maxPromptChars} it is
     * the OLDEST turns of the batch that get dropped — they are the ones the existing running
     * summary is most likely to already cover, and the newest are the ones the next turns of the
     * conversation will actually refer back to.
     */
    private String buildFoldPrompt(String existingSummary, List<ChatTurn> turns) {
        StringBuilder head = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            head.append("Existing running summary of everything that came before these turns:\n")
                    .append(existingSummary)
                    .append("\n\n");
        } else {
            head.append(
                    "There is no existing summary yet - these are the oldest turns of the conversation.\n\n");
        }
        head.append("Turns being dropped from memory (oldest first):\n");

        int budget = Math.max(0, maxPromptChars - head.length());
        List<String> rendered = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            String line = render(turns.get(i));
            if (!rendered.isEmpty() && used + line.length() > budget) {
                omitted = i + 1;
                break;
            }
            rendered.add(line);
            used += line.length();
        }
        Collections.reverse(rendered);

        StringBuilder body = new StringBuilder(head);
        if (omitted > 0) {
            body.append("[")
                    .append(omitted)
                    .append(
                            " older turn(s) omitted for length - rely on the existing summary above for them]\n");
        }
        rendered.forEach(body::append);
        return body.toString();
    }

    private String render(ChatTurn turn) {
        String content = turn.content() == null ? "" : turn.content();
        if (content.length() > maxTurnExcerptChars) {
            content = content.substring(0, maxTurnExcerptChars) + "...";
        }
        return (turn.role() == MessageRole.USER ? "USER: " : "ASSISTANT: ") + content + "\n";
    }

    /**
     * Copy of {@code AssistService#buildChatClient} — platform-pool tenants (the default for every
     * tenant, via {@code TenantLimits#usesPlatformPool}) through the platform-hosted pool,
     * explicit-BYOK tenants through their own credential — see the class javadoc for why this is
     * duplicated rather than shared.
     *
     * <p>{@code temperature 0.0} and a small {@code maxTokens}: a rolling summary is an extraction
     * task, not a creative one, and the same numbers {@code JudgeChatClientFactory} uses for the
     * quality judge apply for the same reason. {@code topP 1.0} (no nucleus narrowing) — only an
     * agent's own configured value should ever tune that, never an internal helper.
     *
     * <p>Note the FREE branch meters: {@code buildClientForFreeTenant} consumes one message unit
     * against the tenant's usage cap. That is accepted rather than worked around — a fold is real
     * platform spend on a real tenant's behalf, and it happens once per {@link
     * SessionMemoryService#SUMMARY_FOLD_BATCH} turns of a session that is already past its replay
     * window (80+ turns by default), so it is at most ~2.5% of that session's message units. If the
     * quota is already exhausted the limiter throws, the fold is caught and logged, and eviction
     * degrades to today's behaviour.
     */
    private ChatClient buildChatClient() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(
                    tenantId, plan, maxTokens, 0.0, 1.0);
        }
        ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
        return llmGatewayService.resolveClient(
                tenantId,
                new GatewayCallSpec(
                        active.providerName(),
                        active.apiKey(),
                        resolveModelFor(active.providerName()),
                        maxTokens,
                        0.0,
                        1.0));
    }

    /**
     * Same model-selection fix {@code AssistService#resolveModelFor} carries: {@code
     * app.agents.default-model} is an Anthropic model id, and passing it to whichever provider
     * happens to hold the active BYOK credential produced a 404 "model not found" at the gateway.
     * Look the model up in the provider's own catalog; only fall back to the configured default
     * when the catalog is unexpectedly empty AND the provider is actually Anthropic.
     */
    private String resolveModelFor(ProviderName provider) {
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(provider);
        if (!models.isEmpty()) {
            return models.get(0).modelId();
        }
        if (provider == ProviderName.ANTHROPIC) {
            return defaultModel;
        }
        throw new ProviderModelCatalogEmptyException(provider);
    }
}
