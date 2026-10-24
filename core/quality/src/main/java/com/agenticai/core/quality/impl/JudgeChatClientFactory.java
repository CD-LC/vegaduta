package com.agenticai.core.quality.impl;

import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.quality.QualitySettings;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.AiModelFeature;
import com.agenticai.core.tenancy.AiModelPreference;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared LLM-as-judge {@link ChatClient} resolution for the quality
 * evaluators (Accuracy/Helpfulness/Relevance/Safety/Tone) - mirrors
 * AssistService#buildChatClient / McpStdioSelfHealer#resolveChatClient's
 * "resolve the tenant's active BYOK provider through LlmGatewayService"
 * pattern instead of each evaluator duplicating credential resolution.
 */
@Component
public class JudgeChatClientFactory {

    /**
     * Scoped per-run judge-model override (V343): an eval run triggered with
     * {@code requestedJudgeModel} installs it here for the duration of its
     * case loop ({@link EvalRunService} via {@link #judgeModelScope}), so
     * every evaluator's {@link #buildJudgeClient} call inside that thread
     * judges with the requested model instead of the catalog's first row.
     * ThreadLocal for the same reason {@code TenantContext} is one: the run
     * executes on a dedicated worker thread and nothing else may see the
     * override. NOT validated against the provider catalog - a model the
     * provider cannot serve surfaces as per-case UNAVAILABLE/ERROR results
     * inside the run rather than a trigger-time refusal, matching how a
     * catalog-empty judge resolution already degrades.
     */
    private static final ThreadLocal<String> MODEL_OVERRIDE = new ThreadLocal<>();

    private static final Logger log = LoggerFactory.getLogger(JudgeChatClientFactory.class);

    /** A probe needs one word back; kept tiny so a preflight costs next to nothing. */
    private static final int PROBE_MAX_TOKENS = 16;
    private static final ChatOptions PROBE_OPTIONS = ChatOptions.builder().temperature(0.0).build();

    /**
     * Explicit deployment-wide judge model ({@code MODEL} on the best provider, or {@code PROVIDER/MODEL}
     * when the first segment names a provider). Blank = derive one from what the workspace is running.
     */
    @Value("${app.quality.eval.judge-model:}")
    String configuredJudgeModel = "";

    /** Hard wall-clock limit for one judge call (seconds). */
    @Value("${app.quality.eval.judge-call-timeout-seconds:20}")
    long callTimeoutSeconds = JudgeCallGuard.DEFAULT_CALL_TIMEOUT.toSeconds();

    /** Overall time budget for one judge run (seconds): a calibration or eval run stops spending past it. */
    @Value("${app.quality.eval.judge-budget-seconds:120}")
    long budgetSeconds = JudgeCallGuard.DEFAULT_BUDGET.toSeconds();

    /**
     * The settings catalog ({@code quality.judge.model}, {@code quality.judge.callTimeoutSeconds}): the workspace's own
     * value when it set one, else the two platform fields above. Optional like the other collaborators; a context
     * without a catalog, or a settings failure, gives the platform fields.
     */
    private volatile SettingsResolver settings;

    @Autowired(required = false)
    public void setSettingsResolver(SettingsResolver settingsResolver) {
        this.settings = settingsResolver;
    }

    private final LlmGatewayService llmGatewayService;
    private final ProviderCredentialResolver credentialResolver;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ObjectProvider<ProviderErrorTranslator> errorTranslator;
    private final ObjectProvider<TenantAiModelPreferenceService> tenantModelPreferences;

    @Autowired
    public JudgeChatClientFactory(
        LlmGatewayService llmGatewayService,
        ProviderCredentialResolver credentialResolver,
        ProviderModelCatalogService providerModelCatalogService,
        ObjectProvider<ProviderErrorTranslator> errorTranslator,
        ObjectProvider<TenantAiModelPreferenceService> tenantModelPreferences
    ) {
        this.llmGatewayService = llmGatewayService;
        this.credentialResolver = credentialResolver;
        this.providerModelCatalogService = providerModelCatalogService;
        this.errorTranslator = errorTranslator;
        this.tenantModelPreferences = tenantModelPreferences;
    }

    public JudgeChatClientFactory(
        LlmGatewayService llmGatewayService,
        ProviderCredentialResolver credentialResolver,
        ProviderModelCatalogService providerModelCatalogService,
        ObjectProvider<ProviderErrorTranslator> errorTranslator
    ) {
        this(llmGatewayService, credentialResolver, providerModelCatalogService, errorTranslator, null);
    }

    public JudgeChatClientFactory(
        LlmGatewayService llmGatewayService,
        ProviderCredentialResolver credentialResolver,
        ProviderModelCatalogService providerModelCatalogService
    ) {
        this(llmGatewayService, credentialResolver, providerModelCatalogService, null, null);
    }

