package com.agenticai.core.providers;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * "In model selection also, there should be a dropdown which lists all available models in that
 * provider, write a pipeline or job like the translator to do this" - direct feedback, July 2026.
 * Before this, {@code Agent.model} was a free-text input for every provider (not just Sarvam -
 * there was no dropdown at all), which is why Sarvam's models never "appeared": nothing did.
 *
 * <p>This is that pipeline. {@link #scheduledRefresh()} runs daily and calls {@link
 * #refreshProvider(ProviderName)} for every implemented provider:
 *
 * <ul>
 *   <li>{@code OPENROUTER} gets a genuine live fetch - OpenRouter's {@code GET /api/v1/models} is
 *       public, unauthenticated, and lists every model it proxies, so this job hits it directly and
 *       caches the result.
 *   <li>{@code ANTHROPIC}/{@code OPENAI}/{@code GROK}/{@code GEMINI}/{@code SARVAM} fall back to a
 *       curated static seed list. Their real models-list endpoints all require a valid API key, and
 *       this job is platform-wide (it runs without any tenant's BYOK credential in scope) - rather
 *       than borrow a random tenant's key for a background job, which would be a
 *       privacy/blast-radius problem, these five providers' catalogs are maintained by hand in
 *       {@link ProviderModelSeedCatalog} and refreshed through the same pipeline as OpenRouter so
 *       the read side ({@link #listModels}) doesn't need per-provider branching.
 * </ul>
 *
 * <p>{@link #listModels(ProviderName)} lazily warms an empty/never-refreshed provider on first call
 * (so a brand-new install isn't empty until the first 03:00 UTC run), then always serves from the
 * cache table - instant, no per-request live API latency, same "background job populates data, UI
 * reads cached results" shape {@code TranslationJobSettingsService} and {@code
 * PlatformHostedLlmProviderService} already establish elsewhere in this module.
 */
@Service
public class ProviderModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelCatalogService.class);
    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    /**
     * Hand-picked "sensible starting model" per provider, consulted first by {@link
     * #defaultModelFor}. Exists because {@code AgentService#resolveDefaultModelFor} used to take
     * {@code listModels(provider).get(0)} - the first ALPHABETICAL entry - which for GROK landed on
     * {@code grok-3}, a model xAI retired on 2026-05-15, so every newly-created Grok agent started
     * broken. Alphabetical order is an accident of the repository query, not a quality signal; this
     * map makes the default an explicit, reviewable choice (generally the fast/cheap tier of each
     * provider, same posture as {@code AUTO_MODEL_PREFERENCE_KEYWORDS} in {@code
     * HostedPoolRequestResolver}). An entry here only wins if it's actually present in the
     * provider's current catalog - a stale pick degrades to the keyword scan below, never to a dead
     * id.
     */
    private static final Map<ProviderName, String> PREFERRED_DEFAULT_MODELS =
            Map.ofEntries(
                    Map.entry(ProviderName.ANTHROPIC, "claude-haiku-4-5-20251001"),
                    Map.entry(ProviderName.OPENAI, "gpt-4o-mini"),
                    Map.entry(ProviderName.GEMINI, "gemini-2.5-flash"),
                    Map.entry(ProviderName.GROK, "grok-4-1-fast-non-reasoning"),
                    Map.entry(ProviderName.SARVAM, "sarvam-30b"),
                    Map.entry(ProviderName.MISTRAL, "mistral-small-latest"),
                    Map.entry(ProviderName.DEEPSEEK, "deepseek-v4-flash"),
                    Map.entry(ProviderName.GROQ, "llama-3.1-8b-instant"),
                    // Meta: the recommended standard model, never a "-contributor" id.
                    Map.entry(ProviderName.META, "muse-spark-1.3"),
                    // The installer's own default local model (local-models/catalog.txt) -
                    // llama3.1:8b left the seed list when the Ollama seed was aligned with
                    // that catalog (Oct 2026).
                    Map.entry(ProviderName.OLLAMA, "qwen3.5:4b"));

    /**
     * Fallback preference when {@link #PREFERRED_DEFAULT_MODELS} has no (present) entry for a
     * provider - first keyword (checked as a case-insensitive substring of the model id) that
     * matches any model wins. Same fast/cheap-first philosophy and mechanics as {@code
     * HostedPoolRequestResolver.AUTO_MODEL_PREFERENCE_KEYWORDS}, and the same "flash-lite before
     * flash" ordering hazard applies: an earlier keyword scans the WHOLE list before a later one is
     * tried.
     */
    private static final List<String> DEFAULT_MODEL_PREFERENCE_KEYWORDS =
            List.of(
                    "flash-lite",
                    "flash",
                    "mini",
                    "haiku",
                    "lite",
                    "8b",
                    "instant",
                    "non-reasoning");

    /**
     * Vendor-retired model id -> current replacement. Runtime self-heal for agents whose persisted
     * {@code model} predates a vendor retirement: {@code AgentService#resolveEffectiveModel}
     * substitutes the replacement at chat time (with a WARN, without rewriting the row) instead of
     * letting the turn die on the upstream 400/404. Keyed by bare model id - unambiguous today
     * because every provider's ids are disjoint (OpenRouter ids carry a {@code vendor/} prefix,
     * Ollama tags use {@code :}).
     *
     * <p>Grok: the whole 3.x/4.0 line was retired 2026-05-15 (grok-3 redirected to grok-4.3 until
     * ~2026-08-15, the rest hard-400 already). DeepSeek: the V3/R1 endpoints gave way to the V4
     * line. Groq: gemma2-9b-it was decommissioned. Keep this map in sync with the
     * ProviderModelSeedCatalog removals and the matching V182 catalog repair migration.
     */
    private static final Map<String, String> RETIRED_MODEL_REPLACEMENTS =
            Map.ofEntries(
                    Map.entry("grok-3", "grok-4.3"),
                    Map.entry("grok-3-mini", "grok-4.20-0309-non-reasoning"),
                    Map.entry("grok-4", "grok-4.5"),
                    Map.entry("grok-4-fast", "grok-4.3"),
                    Map.entry("deepseek-chat", "deepseek-v4-flash"),
                    Map.entry("deepseek-reasoner", "deepseek-v4-pro"),
                    Map.entry("gemma2-9b-it", "llama-3.1-8b-instant"));

    private static final String JOB_LOCK_NAME = "provider-model-catalog-refresh";

    private final ProviderModelCatalogRepository repository;
    private final RestClient restClient;
    private final PgAdvisoryJobLock jobLock;

    public ProviderModelCatalogService(
            ProviderModelCatalogRepository repository,
            RestClient.Builder restClientBuilder,
            PgAdvisoryJobLock jobLock) {
        this.repository = repository;
        this.restClient = restClientBuilder.build();
        this.jobLock = jobLock;
    }

    /**
     * Cost-ordered pick (2026-09-20, {@code control_plane.media_model_cost}) - optional setter
     * injection rather than a constructor parameter so every existing construction site (and slim
     * test context) keeps working; with no service the keyword/preferred logic below is simply the
     * whole answer, exactly as before.
     */
    private com.agenticai.core.providers.modelcost.MediaModelCostService mediaModelCostService;

    /**
     * Runs {@link #refreshProvider}'s delete+insert as one transaction. A template, not
     * {@code @Transactional} on refreshProvider (JAVA-021/022): {@link #refreshAll} and {@link
     * #listModels} call refreshProvider on {@code this}, which bypasses the proxy - so the
     * annotation never gave the "own transaction" its javadoc promised - and the OpenRouter HTTP
     * fetch ran inside the transaction holding a pooled connection. Now the fetch happens first,
     * outside any transaction this class opens, and only the replace is transactional. {@code null}
     * in slim unit tests (no transaction manager): the replace then runs directly.
     */
    private TransactionTemplate replaceTransaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.replaceTransaction =
                transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMediaModelCostService(
            com.agenticai.core.providers.modelcost.MediaModelCostService mediaModelCostService) {
        this.mediaModelCostService = mediaModelCostService;
    }

    /**
     * The cheapest model an operator has listed (and costed) for {@code provider} that is actually
     * present in {@code models}. Runs AHEAD of {@link #pickDefaultModel}'s keyword scan: a listed
     * row is a number someone chose, a keyword hit is a guess from the model's name. Empty whenever
     * the table has no usable row, so callers fall through to the existing logic unchanged. V263
     * seeds exactly the {@link #PREFERRED_DEFAULT_MODELS} picks, so this changes no default on its
     * own.
     */
    public Optional<String> costPreferredModel(
            ProviderName provider, List<ProviderModelResponse> models) {
        if (mediaModelCostService == null
                || provider == null
                || models == null
                || models.isEmpty()) {
            return Optional.empty();
        }
        List<String> available = models.stream().map(ProviderModelResponse::modelId).toList();
        return mediaModelCostService.cheapestAmong(
                com.agenticai.core.providers.modelcost.MediaModality.TEXT,
                provider.name(),
                available);
    }

    /**
     * Daily at 03:00 UTC - low-traffic window, models change slowly enough that this cadence is
     * plenty fresh. Runs under {@link PgAdvisoryJobLock} as belt-and-braces: {@link
     * #refreshProvider} is already replace-not-merge per provider inside its own transaction, so a
     * double-run converges on the same rows - the lock just keeps N replicas from each hitting
     * OpenRouter and racing delete/insert passes at the same instant. The manual "run now" admin
     * trigger ({@link #refreshAll}) stays deliberately unwrapped - an operator's explicit click
     * should never be silently skipped.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void scheduledRefresh() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica refreshing is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::refreshAll);
    }

    /** Manual "run now" - same shape as {@code PlatformController}'s translation-job trigger. */
    public void refreshAll() {
        for (ProviderName provider : ProviderModelSeedCatalog.providers()) {
            refreshProvider(provider);
        }
        refreshProvider(ProviderName.OPENROUTER);
    }

    public void refreshProvider(ProviderName provider) {
        try {
            // Network fetch first - never inside the replace transaction below.
            List<ProviderModelCatalogEntry> fresh =
                    provider == ProviderName.OPENROUTER
                            ? fetchOpenRouterModels()
                            : ProviderModelSeedCatalog.entriesFor(provider);
            if (fresh.isEmpty()) {
                if (provider == ProviderName.OPENROUTER
                        || ProviderModelSeedCatalog.providers().contains(provider)) {
                    log.warn(
                            "Model catalog refresh for {} produced zero models - leaving existing cache untouched",
                            provider);
                } else {
                    // A provider with no platform catalog (LOCAL_OPENAI: the models are whatever
                    // the tenant's own
                    // server loaded) is empty by design; every dropdown open lands here.
                    log.debug("No platform model catalog for {} - nothing to refresh", provider);
                }
                return;
            }
            Runnable replace =
                    () -> {
                        repository.deleteAllByProviderName(provider);
                        repository.saveAll(fresh);
                    };
            if (replaceTransaction == null) {
                replace.run();
            } else {
                // REQUIRED: joins the caller's transaction when there is one
                // (listModels' lazy warm), otherwise opens its own.
                replaceTransaction.executeWithoutResult(status -> replace.run());
            }
            log.info("Refreshed model catalog for {}: {} models", provider, fresh.size());
        } catch (Exception e) {
            log.warn(
                    "Model catalog refresh for {} failed - leaving existing cache untouched",
                    provider,
                    e);
        }
    }

    // Deliberately NOT readOnly=true, even though this method's own work is
    // all reads. The lazy-warm call to refreshProvider(provider) below joins
    // this method's transaction (its TransactionTemplate is REQUIRED), so its
    // DELETE/INSERT run inside the physical transaction this method already
    // opened. Postgres'
    // JDBC driver honors Connection.setReadOnly(true) at the wire level, so
    // when this method WAS readOnly=true and hit a never-refreshed provider
    // (an empty control_plane.provider_model_catalog for that provider -
    // e.g. right after a fresh install/reset, before the 03:00 UTC job has
    // run once), refreshProvider's DELETE failed with "cannot execute DELETE
    // in a read-only transaction," which then aborted the whole transaction
    // and made the subsequent SELECT fail too ("current transaction is
    // aborted"). Every call for that provider 500'd until the scheduled job
    // finally ran. Not read-only avoids the conflict; the common case (cache
    // already warm) still does no writes.
    @Transactional
    public List<ProviderModelResponse> listModels(ProviderName provider) {
        if (repository.countByProviderName(provider) == 0) {
            refreshProvider(provider);
        }
        return repository.findAllByProviderNameOrderByModelIdAsc(provider).stream()
                .map(ProviderModelResponse::from)
                .toList();
    }

    /**
     * Chat file upload gating (Stage 4, {@code AgentChatService}) - "consumers should be able to
     * attach audio and other files if agents model supports those files in chat" (direct feedback).
     * A single agent has already committed to one {@code providerName}/{@code model} pair (unlike
     * {@link #listModels}, which serves the whole per-provider dropdown), so this warms the same
     * lazily-populated cache {@link #listModels} does and simply filters down to the one entry a
     * chat turn cares about. Empty (not an exception) when the model id isn't in the cached catalog
     * - a hand-typed/legacy model string, or a model a vendor removed since the last refresh -
     * callers treat that the same as "no attachment support confirmed," the safe default.
     */
    // Not readOnly=true - same self-invocation/read-only-transaction conflict
    // as listModels above, see that method's comment.
    @Transactional
    public Optional<ProviderModelResponse> findModel(ProviderName provider, String modelId) {
        if (repository.countByProviderName(provider) == 0) {
            refreshProvider(provider);
        }
        return repository
                .findByProviderNameAndModelId(provider, modelId)
                .map(ProviderModelResponse::from);
    }

    /**
     * The model a brand-new agent should start on for {@code provider} - lives here (with the
     * catalog) rather than in {@code AgentService} so "what's a sensible default" and "what models
     * exist" can't drift apart. Empty only when the provider's catalog is empty even after the lazy
     * warm (e.g. a transient refresh failure) - callers keep their own last-resort fallback for
     * that case.
     */
    // Not readOnly=true - same self-invocation/read-only-transaction conflict
    // as listModels above (this delegates to it), see that method's comment.
    @Transactional
    public Optional<String> defaultModelFor(ProviderName provider) {
        List<ProviderModelResponse> models = listModels(provider);
        Optional<String> byCost = costPreferredModel(provider, models);
        return byCost.isPresent()
                ? byCost
                : Optional.ofNullable(pickDefaultModel(provider, models));
    }

    /**
     * Deterministic selection over an already-fetched list: the provider's {@link
     * #PREFERRED_DEFAULT_MODELS} pick if present in the list, else the first {@link
     * #DEFAULT_MODEL_PREFERENCE_KEYWORDS} match, else the first entry. Package-private and
     * side-effect-free so the preference logic is unit-testable without a repository.
     */
    static String pickDefaultModel(ProviderName provider, List<ProviderModelResponse> models) {
        if (models.isEmpty()) {
            return null;
        }
        String preferred = PREFERRED_DEFAULT_MODELS.get(provider);
        if (preferred != null) {
            for (ProviderModelResponse model : models) {
                if (preferred.equals(model.modelId())) {
                    return preferred;
                }
            }
        }
        for (String keyword : DEFAULT_MODEL_PREFERENCE_KEYWORDS) {
            for (ProviderModelResponse model : models) {
                if (model.modelId().toLowerCase(java.util.Locale.ROOT).contains(keyword)) {
                    return model.modelId();
                }
            }
        }
        return models.get(0).modelId();
    }

    /**
     * The current replacement for a vendor-retired model id, if this catalog knows of one - see
     * {@link #RETIRED_MODEL_REPLACEMENTS}. Pure static lookup, no catalog read.
     */
    public Optional<String> replacementForRetiredModel(String modelId) {
        return Optional.ofNullable(modelId).map(RETIRED_MODEL_REPLACEMENTS::get);
    }

    /**
     * Every catalog row still missing an LLM-generated {@code capabilitySummary} - what {@code
     * ModelCapabilitySummaryGenerationService} (core/chat) iterates to backfill it. Lives here
     * rather than there because that service needs an LLM {@code ChatClient} (see {@link
     * HostedLlmChatClientFactory} in core/chat) and core/providers cannot depend on core/chat
     * without creating a module cycle - core/chat already depends on core/providers (see {@code
     * ErrorExplanationService}).
     */
    @Transactional(readOnly = true)
    public List<ProviderModelCatalogEntry> findModelsMissingCapabilitySummary() {
        return repository.findAllByCapabilitySummaryIsNull();
    }

    /**
     * Persists one generated blurb onto its row. Silently a no-op if the row has since disappeared
     * (a provider refresh replaced it out from under the generation job mid-run) - the next
     * generation pass simply sees the new row as still-missing and tries again.
     */
    @Transactional
    public void saveCapabilitySummary(Long catalogEntryId, String capabilitySummary) {
        repository
                .findById(catalogEntryId)
                .ifPresent(
                        entry -> {
                            entry.setCapabilitySummary(capabilitySummary);
                            repository.save(entry);
                        });
    }

    private List<ProviderModelCatalogEntry> fetchOpenRouterModels() {
        try {
            JsonNode response =
                    restClient.get().uri(OPENROUTER_MODELS_URL).retrieve().body(JsonNode.class);
            if (response == null || !response.has("data")) {
                return List.of();
            }
            List<ProviderModelCatalogEntry> entries = new ArrayList<>();
            Iterator<JsonNode> elements = response.get("data").elements();
            while (elements.hasNext()) {
                JsonNode node = elements.next();
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                String name = node.path("name").asText(id);
                Integer contextWindow =
                        node.path("context_length").isMissingNode()
                                ? null
                                : node.path("context_length").asInt();
                // OpenRouter's own /models response carries a genuine per-model
                // capability signal at architecture.input_modalities (e.g.
                // ["text","image"]) - read it directly instead of guessing,
                // since this is the one provider here with a live, authoritative
                // source rather than a hand-maintained seed list.
                boolean supportsImage = false;
                boolean supportsAudio = false;
                boolean supportsVideo = false;
                JsonNode modalities = node.path("architecture").path("input_modalities");
                if (modalities.isArray()) {
                    for (JsonNode modality : modalities) {
                        String value = modality.asText("");
                        supportsImage |= "image".equalsIgnoreCase(value);
                        supportsAudio |= "audio".equalsIgnoreCase(value);
                        supportsVideo |= "video".equalsIgnoreCase(value);
                    }
                }
                entries.add(
                        new ProviderModelCatalogEntry(
                                ProviderName.OPENROUTER,
                                id,
                                name,
                                contextWindow,
                                supportsImage,
                                supportsAudio,
                                supportsVideo));
            }
            return entries;
        } catch (RestClientException e) {
            log.warn(
                    "Could not reach OpenRouter's public model list ({}) - leaving existing cache untouched",
                    OPENROUTER_MODELS_URL,
                    e);
            return List.of();
        }
    }
}
