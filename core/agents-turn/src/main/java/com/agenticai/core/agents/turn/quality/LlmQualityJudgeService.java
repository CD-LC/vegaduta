package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.langfuse.LangfuseScoreClient;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Gap 2 (Reliability/Governance/SDLC build plan) - a SAMPLED, ASYNCHRONOUS
 * LLM-as-judge quality signal feeding the admin Issues portal (#425/#426).
 *
 * <p>Deliberately not a blocking gate: unlike {@link AnswerConsistencyChecker}
 * (self-consistency, CAREFUL/RIGOROUS) and {@link ReflexionService}
 * (Reflexion critique, RIGOROUS-only), both of which run synchronously
 * before an answer is shown so they can revise it, this service runs
 * strictly AFTER a turn's real answer has already been returned to the
 * caller, on a background thread, and never revises or blocks anything. It
 * exists purely to catch quality problems the platform operator should know
 * about (hallucination-shaped confident claims, off-target answers,
 * unwarranted refusals, broken/incoherent output) across every reasoning
 * depth and every tenant, without paying a second LLM call's latency on
 * every single turn - {@code sampleRate} (default 5%) controls how much of
 * that quality-observability-vs-cost tradeoff is spent.
 *
 * <p>Reuses whichever {@link ChatClient} the calling turn already resolved
 * rather than opening a second provider/model selection path - same "the
 * caller already paid for a working client, don't re-derive one" reasoning
 * {@code AnswerConsistencyChecker}/{@code ReflexionService} already apply,
 * just off the request thread. The judge call's own cost/latency therefore
 * never affects the user-visible response, and a judge-call failure
 * (provider hiccup, budget/rate-limit rejection from {@code
 * LlmGatewayService}, malformed verdict) is swallowed with a warning log -
 * losing one quality sample is never worth risking anything else.
 *
 * <p>{@code TenantContext} is snapshotted on the calling thread and restored
 * on the background thread before the (schema-per-tenant) {@link
 * PlatformIssueService} call - the exact same cross-thread-boundary idiom
 * {@code WorkflowTriggerPortImpl#triggerAsync} already established for fire-
 * and-forget work, not a new pattern invented here.
 *
 * <p><b>Langfuse score push (infrastructure audit fix).</b> Every judged
 * sample - not just flagged ones - now also pushes a Langfuse BOOLEAN score
 * ({@code llm_quality_ok}) via {@link
 * com.agenticai.core.chat.langfuse.LangfuseScoreClient}, attached to the
 * exact trace the sampled turn produced (looked up by session id via {@link
 * com.agenticai.core.chat.LlmCallLogService#mostRecentTraceIdForSession} -
 * this service has no trace id of its own to thread through, since it only
 * ever receives {@code agentId}/{@code sessionId}/text, never the advisor
 * context {@code LlmMetricsAdvisor} captured its span from). This closes the
 * "sampled LLM-as-judge verdicts never write back to Langfuse" gap alongside
 * the admin Issues portal recording that already existed for FLAGged samples.
 */
@Service
public class LlmQualityJudgeService {

    private static final Logger log = LoggerFactory.getLogger(LlmQualityJudgeService.class);

    /** Registry key of the judge's system prompt (default text: {@code prompts/en/judge.quality.md}). */
    static final String PROMPT_KEY = "judge.quality";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of {@link #PROMPT_KEY} applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private volatile JudgeSampleRate sampleRates;

    /** The run-time override of the sample rate (platform setting); absent = {@code app.llm.judge.sample-rate} as is. */
    @Autowired(required = false)
    void useSampleRate(JudgeSampleRate rates) {
        this.sampleRates = rates;
    }

    /** The fraction of turns sampled right now: the platform setting if an admin set one, else the configured rate. */
    double effectiveSampleRate() {
        JudgeSampleRate rates = sampleRates;
        return rates == null ? sampleRate : rates.current(sampleRate);
    }

    private final ObjectProvider<PlatformIssueService> issueService;
    private final LlmCallLogService llmCallLogService;
    private final LangfuseScoreClient langfuseScoreClient;
    private final double sampleRate;
    private final int maxExcerptLength;
    /** Shared bounded virtual-thread executor (JAVA-008) - was the common ForkJoinPool. */
    private final java.util.concurrent.Executor asyncExecutor;

    public LlmQualityJudgeService(
        ObjectProvider<PlatformIssueService> issueService,
        LlmCallLogService llmCallLogService,
        LangfuseScoreClient langfuseScoreClient,
        @Value("${app.llm.judge.sample-rate:0.05}") double sampleRate,
        @Value("${app.llm.judge.max-excerpt-length:600}") int maxExcerptLength,
        @org.springframework.beans.factory.annotation.Qualifier(com.agenticai.core.security.concurrent.PlatformExecutorConfiguration.PLATFORM_EXECUTOR) java.util.concurrent.Executor asyncExecutor
    ) {
        this.asyncExecutor = asyncExecutor;
        this.issueService = issueService;
        this.llmCallLogService = llmCallLogService;
        this.langfuseScoreClient = langfuseScoreClient;
        this.sampleRate = sampleRate;
        this.maxExcerptLength = maxExcerptLength;
    }

    /**
     * Fire-and-forget: rolls the sampling dice for this one turn, and if it
     * lands, spawns a background judge call. Always returns immediately -
     * never delays or affects the caller in any way, including when nothing
     * gets sampled at all (the common case at a 5% default rate).
     */
    public void sampleAsync(ChatClient chatClient, UUID agentId, String sessionId, String userMessage, String answer) {
        if (chatClient == null || userMessage == null || userMessage.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        PlatformIssueService service = issueService.getIfAvailable();
        if (service == null) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= effectiveSampleRate()) {
            return;
        }

        // Tenant context + MDC travel with the task and are restored after it.
        CompletableFuture.runAsync(com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(() -> {
            try {
                judgeAndMaybeRecord(service, chatClient, agentId, sessionId, userMessage, answer);
            } catch (Exception e) {
                log.warn(
                    "Sampled LLM-as-judge quality check failed for agent {} session {} - dropping this sample",
                    agentId, sessionId, e
                );
            }
        }), asyncExecutor);
    }

    private void judgeAndMaybeRecord(
        PlatformIssueService service, ChatClient chatClient, UUID agentId, String sessionId, String userMessage, String answer
    ) {
        String verdict = chatClient.prompt()
            .system(prompts.text(PROMPT_KEY, agentId))
            .user("User's message: " + truncate(userMessage) + "\n\nAssistant's response: " + truncate(answer))
            .call()
            .content();

        if (verdict == null || verdict.isBlank()) {
            return;
        }
        String trimmed = verdict.trim();
        // Anything that isn't a clean "FLAG:" line - including "OK", a
        // malformed response, or the model ignoring the format entirely -
        // fails open (not flagged). This deliberately mirrors ReflexionService's
        // "ambiguous verdict means don't act" discipline: a sampled quality
        // signal producing false positives on parse noise would erode trust
        // in the Issues portal faster than an occasional missed real issue does.
        boolean flagged = trimmed.regionMatches(true, 0, "FLAG", 0, "FLAG".length());
        String reason = flagged && trimmed.length() > "FLAG:".length()
            ? trimmed.substring(trimmed.indexOf(':') + 1).trim()
            : (flagged ? "the quality judge flagged this response without a specific reason" : null);

        // Infrastructure audit fix - push every judged sample (not just
        // flagged ones) to Langfuse as a BOOLEAN score, so the same signal
        // that already feeds the admin Issues portal on a FLAG is also
        // visible/filterable in Langfuse's own UI, attached to the exact
        // trace this turn produced (looked up by session id - see
        // LlmCallLogService#mostRecentTraceIdForSession's javadoc for why a
        // lookup, not a threaded-through parameter, is how this call gets
        // the trace id). A missing trace id (tracing off, or no
        // llm_call_log row yet) falls back to a session-scoped score -
        // LangfuseScoreClient accepts either. No id passed: each sampled
        // judge call is a genuinely new, one-off event, unlike RL's
        // recomputed-per-episode reward (see RewardService).
        String traceId = llmCallLogService.mostRecentTraceIdForSession(sessionId);
        langfuseScoreClient.pushScore(
            null, traceId, sessionId, "llm_quality_ok", LangfuseScoreClient.ScoreDataType.BOOLEAN,
            flagged ? 0 : 1, reason
        );

        if (!flagged) {
            return;
        }

        String message = "Judge reason: " + reason
            + "\n\nUser message: " + truncate(userMessage)
            + "\n\nAssistant response: " + truncate(answer)
            + (sessionId == null ? "" : "\n\nSession: " + sessionId);

        // sourceId + title both carry agentId (redundant with each other,
        // same shape LoggingToolCallback's TOOL_CALL recording already
        // uses) - title stays fixed per agent so repeat flags on the same
        // agent dedupe onto one recurring PlatformIssue row instead of
        // spawning a new one per sampled call (see PlatformIssueService's
        // fingerprint javadoc); the varying "reason" only ever lives in the
        // message body, which recordRecurrence overwrites on each hit.
        service.record(
            IssueSource.LLM_QUALITY,
            agentId == null ? null : agentId.toString(),
            IssueSeverity.WARNING,
            "Sampled quality check flagged agent " + (agentId == null ? "(unknown)" : agentId) + "'s responses",
            message
        );
    }

    private String truncate(String text) {
        return text.length() <= maxExcerptLength ? text : text.substring(0, maxExcerptLength) + "...";
    }
}