    /**
     * Preflight: is the judge this factory would resolve right now able to
     * answer at all? Sends ONE tiny request ("reply OK") and returns empty
     * when it comes back, or the reason it did not.
     *
     * <p>Callers that are about to spend many judge calls (a calibration is
     * two per row, an eval run is several per case per repetition) ask this
     * first and stop with "judge model unavailable: reason" instead of
     * running to the end against a dead judge. Prod 2026-10-05: a calibration
     * made 40 calls to a judge whose credentials the provider refused, then
     * explained the empty result as a labelling problem; an eval run in the
     * same state reported a score built from the one non-LLM dimension.
     *
     * <p>Only an exception counts as unavailable. An odd or empty completion
     * is the per-call paths' business (they already record it per case).
     */
    public Optional<String> probeUnavailableReason() {
        try {
            ChatClient client = buildJudgeClient(PROBE_MAX_TOKENS);
            JudgeCallGuard.callWithTimeout(
                () -> client.prompt(new Prompt("Reply with the single word OK.", PROBE_OPTIONS)).call().content(),
                callTimeout());
            return Optional.empty();
        } catch (Exception e) {
            String reason = describeFailure(e);
            log.warn("Judge preflight failed - judge model unavailable: {}", reason);
            return Optional.of(reason);
        }
    }

    /** The per-call limit, never null or non-positive. */
    public Duration callTimeout() {
        long seconds = callTimeoutSeconds;
        SettingsResolver resolver = settings;
        if (resolver != null) {
            try {
                seconds = resolver.getLong(QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, SettingContext.tenant());
            } catch (RuntimeException e) {
                log.warn("Could not read {}; using the platform value {}: {}", QualitySettings.JUDGE_CALL_TIMEOUT_SECONDS, seconds, e.toString());
            }
        }
        return JudgeCallGuard.orDefault(Duration.ofSeconds(seconds));
    }

    /** The explicit judge model: the workspace's setting, else the platform's {@code app.quality.eval.judge-model}; blank = none. */
    private String configuredJudgeModel() {
        String value = configuredJudgeModel;
        SettingsResolver resolver = settings;
        if (resolver != null) {
            try {
                value = resolver.getString(QualitySettings.JUDGE_MODEL, SettingContext.tenant());
            } catch (RuntimeException e) {
                log.warn("Could not read {}; using the platform value: {}", QualitySettings.JUDGE_MODEL, e.toString());
            }
        }
        return value == null ? "" : value.trim();
    }

    /** The overall time budget for a judge run, never null or non-positive. */
    public Duration budget() {
        return budgetSeconds <= 0 ? JudgeCallGuard.DEFAULT_BUDGET : Duration.ofSeconds(budgetSeconds);
    }

    /**
     * The plain-language reason for a judge failure: the translator's consumer message when it has one,
     * else a timeout / HTTP status / message line ({@link JudgeCallGuard#describe}).
     */
    public String describeFailure(Throwable e) {
        ProviderErrorTranslator translator = errorTranslator == null ? null : errorTranslator.getIfAvailable();
        if (e instanceof JudgeCallGuard.JudgeCallFailedException) {
            return e.getMessage();
        }
        if (e instanceof NoActiveProviderException
            || e instanceof com.agenticai.core.providers.ProviderModelCatalogEmptyException) {
            return e.getMessage();
        }
        if (translator != null && e instanceof Exception ex) {
            try {
                UpstreamProviderException translated = translator.translate(ex);
                if (translated.getConsumerMessage() != null && !translated.getConsumerMessage().isBlank()) {
                    return translated.getConsumerMessage();
                }
            } catch (RuntimeException ignored) {
                // fall through to the raw message
            }
        }
        return JudgeCallGuard.describe(e);
    }

