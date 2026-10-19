package com.agenticai.core.platform;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.providers.HostedLlmPoolSettingsResponse;
import com.agenticai.core.providers.HostedLlmPoolSettingsUpdateRequest;
import com.agenticai.core.providers.PlatformHostedLlmProviderResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderUpsertRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform-hosted LLM half of {@link PlatformController}'s {@code /api/platform} surface:
 * platform LLM task routing, the per-user dollar budget, the hosted-pool token quota, chat
 * generation knobs, the Smart AI budget, and CRUD plus the master switch over the hosted provider
 * pool.
 *
 * <p>Deliberately a superclass of the controller rather than a second {@code @RestController}:
 * Spring MVC and {@code OpenApiSpecGenerator} both collect handler methods across the class
 * hierarchy, so every endpoint here keeps its path, its {@code PlatformController_*} operation id
 * and its place in the generated SDKs' {@code PlatformApi}. Not annotated and not a bean on its
 * own.
 */
public abstract class PlatformLlmControlEndpoints {

    private final PlatformLlmTaskService platformLlmTaskService;
    final PlatformHostedLlmProviderService platformHostedLlmProviderService;
    final PlatformSettingsService platformSettingsService;
    // PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 (Budgets & Limits, Phase P1).
    private final PerUserBudgetService perUserBudgetService;
    // Smart AI layer master switch + per-feature dollar budgets (2026-07-29).
    private final PlatformFeatureBudgetService smartAiBudgetService;
    // 2026-08-09 - the platform-hosted-pool TOKEN quota (separate gate from
    // perUserBudgetService's DOLLAR budget above). Was fully admin-tunable in
    // code since 2026-08-08 with nothing ever calling it - see #getFreeTierQuota.
    final FreeTierUsageLimiterService freeTierUsageLimiterService;

    PlatformLlmControlEndpoints(
            PlatformLlmTaskService platformLlmTaskService,
            PlatformHostedLlmProviderService platformHostedLlmProviderService,
            PlatformSettingsService platformSettingsService,
            PerUserBudgetService perUserBudgetService,
            PlatformFeatureBudgetService smartAiBudgetService,
            FreeTierUsageLimiterService freeTierUsageLimiterService) {
        this.platformLlmTaskService = platformLlmTaskService;
        this.platformHostedLlmProviderService = platformHostedLlmProviderService;
        this.platformSettingsService = platformSettingsService;
        this.perUserBudgetService = perUserBudgetService;
        this.smartAiBudgetService = smartAiBudgetService;
        this.freeTierUsageLimiterService = freeTierUsageLimiterService;
    }

    @GetMapping("/llm-tasks")
    public List<PlatformLlmTaskConfigResponse> listLlmTasks() {
        return platformLlmTaskService.list();
    }

    @PutMapping("/llm-tasks/{task}")
    public PlatformLlmTaskConfigResponse upsertLlmTask(
            @PathVariable PlatformTask task,
            @Valid @RequestBody PlatformLlmTaskUpsertRequest request) {
        return platformLlmTaskService.upsert(
                task, request.providerName(), request.model(), request.apiKey());
    }

    /**
     * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 (Budgets &amp; Limits, Phase P1) - the admin
     * surface for the platform's per-USER daily dollar budget on platform-hosted LLM usage. GET
     * returns the current effective global config (amount, window, per-modality split, enabled
     * flag, and the fallback per-1k-token price for hosted models the pricing catalog can't price);
     * PUT upserts any subset of those. Backed by {@code platform_setting} rows (see {@link
     * PerUserBudgetService}), so a change is live on the next chat turn with no redeploy - same
     * runtime-config posture as {@link PlatformController#updateSetting}, exposed here as one typed object rather
     * than several raw string keys the way {@link #upsertLlmTask} groups a task's
     * provider/model/key together.
     */
    @GetMapping("/per-user-budget")
    public PerUserBudgetService.BudgetConfig getPerUserBudget() {
        return perUserBudgetService.currentGlobalConfig();
    }

    @PutMapping("/per-user-budget")
    public PerUserBudgetService.BudgetConfig updatePerUserBudget(
            @RequestBody PerUserBudgetConfigUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return perUserBudgetService.updateGlobalConfig(
                request.enabled(),
                request.dailyUsd(),
                request.windowSeconds(),
                request.resolveSplitPct(),
                request.fallbackUsdPer1kTokens(),
                updatedBy);
    }

    /**
     * The admin surface for {@link FreeTierUsageLimiterService}'s platform-hosted-pool TOKEN quota
     * (base FREE-tier rolling-30-day limit plus the PREMIUM/UNLIMITED multipliers - default 400,000
     * &times; 3 = 1,200,000 tokens/month for UNLIMITED). Independent from {@link
     * #getPerUserBudget}/{@link #updatePerUserBudget} right above, which gates on DOLLARS spent,
     * not raw token count - raising one does not raise the other, a support-desk confusion point
     * that prompted this endpoint's addition. Backed by {@code platform_setting} rows (see {@code
     * FreeTierUsageLimiterService#KEY_MONTHLY_TOKEN_LIMIT} etc.), so a change is live on the next
     * chat turn with no redeploy, same posture as {@link #updatePerUserBudget}.
     */
    @GetMapping("/free-tier-quota")
    public FreeTierUsageLimiterService.QuotaConfig getFreeTierQuota() {
        return freeTierUsageLimiterService.currentQuotaConfig();
    }

