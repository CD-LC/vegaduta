package com.agenticai.core.common;

import com.agenticai.core.audit.IssueCategory;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.common.diagnostics.SensitiveDataMasker;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Diagnostics build-out (2026-08), phase 2 - layers LLM-based root-cause analysis and
 * notification-bell fan-out on top of {@link DiagnosticService}'s synchronous, rule-based
 * re-categorization. {@code DiagnosticService} already turns a {@link
 * com.agenticai.core.guardrails.GuardrailViolationException}/{@link
 * com.agenticai.core.chat.UpstreamProviderException} into a per-tool/per-provider {@code
 * PlatformIssue} row with no LLM involved - this class enriches that SAME row after the fact
 * (never a second row) with a plain-English category + explanation, and, when warranted,
 * pushes a tenant notification pointing at it.
 *
 * <p>Lives here (core/app, alongside {@code GlobalExceptionHandler} and {@code
 * DiagnosticService}, its two callers) for the same reason {@code DiagnosticService}'s own
 * javadoc documents: core/audit is a leaf module ("no dependency on any other core module")
 * and core/guardrails already depends on core/audit, so this class - needing BOTH {@link
 * PlatformIssueService} (core/audit) and an LLM-calling {@link ChatClient} - cannot live in
 * core/audit without creating a circular Maven reactor dependency. core/app already compiles
 * against core/audit, core/chat, and core/guardrails (it is the top-level composition module),
 * so this is the lowest-friction legal home - and it is also where {@link NotificationService}'s
 * first real producer naturally lands, since core/app already compiles against core/audit.
 *
 * <p><b>Async mechanism</b>: no {@code @Async}/executor bean - a plain {@link
 * CompletableFuture#runAsync} (default {@code ForkJoinPool.commonPool()}), following the exact
 * precedent {@code LlmQualityJudgeService#sampleAsync} (core/agents) already established,
 * including the same cross-thread {@link TenantContext} snapshot/restore idiom: {@code
 * platform_issue}/{@code notification} are both tenant-scoped-by-schema tables (no tenant_id
 * column, isolation via {@code SchemaMultiTenantConnectionProvider} reading {@code
 * TenantContext} per-request) and a background thread has no {@code TenantContext} of its own
 * unless it is explicitly propagated from the calling (request) thread before the async hop.
 *
 * <p><b>Redaction</b>: {@code rawContext} is NEVER sent to the LLM (or persisted) unredacted -
 * see {@link #redact(String)}. Only the LLM's own output text (category + explanation) is ever
 * stored, via {@link PlatformIssueService#attachDiagnosis}/{@link NotificationService#create}.
 *
 * <p><b>Fail-open, always</b>: a null {@code chatClient} skips analysis entirely and silently
 * (the "reuse, don't re-derive" contract - see {@link #analyzeAsync}'s javadoc); any exception,
 * blank response, or unparseable verdict from the LLM call leaves the issue's category/message
 * exactly as {@code DiagnosticService} already left it - this is strictly an enrichment step,
 * never a second failure on top of the original one being diagnosed.
 */
@Service
public class AiDiagnosticAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosticAnalysisService.class);

    /**
     * Same rationale and same value as {@code SemanticGuardrailService.MAX_JUDGE_INPUT_CHARS}
     * (core/guardrails): caps how much of the (already redacted) raw context is embedded into
     * the analysis prompt, so an oversized tool/provider error body can't blow this call's
     * cost/latency budget or push the system prompt itself out of the model's effective
     * attention window.
     */
    private static final int MAX_ANALYSIS_INPUT_CHARS = 4000;

    private static final Set<IssueCategory> SYSTEM_DETECTED_CATEGORIES = EnumSet.of(
        IssueCategory.TOOL_FAILURE, IssueCategory.SAFETY, IssueCategory.SYSTEM,
        IssueCategory.WORKFLOW, IssueCategory.BILLING
    );

    // Bearer-token shape isn't covered by SensitiveDataMasker's own pattern set (that masker
    // targets bare key=value/connection-string/PII shapes, not an HTTP header's "Scheme <token>"
    // form) - kept here as a narrow pre-pass, then SensitiveDataMasker.mask() below does
    // everything else (the 7 copied guardrail secret patterns, generic key=value, connection
    // strings, email, phone, IP, credit-card-shaped digits, AND stack-trace/filesystem-path
    // collapsing that this class's own redact() never covered).
    private static final Pattern BEARER_TOKEN = Pattern.compile("Bearer\\s+[A-Za-z0-9._-]{10,}");

    /** Registry key of the triage system prompt ({@code prompts/en/diagnostics.failure_triage.md}). */
    public static final String ANALYSIS_PROMPT_KEY = "diagnostics.failure_triage";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so an owner can reword the triage instruction; absent = the shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final PlatformIssueService platformIssueService;
    private final NotificationService notificationService;
    private final SensitiveDataMasker sensitiveDataMasker;
    /** Shared bounded virtual-thread executor (JAVA-008) - was the common ForkJoinPool. */
    private final java.util.concurrent.Executor asyncExecutor;

    public AiDiagnosticAnalysisService(
        PlatformIssueService platformIssueService,
        NotificationService notificationService,
        SensitiveDataMasker sensitiveDataMasker,
        @org.springframework.beans.factory.annotation.Qualifier(com.agenticai.core.security.concurrent.PlatformExecutorConfiguration.PLATFORM_EXECUTOR) java.util.concurrent.Executor asyncExecutor
    ) {
        this.asyncExecutor = asyncExecutor;
        this.platformIssueService = platformIssueService;
        this.notificationService = notificationService;
        this.sensitiveDataMasker = sensitiveDataMasker;
    }

    /**
     * Fire-and-forget single entry point. Always returns immediately, including when analysis is
     * skipped outright ({@code chatClient == null}) or ultimately fails/comes back unparseable -
     * see this class's own javadoc for the full fail-open posture.
     *
     * @param chatClient nullable - the calling turn's already-resolved {@link ChatClient} (same
     *     "reuse, don't re-derive" rule {@code LlmQualityJudgeService}/{@code
     *     SemanticGuardrailService} already apply). {@code null} means skip silently: no analysis
     *     is attempted, no background task is even spawned.
     * @param issueId the {@code PlatformIssue} row the caller's synchronous {@code
     *     DiagnosticService}/{@code PlatformIssueService.record(...)} call just created or bumped.
     * @param source {@code GUARDRAIL_FALSE_POSITIVE} or {@code UPSTREAM_PROVIDER_FAILURE} today -
     *     generic enough that {@code TOOL_CALL}/{@code WORKFLOW_RUN} are one more call site away.
     * @param sourceId the tool name / provider name the caller already has - used only to build
     *     the prompt and the notification title, never re-derived.
     * @param rawContext the tool's raw output / exception message, UNREDACTED - redaction happens
     *     inside this method (see {@link #redact(String)}), never at the call site.
     * @param notificationRecipientUserId nullable - {@code null} broadcasts to every
     *     tenant-admin in this tenant schema, matching {@link NotificationService#create}'s own
     *     "null means broadcast" convention.
     */
    public void analyzeAsync(
        ChatClient chatClient,
        UUID issueId,
        IssueSource source,
        String sourceId,
        String rawContext,
        String notificationRecipientUserId
    ) {
        if (chatClient == null) {
            return;
        }

        // Captured on the calling (correct) thread, BEFORE the async hop - not a new pattern,
        // see LlmQualityJudgeService#sampleAsync's identical idiom and javadoc.
        // (Tenant context + MDC are carried by the propagating wrapper.)
        CompletableFuture.runAsync(com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(() -> {
            try {
                doAnalysis(chatClient, issueId, source, sourceId, rawContext, notificationRecipientUserId);
            } catch (Exception e) {
                log.warn("AI diagnostic analysis failed for issue {} - leaving it uncategorized", issueId, e);
            }
        }), asyncExecutor);
    }

    private void doAnalysis(
        ChatClient chatClient,
        UUID issueId,
        IssueSource source,
        String sourceId,
        String rawContext,
        String notificationRecipientUserId
    ) {
        String redactedExcerpt = truncate(redact(rawContext));

        String content = chatClient.prompt()
            .system(prompts.text(ANALYSIS_PROMPT_KEY))
            .user("Source: " + source + " (" + sourceId + ")\n\nRedacted excerpt:\n" + redactedExcerpt)
            .call()
            .content();

        if (content == null || content.isBlank()) {
            log.warn("AI diagnostic analysis returned a blank response for issue {} - leaving it uncategorized", issueId);
            return;
        }

        ParsedVerdict parsed = parse(content);
        if (parsed == null) {
            log.warn(
                "AI diagnostic analysis returned an unparseable response for issue {} - leaving it uncategorized: {}",
                issueId, content
            );
            return;
        }

        // Enriches the SAME PlatformIssue row DiagnosticService already created - never a second row.
        platformIssueService.attachDiagnosis(issueId, parsed.category(), parsed.explanation());

        // IssueCategory's four system-detected values are an exact token-for-token match with
        // NotificationCategory's own value set by design (see IssueCategory's javadoc), so this
        // mapping is a trivial identity conversion, never a lookup table that can drift out of sync.
        NotificationCategory notificationCategory = NotificationCategory.valueOf(parsed.category().name());
        notificationService.create(
            notificationRecipientUserId,
            notificationCategory,
            buildTitle(source, sourceId),
            parsed.explanation(),
            severityFor(source),
            null, // link - no tenant-facing issue-detail page exists yet to deep-link into; never fabricate a dead route.
            issueId
        );
    }

    /**
     * {@link com.agenticai.core.audit.IssueSeverity} isn't threaded through {@link
     * #analyzeAsync}'s entry point - the caller already recorded it at {@code
     * PlatformIssueService.record} time. This mirrors {@code DiagnosticService}'s own
     * source-to-severity mapping (WARNING for a guardrail false positive, ERROR for an upstream
     * provider outage) rather than re-deriving severity from the LLM's own judgement, which only
     * ever classifies category/explanation, never severity. {@code NotificationSeverity.INFO} is
     * never produced by this path - reserved for future non-issue-backed notifications.
     */
    private NotificationSeverity severityFor(IssueSource source) {
        return source == IssueSource.UPSTREAM_PROVIDER_FAILURE ? NotificationSeverity.ERROR : NotificationSeverity.WARNING;
    }

    private String buildTitle(IssueSource source, String sourceId) {
        return switch (source) {
            case GUARDRAIL_FALSE_POSITIVE -> "Guardrail blocked output from tool '" + sourceId + "'";
            case UPSTREAM_PROVIDER_FAILURE -> "Upstream provider failure: " + sourceId;
            default -> source + ": " + sourceId;
        };
    }

    /**
     * Parses the requested exactly-two-line response defensively - same "ambiguous verdict means
     * don't act" discipline as {@code ReflexionService}/{@code LlmQualityJudgeService}: a response
     * with neither line recognizable at all is treated as unparseable (returns {@code null}, the
     * whole analysis is dropped); a recognizable CATEGORY token that isn't one of the five exact
     * values falls back to {@link IssueCategory#SYSTEM} rather than failing the analysis outright.
     */
    private ParsedVerdict parse(String content) {
        String categoryToken = null;
        String explanation = null;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (categoryToken == null && trimmed.regionMatches(true, 0, "CATEGORY:", 0, "CATEGORY:".length())) {
                categoryToken = trimmed.substring("CATEGORY:".length()).trim();
            } else if (explanation == null && trimmed.regionMatches(true, 0, "EXPLANATION:", 0, "EXPLANATION:".length())) {
                explanation = trimmed.substring("EXPLANATION:".length()).trim();
            }
        }
        if (categoryToken == null && explanation == null) {
            return null;
        }

        IssueCategory category = IssueCategory.SYSTEM;
        if (categoryToken != null) {
            try {
                IssueCategory parsedCategory = IssueCategory.valueOf(categoryToken.toUpperCase(Locale.ROOT));
                if (SYSTEM_DETECTED_CATEGORIES.contains(parsedCategory)) {
                    category = parsedCategory;
                }
            } catch (IllegalArgumentException notARecognizedCategory) {
                // Falls back to SYSTEM, set above.
            }
        }

        String finalExplanation = (explanation == null || explanation.isBlank()) ? content.trim() : explanation;
        return new ParsedVerdict(category, finalExplanation);
    }

    private record ParsedVerdict(IssueCategory category, String explanation) {
    }

    /**
     * Never sends anything past this point to the LLM unredacted, and never persists the
     * unredacted value anywhere - {@link #doAnalysis} only ever stores the LLM's own output text,
     * not this raw excerpt itself. Delegates the bulk of redaction to {@link SensitiveDataMasker}
     * (Diagnostics build-out phase 2b) - broader coverage than this class's original narrow
     * pattern set (adds: the 7 patterns copied from {@code PatternBasedGuardrailService},
     * connection-string credentials, phone numbers, IPv4/IPv6, credit-card-shaped digit runs, and
     * stack-trace/filesystem-path collapsing) - only the bearer-token pre-pass stays local, since
     * that HTTP-header shape is outside the masker's own scope.
     */
    private String redact(String text) {
        if (text == null) {
            return "";
        }
        String result = BEARER_TOKEN.matcher(text).replaceAll("Bearer [REDACTED]");
        return sensitiveDataMasker.mask(result);
    }

    private String truncate(String text) {
        if (text.length() <= MAX_ANALYSIS_INPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ANALYSIS_INPUT_CHARS) + " …[truncated]";
    }
}
