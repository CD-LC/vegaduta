package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderModelCatalogEntry;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService.BudgetDecision;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backfills {@link ProviderModelCatalogEntry#getCapabilitySummary()} - one
 * cheap platform-paid LLM call per catalog model, generated once and cached
 * in that column, exactly the enhancement {@link
 * com.agenticai.core.providers.ProviderModelResponse}'s javadoc flagged as a
 * reasonable future step beyond its deterministic template fallback.
 *
 * <p><b>Why this lives in core/chat, not core/providers (where {@link
 * ProviderModelCatalogService} and the rest of the catalog otherwise
 * live).</b> Generating a real blurb needs an LLM {@link ChatClient} - {@link
 * HostedLlmChatClientFactory} and {@link PlatformHostedLlmProviderService}'s
 * pool-selection - and core/chat already depends on core/providers (see
 * {@link ErrorExplanationService}, the same Smart-AI-governed-LLM-call
 * pattern this class follows). The reverse edge (core/providers depending on
 * core/chat) would be a module cycle, so the generation job can't live next
 * to the catalog it updates - it calls back into {@link
 * ProviderModelCatalogService}'s public read/write methods instead.
 *
 * <p><b>Trigger, not a new endpoint.</b> Per the direct instruction this was
 * built against, there is no dedicated "generate summaries" HTTP route.
 * Instead:
 * <ul>
 *   <li>{@code PlatformController.refreshModelCatalogNow()} (the existing
 *   admin "run now" trigger) calls {@link #generateMissingSummaries()}
 *   synchronously right after {@code ProviderModelCatalogService#refreshAll()}
 *   - an admin who just refreshed the catalog sees fresh models get real
 *   summaries immediately, not at the next scheduled window.
 *   <li>{@link #scheduledGenerate()} is this class's own daily companion to
 *   {@code ProviderModelCatalogService#scheduledRefresh()}'s 03:00 UTC cron,
 *   offset five minutes later so that job's replace-not-merge delete/insert
 *   has settled first. It can't be the SAME {@code @Scheduled} method
 *   (module-cycle constraint above), so it's a second cron on the same
 *   cadence instead - idempotent either way, since it only ever touches rows
 *   {@link ProviderModelCatalogService#findModelsMissingCapabilitySummary()}
 *   still reports as ungenerated.
 * </ul>
 *
 * <p><b>Budget/safety.</b> Same Smart AI governor contract as {@link
 * ErrorExplanationService}: gated by {@link #FEATURE_KEY}'s budget before
 * every call, metered after, runs on the platform-hosted pool's top-priority
 * row (never a tenant's BYOK key), and a blocked/failed/empty generation is
 * never an error - the row's {@code capabilitySummary} just stays null and
 * {@code ProviderModelResponse.from()}'s deterministic template covers it
 * until a later pass succeeds. {@link #generateMissingSummaries()} never
 * throws; one model's generation failing does not stop the rest of the batch.
 */
@Component
public class ModelCapabilitySummaryGenerationService {

    /** Smart AI feature key - admin-tunable via {@code platform.smart-ai.feature.model-capability-summary.*}. */
    public static final String FEATURE_KEY = "model-capability-summary";

    /** Conservative flat estimate when a response carries no usable token usage - see {@link LlmPricingCatalog}. */
    static final BigDecimal FLAT_SPEND_ESTIMATE_USD = new BigDecimal("0.001");

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilitySummaryGenerationService.class);

    private static final int MAX_OUTPUT_TOKENS = 100;
    private static final double TEMPERATURE = 0.3;
    private static final double TOP_P = 1.0;
    /** A "1-2 sentence" blurb should never need more than this - truncated, not rejected, past it. */
    private static final int MAX_SUMMARY_CHARS = 320;

    private static final String SYSTEM_PROMPT = """
        You write short "what can this model do" blurbs for an AI platform's model \
        picker, aimed at a non-technical user deciding which model to use. You are \
        given a model's id, display name, context window size, and which extra input \
        types (beyond plain text) it accepts. Reply with ONE or TWO short plain-English \
        sentences describing what the model is good for and any practical limits - \
        no marketing language, no made-up benchmarks or capabilities you weren't told \
        about, no JSON, no bullet points, no quotes, prose only.""";

    private static final String JOB_LOCK_NAME = "model-capability-summary";

    private final ProviderModelCatalogService catalogService;
    private final PlatformFeatureBudgetService featureBudget;
    private final PlatformHostedLlmProviderService hostedProviders;
    private final HostedLlmChatClientFactory chatClientFactory;
    private final LlmPricingCatalog pricingCatalog;
    private final PgAdvisoryJobLock jobLock;

    public ModelCapabilitySummaryGenerationService(
        ProviderModelCatalogService catalogService,
        PlatformFeatureBudgetService featureBudget,
        PlatformHostedLlmProviderService hostedProviders,
        HostedLlmChatClientFactory chatClientFactory,
        LlmPricingCatalog pricingCatalog,
        PgAdvisoryJobLock jobLock
    ) {
        this.catalogService = catalogService;
        this.featureBudget = featureBudget;
        this.hostedProviders = hostedProviders;
        this.chatClientFactory = chatClientFactory;
        this.pricingCatalog = pricingCatalog;
        this.jobLock = jobLock;
    }

    /**
     * Daily companion to {@code ProviderModelCatalogService#scheduledRefresh()}
     * - see class javadoc for why this can't be the same cron. Runs under
     * {@link PgAdvisoryJobLock}: N replicas each generating summaries for the
     * same pending rows would each make (and pay for) a platform-funded LLM
     * call per row, so the lock is real spend de-duplication, not just
     * insurance. The admin "run now" path calls {@link
     * #generateMissingSummaries()} directly and stays deliberately unwrapped -
     * an operator's explicit click should never be silently skipped.
     */
    @Scheduled(cron = "0 5 3 * * *", zone = "UTC")
    public void scheduledGenerate() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica generating is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::generateMissingSummaries);
    }

    /**
     * Generates and caches a summary for every catalog row that doesn't have
     * one yet. Safe to call as often as the caller likes - already-generated
     * rows are never re-queried or re-spent-on. Never throws.
     */
    public void generateMissingSummaries() {
        List<ProviderModelCatalogEntry> pending = catalogService.findModelsMissingCapabilitySummary();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Generating capability summaries for {} model catalog row(s)", pending.size());
        for (ProviderModelCatalogEntry entry : pending) {
            try {
                generateOne(entry);
            } catch (RuntimeException e) {
                log.warn("Capability summary generation failed for {}/{} - leaving the deterministic template fallback in place",
                    entry.getProviderName(), entry.getModelId(), e);
            }
        }
    }

    private void generateOne(ProviderModelCatalogEntry entry) {
        BudgetDecision decision = featureBudget.checkBudget(FEATURE_KEY);
        if (!decision.allowed()) {
            log.debug("Capability summary generation skipped ({}) for {}/{} - template fallback applies",
                decision.reason(), entry.getProviderName(), entry.getModelId());
            return;
        }

        HostedProviderSelection selection = hostedProviders.resolveTopPriorityEnabled().orElse(null);
        if (selection == null) {
            log.debug("Capability summary generation skipped - no enabled platform-hosted LLM pool row");
            return;
        }

        ChatResponse response = chatClientFactory.create(
                selection.provider(), selection.decryptedApiKey(), MAX_OUTPUT_TOKENS, TEMPERATURE, TOP_P)
            .prompt()
            .system(SYSTEM_PROMPT)
            .user(describeModel(entry))
            .call()
            .chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }

        Long promptTokens = null;
        Long completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
            completionTokens = usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue();
        }
        BigDecimal estimated = pricingCatalog.estimateCostUsd(selection.provider().getModel(), promptTokens, completionTokens);
        featureBudget.recordSpend(FEATURE_KEY, estimated != null ? estimated : FLAT_SPEND_ESTIMATE_USD);

        String summary = sanitize(response.getResult().getOutput().getText());
        if (summary == null) {
            return;
        }
        catalogService.saveCapabilitySummary(entry.getId(), summary);
    }

    private static String describeModel(ProviderModelCatalogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model id: ").append(entry.getModelId());
        sb.append("\nDisplay name: ").append(entry.getDisplayName());
        sb.append("\nContext window: ").append(
            entry.getContextWindow() != null && entry.getContextWindow() > 0
                ? entry.getContextWindow() + " tokens"
                : "unknown");
        sb.append("\nAccepts image input beyond text: ").append(entry.isSupportsImageInput());
        sb.append("\nAccepts audio input beyond text: ").append(entry.isSupportsAudioInput());
        sb.append("\nAccepts video input beyond text: ").append(entry.isSupportsVideoInput());
        return sb.toString();
    }

    /** Null when unusable (blank); otherwise trimmed and capped. */
    private static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_SUMMARY_CHARS) {
            trimmed = trimmed.substring(0, MAX_SUMMARY_CHARS) + "…";
        }
        return trimmed;
    }
}