    @PutMapping("/free-tier-quota")
    public FreeTierUsageLimiterService.QuotaConfig updateFreeTierQuota(
            @RequestBody FreeTierQuotaConfigUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return freeTierUsageLimiterService.updateQuotaConfig(
                request.monthlyTokenLimit(),
                request.premiumMultiplier(),
                request.unlimitedMultiplier(),
                updatedBy);
    }

    // platform.chat.* history-window keys (2026-08-12): referenced straight
    // off SessionMemoryService - the consumer - so the write side and the
    // read side can never drift (this module already depends on core/agents;
    // the effort key/allowed-set constants come from FreeTierChatClientService
    // the same way). Only the fresh-DB display defaults are restated locally:
    // SessionMemoryService's own fallbacks are @Value instance fields, not
    // constants, so there is nothing to reference for those two numbers.
    private static final String KEY_CHAT_HISTORY_MAX_TURNS =
            SessionMemoryService.KEY_HISTORY_MAX_TURNS;
    private static final String KEY_CHAT_HISTORY_MAX_CHARS =
            SessionMemoryService.KEY_HISTORY_MAX_CHARS;
    private static final int DEFAULT_CHAT_HISTORY_MAX_TURNS = 40;
    private static final int DEFAULT_CHAT_HISTORY_MAX_CHARS = 32000;

    /**
     * 2026-08-12 - the admin surface for the platform-wide chat GENERATION knobs: the hosted pool's
     * Gemini thinking effort (the silent thinking-token spend fix - see {@link
     * FreeTierChatClientService#KEY_GEMINI_REASONING_EFFORT}'s javadoc) and the
     * conversation-history replay window. Product owner explicitly wants these admin-selectable,
     * not hardcoded. Backed by {@code platform_setting} rows, so a change is live on the next chat
     * turn with no redeploy - same posture, style and partial-update contract as {@link
     * #getFreeTierQuota}/{@link #updateFreeTierQuota} right above.
     */
    @GetMapping("/chat-generation-settings")
    public ChatGenerationSettingsResponse getChatGenerationSettings() {
        return currentChatGenerationSettings();
    }

    @PutMapping("/chat-generation-settings")
    public ChatGenerationSettingsResponse updateChatGenerationSettings(
            @RequestBody ChatGenerationSettingsUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (request.geminiReasoningEffort() != null) {
            String effort =
                    request.geminiReasoningEffort().trim().toLowerCase(java.util.Locale.ROOT);
            if (!FreeTierChatClientService.ALLOWED_GEMINI_REASONING_EFFORTS.contains(effort)) {
                throw new IllegalArgumentException(
                        "geminiReasoningEffort must be one of none|low|medium|high, got '"
                                + request.geminiReasoningEffort()
                                + "'");
            }
            platformSettingsService.update(
                    FreeTierChatClientService.KEY_GEMINI_REASONING_EFFORT, effort, updatedBy);
        }
        if (request.historyMaxTurns() != null) {
            // Upper bound is the stored-history hard cap: appendTurn LTRIMs
            // the Redis list to MAX_STORED_ENTRIES, so any larger window
            // would silently behave as that cap anyway - reject it instead
            // of accepting a number that can't mean what the admin typed.
            if (request.historyMaxTurns() < 1
                    || request.historyMaxTurns() > SessionMemoryService.MAX_STORED_ENTRIES) {
                throw new IllegalArgumentException(
                        "historyMaxTurns must be between 1 and "
                                + SessionMemoryService.MAX_STORED_ENTRIES
                                + ", got "
                                + request.historyMaxTurns());
            }
            platformSettingsService.update(
                    KEY_CHAT_HISTORY_MAX_TURNS,
                    Integer.toString(request.historyMaxTurns()),
                    updatedBy);
        }
        if (request.historyMaxChars() != null) {
            if (request.historyMaxChars() < 1000 || request.historyMaxChars() > 200000) {
                throw new IllegalArgumentException(
                        "historyMaxChars must be between 1000 and 200000, got "
                                + request.historyMaxChars());
            }
            platformSettingsService.update(
                    KEY_CHAT_HISTORY_MAX_CHARS,
                    Integer.toString(request.historyMaxChars()),
                    updatedBy);
        }
        return currentChatGenerationSettings();
    }