    /**
     * Which provider, key and model judge right now, and WHY - in priority order:
     * <ol>
     *   <li>the model an eval run asked for ({@link #judgeModelScope});</li>
     *   <li>the workspace's {@code quality.judge.model} setting, else the deployment's explicit
     *       {@code app.quality.eval.judge-model};</li>
     *   <li>the model of this workspace's most recent SUCCESSFUL call on its best-ranked provider
     *       ({@link ProviderCredentialResolver#rankActiveProviders}, read from llm_call_log) - a model
     *       known to answer, not an arbitrary catalog row;</li>
     *   <li>that provider's catalog default.</li>
     * </ol>
     * Prod 2026-10-05: the catalog's FIRST row for OpenRouter ({@code aion-labs/aion-2.0}) judged every
     * calibration row and returned nothing the verdict parser could read, on a workspace whose
     * {@code deepseek/deepseek-chat} was answering fine.
     */
    public JudgeChoice resolveJudge() {
        ProviderCredentialResolver.ActiveProvider active = credentialResolver.resolveAnyActiveKey();
        ProviderName provider = active.providerName();
        String apiKey = active.apiKey();
        String override = MODEL_OVERRIDE.get();
        if (override != null && !override.isBlank()) {
            return new JudgeChoice(provider, apiKey, override, "judge model requested for this run");
        }
        JudgeChoice tenantChoice = tenantConfiguredJudge();
        if (tenantChoice != null) {
            return tenantChoice;
        }
        String configured = configuredJudgeModel();
        if (!configured.isEmpty()) {
            int slash = configured.indexOf('/');
            if (slash > 0) {
                try {
                    ProviderName named = ProviderName.valueOf(configured.substring(0, slash));
                    return new JudgeChoice(named, credentialResolver.resolveActiveKey(named),
                        configured.substring(slash + 1), "configured judge model (app.quality.eval.judge-model)");
                } catch (IllegalArgumentException notAProviderPrefix) {
                    // the whole string is a model id (OpenRouter ids contain '/')
                }
            }
            return new JudgeChoice(provider, apiKey, configured, "configured judge model (app.quality.eval.judge-model)");
        }
        String lastWorking = null;
        try {
            for (ProviderCredentialResolver.RankedProvider ranked : credentialResolver.rankActiveProviders()) {
                if (ranked.providerName() == provider) {
                    lastWorking = ranked.lastWorkingModel();
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the workspace's call history for the judge model ({})", e.toString());
        }
        if (lastWorking != null && !lastWorking.isBlank()) {
            return new JudgeChoice(provider, apiKey, lastWorking,
                "model of this workspace's most recent successful " + provider + " call");
        }
        return new JudgeChoice(provider, apiKey, catalogModelFor(provider), "provider default model");
    }

    /**
     * The workspace's own "AI Model Control -> Evals / LLM-as-judge" choice (falls back to its Default
     * row), when that provider has an ACTIVE key here. Null otherwise - a stale preference for a
     * provider the workspace no longer has must not break judging.
     */
    private JudgeChoice tenantConfiguredJudge() {
        TenantAiModelPreferenceService prefs = tenantModelPreferences == null ? null : tenantModelPreferences.getIfAvailable();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (prefs == null || tenantId == null) {
            return null;
        }
        try {
            Optional<AiModelPreference> pref = prefs.resolve(tenantId, AiModelFeature.EVAL_JUDGE.key());
            if (pref == null || pref.isEmpty() || pref.get().model() == null || pref.get().model().isBlank()) {
                return null;
            }
            ProviderName named = ProviderName.valueOf(pref.get().providerName());
            return new JudgeChoice(named, credentialResolver.resolveActiveKey(named), pref.get().model().trim(),
                "judge model set for this workspace (AI Model Control)");
        } catch (RuntimeException e) {
            log.debug("Workspace judge-model preference not usable ({})", e.toString());
            return null;
        }
    }

    /** The judge resolved by {@link #resolveJudge}; {@code apiKey} is decrypted - never log it. */
    public record JudgeChoice(ProviderName provider, String apiKey, String model, String why) {
        public String id() {
            return provider.name() + "/" + model;
        }
    }

    public ChatClient buildJudgeClient(int maxTokens) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        JudgeChoice judge = resolveJudge();
        log.debug("Judge: {} ({})", judge.id(), judge.why());
        return llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            judge.provider(), judge.apiKey(), judge.model(), maxTokens, 0.0, 1.0
        ));
    }

    /**
     * PROVENANCE ONLY (eval-datasets workstream): the model id this factory
     * would resolve right now, recorded onto {@code eval_run.judge_model} so
     * a run-over-run score diff can see when the judge itself changed.
     * Needed because {@link #resolveModelFor} takes the FIRST catalog row -
     * a catalog reorder silently swaps the judge without anything else
     * changing. Honest caveat: this re-resolves at call time, so a catalog
     * edit BETWEEN this call and the per-case {@link #buildJudgeClient}
     * calls inside one run could still drift; recording the trigger-time
     * answer makes the common case visible without adding a pin-the-judge
     * override this module does not otherwise have (see the scout note in
     * the eval-datasets migration/run javadoc). Returns {@code null} rather
     * than throwing when no provider/catalog is available - the run itself
     * will surface that as UNAVAILABLE judge results; provenance must not be
     * the thing that kills it first.
     */
    public String resolveJudgeModelIdOrNull() {
        try {
            JudgeChoice judge = resolveJudge();
            log.info("Judge model {} chosen: {}", judge.id(), judge.why());
            return judge.id();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Installs {@code modelOverride} as the judge model for the CURRENT
     * thread until the returned scope is closed. Null/blank installs nothing
     * and returns a no-op scope - callers need not branch. Always used
     * try-with-resources by {@code EvalRunService} around its case loop.
     */
    public AutoCloseable judgeModelScope(String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) {
            return () -> { };
        }
        MODEL_OVERRIDE.set(modelOverride.trim());
        return MODEL_OVERRIDE::remove;
    }

    private String catalogModelFor(ProviderName provider) {
        // The catalog's preferred default (cost-ranked / vetted pick), not its alphabetical first row.
        Optional<String> preferred = providerModelCatalogService.defaultModelFor(provider);
        if (preferred != null && preferred.isPresent()) {
            return preferred.get();
        }
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(provider);
        if (models == null || models.isEmpty()) {
            throw new com.agenticai.core.providers.ProviderModelCatalogEmptyException(provider);
        }
        return models.get(0).modelId();
    }
}