    private ChatGenerationSettingsResponse currentChatGenerationSettings() {
        return new ChatGenerationSettingsResponse(
                platformSettingsService.getString(
                        FreeTierChatClientService.KEY_GEMINI_REASONING_EFFORT,
                        FreeTierChatClientService.DEFAULT_GEMINI_REASONING_EFFORT),
                platformSettingsService.getInt(
                        KEY_CHAT_HISTORY_MAX_TURNS, DEFAULT_CHAT_HISTORY_MAX_TURNS),
                platformSettingsService.getInt(
                        KEY_CHAT_HISTORY_MAX_CHARS, DEFAULT_CHAT_HISTORY_MAX_CHARS));
    }

    /**
     * The Smart AI layer's master control surface - one boolean that degrades every AI-native
     * platform feature (in-console copilot, AI nudges/digests, error explanations, etc.) to its
     * non-AI behaviour immediately when flipped off, plus the combined daily USD ceiling across all
     * of them. Same runtime-config posture as {@link #updatePerUserBudget}: backed by {@code
     * platform_setting} rows via {@link PlatformFeatureBudgetService}, live on the next call with
     * no redeploy. See {@code /smart-ai/features/{key}} below for a single feature's own
     * switch/cap.
     */
    @GetMapping("/smart-ai")
    public PlatformFeatureBudgetService.GlobalConfig getSmartAiBudget() {
        return smartAiBudgetService.currentGlobalConfig();
    }

    @PutMapping("/smart-ai")
    public PlatformFeatureBudgetService.GlobalConfig updateSmartAiBudget(
            @RequestBody SmartAiBudgetConfigUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return smartAiBudgetService.updateGlobalConfig(
                request.enabled(),
                request.masterDailyUsd(),
                request.windowSeconds(),
                request.defaultFeatureDailyUsd(),
                updatedBy);
    }

    @GetMapping("/smart-ai/features/{key}")
    public PlatformFeatureBudgetService.FeatureConfig getSmartAiFeature(@PathVariable String key) {
        return smartAiBudgetService.currentFeatureConfig(key);
    }

    @PutMapping("/smart-ai/features/{key}")
    public PlatformFeatureBudgetService.FeatureConfig updateSmartAiFeature(
            @PathVariable String key, @RequestBody SmartAiFeatureConfigUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return smartAiBudgetService.updateFeatureConfig(
                key, request.enabled(), request.dailyUsd(), updatedBy);
    }

    /**
     * The "update multiple providers, on run time" ask - CRUD over the platform-hosted LLM pool
     * that FREE-plan tenants draw from (see {@code core.agents.FreeTierChatClientService}).
     * Adding/editing/disabling a Groq/Together/etc. row here takes effect on the very next
     * FREE-tier chat call, no redeploy - {@link
     * PlatformHostedLlmProviderService#resolveTopPriorityEnabled} reads the table fresh every time,
     * nothing is cached at startup. Same "bare DTOs, no ResponseEntity wrapping" style as the rest
     * of this controller; validation/not-found errors are mapped centrally by {@code
     * GlobalExceptionHandler} (see {@code ProviderValidationException} -> 400, {@code
     * PlatformHostedLlmProviderNotFoundException} -> 404).
     */
    @GetMapping("/hosted-llm-providers")
    public List<PlatformHostedLlmProviderResponse> listHostedLlmProviders() {
        return platformHostedLlmProviderService.list();
    }

    @PostMapping("/hosted-llm-providers")
    public PlatformHostedLlmProviderResponse createHostedLlmProvider(
            @Valid @RequestBody PlatformHostedLlmProviderUpsertRequest request) {
        return platformHostedLlmProviderService.create(request);
    }

    @PutMapping("/hosted-llm-providers/{id}")
    public PlatformHostedLlmProviderResponse updateHostedLlmProvider(
            @PathVariable Long id,
            @Valid @RequestBody PlatformHostedLlmProviderUpsertRequest request) {
        return platformHostedLlmProviderService.update(id, request);
    }

    @DeleteMapping("/hosted-llm-providers/{id}")
    public void deleteHostedLlmProvider(@PathVariable Long id) {
        platformHostedLlmProviderService.delete(id);
    }

    /**
     * The master switch (direct feedback: "there should be a control in admin platform, to turn on
     * or off, the platform use for llm") - pauses the WHOLE hosted pool with one flip, without
     * touching any individual {@code hosted-llm-providers} row's own enabled flag or deleting
     * anything. See {@code HostedLlmPoolSettings}'s javadoc for exactly how FREE-tier
     * chat/Ask-AI/AI-workflow-generator all already treat "pool unavailable" (the same {@code
     * FreeTierProviderPoolEmptyException} -> 503 path used when nobody has configured a provider
     * row yet).
     */
    @GetMapping("/hosted-llm-providers/settings")
    public HostedLlmPoolSettingsResponse getHostedLlmPoolSettings() {
        return platformHostedLlmProviderService.getPoolSettings();
    }

    @PutMapping("/hosted-llm-providers/settings")
    public HostedLlmPoolSettingsResponse updateHostedLlmPoolSettings(
            @RequestBody HostedLlmPoolSettingsUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return platformHostedLlmProviderService.setPoolEnabled(request.enabled(), updatedBy);
    }
}
