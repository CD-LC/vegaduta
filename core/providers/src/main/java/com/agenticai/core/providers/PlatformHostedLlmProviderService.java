package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * CRUD + key validation for the platform-hosted LLM provider pool - backs {@code
 * PlatformController}'s {@code /api/platform/hosted-llm-providers} endpoints. See {@link
 * PlatformHostedLlmProvider}'s javadoc for why this lives in {@code core.providers} rather than
 * {@code core.platform}.
 *
 * <p>Unlike every {@code ProviderValidator} implementation (one fixed {@code baseUrl} each - {@code
 * GrokProviderValidator}'s {@code "https://api.x.ai/v1/models"}, etc.), the validation check here
 * is generic over whatever {@code baseUrl} an admin enters, since that's exactly the point of this
 * pool being runtime-configurable. Every provider this pool is meant for (Groq, Together AI,
 * Fireworks, Deepinfra, ...) is OpenAI-chat- completions-compatible and exposes the same free,
 * non-billed {@code GET /v1/models} metadata endpoint OpenAI-style APIs conventionally do - same
 * "cheap call, not a real generation" property {@code GrokProviderValidator}/{@code
 * OpenRouterProviderValidator} already establish, just with the host coming from the row being
 * validated instead of a compile-time constant.
 */
@Service
public class PlatformHostedLlmProviderService {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformHostedLlmProviderService.class);

    /**
     * The only {@link ProviderName} values this pool can actually route traffic to: {@link
     * com.agenticai.core.chat.HostedLlmChatClientFactory} always authenticates with a plain {@code
     * Authorization: Bearer <key>} header via Spring AI's {@code OpenAiApi}, so a provider is only
     * safe to auto-configure here if it is (a) OpenAI-chat-completions-compatible and (b) uses
     * standard bearer-token auth. That excludes {@code SARVAM} (its chat factory - see {@code
     * SarvamAgentChatClientFactory} - sends the key via a custom {@code api-subscription-key}
     * header instead, which the pool's client factory does not support per-row) and every
     * non-OpenAI- compat value ({@code ANTHROPIC}, {@code AZURE_OPENAI}, {@code GOOGLE_VERTEX},
     * {@code AWS_BEDROCK}, {@code OLLAMA}, {@code PLATFORM_HOSTED} itself). An admin can still add
     * any of those manually with a raw {@code baseUrl} row (no {@code providerName}) if a
     * compatible endpoint genuinely exists for it - this set only gates the *generic provider+key*
     * convenience flow, not the pool in general.
     *
     * <p>{@code MISTRAL}/{@code DEEPSEEK}/{@code GROQ} added 2026-08-03 - confirmed
     * OpenAI-chat-completions-compatible with plain bearer-token auth same as the original four
     * (see {@code MistralAgentChatClientFactory} /{@code DeepSeekAgentChatClientFactory}/{@code
     * GroqAgentChatClientFactory}'s own BASE_URL constants, mirrored into {@link
     * HostedPoolKnownEndpoints#KNOWN_PROVIDER_ENDPOINTS}). {@code META} added 2026-09-17 on the
     * same basis - Meta Model API ({@code api.meta.ai}) is OpenAI-chat-completions-compatible with
     * plain bearer auth, see {@code MetaAgentChatClientFactory}. {@code ANTHROPIC}/{@code
     * AZURE_OPENAI}/{@code GOOGLE_VERTEX}/ {@code AWS_BEDROCK}/{@code OLLAMA} remain excluded -
     * each needs a fundamentally different auth mechanism (Anthropic's own header shape, Azure's
     * endpoint+deployment+api-version, Vertex's GCP ADC/service-account, Bedrock's AWS SigV4
     * signing, Ollama's no-key local setup) that this bearer-token-only {@code
     * HostedLlmChatClientFactory} cannot express - adding any of them here would silently break
     * every FREE-tier chat call that picks that row, exactly the failure mode this set exists to
     * prevent. Each would need its own dedicated pool-client factory, not a one-line addition to
     * this set.
     */
    static final Set<ProviderName> HOSTED_POOL_COMPATIBLE_PROVIDERS =
            Set.of(
                    ProviderName.OPENAI,
                    ProviderName.GROK,
                    ProviderName.OPENROUTER,
                    ProviderName.GEMINI,
                    ProviderName.MISTRAL,
                    ProviderName.DEEPSEEK,
                    ProviderName.GROQ,
                    ProviderName.META);

    // KNOWN_PROVIDER_ENDPOINTS (default base URL per generic provider) lives in
    // HostedPoolKnownEndpoints since 2026-10-14 (file-size ratchet).

    private final PlatformHostedLlmProviderRepository repository;
    private final HostedLlmPoolSettingsRepository poolSettingsRepository;
    private final EncryptionService encryptionService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final HostedPoolEndpointProbe endpointProbe;
    private final HostedPoolRequestResolver requestResolver;
    private final HostedPoolChainResolver chain;

    /**
     * Null until Spring injects the bean; {@link #ssrfGuard()} falls back to the process-wide
     * guard.
     */
    private SsrfGuard ssrfGuard;

    // V499 - measures a row's tool-calling grade on save; a no-op until the chat module's probe
    // arrives.
    private HostedPoolToolCallGrading grading = new HostedPoolToolCallGrading(null);

    public PlatformHostedLlmProviderService(
            PlatformHostedLlmProviderRepository repository,
            HostedLlmPoolSettingsRepository poolSettingsRepository,
            EncryptionService encryptionService,
            RestClient.Builder restClientBuilder,
            ProviderModelCatalogService providerModelCatalogService) {
        this.repository = repository;
        this.poolSettingsRepository = poolSettingsRepository;
        this.encryptionService = encryptionService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.endpointProbe =
                new HostedPoolEndpointProbe(restClientBuilder.build(), this::ssrfGuard);
        this.requestResolver = new HostedPoolRequestResolver(providerModelCatalogService);
        this.chain =
                new HostedPoolChainResolver(
                        repository,
                        encryptionService,
                        () -> loadOrCreateSettings().isEnabled(),
                        this::ssrfGuard);
    }

    /**
     * Never throws 404 - creates a default (enabled) singleton row on first call, same pattern as
     * TranslationJobSettingsService#get.
     */
    public HostedLlmPoolSettingsResponse getPoolSettings() {
        return HostedLlmPoolSettingsResponse.from(loadOrCreateSettings());
    }

    /** The master switch's write side - see {@link HostedLlmPoolSettings}'s javadoc. */
    public HostedLlmPoolSettingsResponse setPoolEnabled(boolean enabled, String updatedBy) {
        HostedLlmPoolSettings settings = loadOrCreateSettings();
        settings.setEnabled(enabled, updatedBy);
        return HostedLlmPoolSettingsResponse.from(poolSettingsRepository.save(settings));
    }

    private HostedLlmPoolSettings loadOrCreateSettings() {
        return poolSettingsRepository
                .findById(HostedLlmPoolSettings.SINGLETON_ID)
                .orElseGet(() -> poolSettingsRepository.save(new HostedLlmPoolSettings(true)));
    }

    public List<PlatformHostedLlmProviderResponse> list() {
        return repository.findAllByOrderByPriorityAsc().stream()
                .map(PlatformHostedLlmProviderResponse::from)
                .toList();
    }

    /**
     * The pick for a FREE-plan tenant's next chat call: lowest-{@code priority} enabled row,
     * decrypted and ready to use - {@code core.agents}' free-tier routing calls this rather than
     * reaching for {@link EncryptionService} or {@link PlatformHostedLlmProviderRepository}
     * directly, same "resolver hides the decrypt step" shape {@link
     * com.agenticai.core.providers.ProviderCredentialResolver#resolveAnyActiveKey} already
     * establishes for BYOK. Also empty whenever {@link HostedLlmPoolSettings#isEnabled()} is {@code
     * false} - the master switch overrides every individual row's own {@code enabled} flag. Empty
     * when no row is enabled - the caller (currently only a FREE-tier chat attempt with nothing
     * configured) decides what that means, this class doesn't assume.
     */
    public Optional<HostedProviderSelection> resolveTopPriorityEnabled() {
        // The single-pick view is now just the head of the ordered fallback
        // chain (see resolveEnabledChain) - kept working so every pre-P3 call
        // site (single-client resolution) compiles and behaves unchanged,
        // while the chat path can take the whole chain and fall through it on
        // a 429/timeout/5xx.
        return resolveEnabledChain().stream().findFirst();
    }

    /**
     * V484 - true when the pool has at least one enabled row and every enabled row is {@link
     * PlatformHostedLlmProvider#isZeroCost() zero-cost}. {@code FreeTierChatClientService} uses it
     * to skip the token and dollar reservations of a turn that cannot cost anyone money.
     * Deliberately ignores the master switch and the SSRF guard: a false positive only means a
     * reservation is skipped for a turn that then fails with an empty pool, which costs nothing.
     */
    public boolean allEnabledRowsZeroCost() {
        List<PlatformHostedLlmProvider> enabled =
                repository.findAllByEnabledTrueOrderByPriorityAsc();
        return !enabled.isEmpty()
                && enabled.stream().allMatch(PlatformHostedLlmProvider::isZeroCost);
    }

    /**
     * P3 (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §6 + Appendix A) - the FULL priority-ordered
     * chain of enabled hosted providers, each decrypted and ready to use, lowest-{@code priority}
     * first. Where {@link #resolveTopPriorityEnabled} returns only the single top row (the pre-P3
     * behavior, which meant e.g. an OpenRouter free-tier {@code 429} just failed the FREE-tier turn
     * with no recovery), this returns every enabled row so a caller can try them in order, falling
     * to the next when one 429s / times out / 5xxes - the same "flat, bounded, original-error-wins"
     * fallback discipline {@code AgentChatService#callWithProviderFallback} already applies to
     * BYOK, now applied to the pool.
     *
     * <p>Recommended operator chain (documented in §6): Groq {@code llama-3.1-8b-instant} (fast,
     * near-free) → Gemini {@code gemini-2.0-flash-lite} (multimodal fallback) → Cerebras (resilient
     * backup). This method only wires the resolution/ordering logic; which rows actually exist is
     * entirely operator-configured in Platform Admin.
     *
     * <p>Empty whenever {@link HostedLlmPoolSettings#isEnabled()} is {@code false} (the master
     * switch overrides every row's own flag) or no row is enabled - identical emptiness contract to
     * {@link #resolveTopPriorityEnabled}, so the caller decides what an empty pool means.
     */
    public List<HostedProviderSelection> resolveEnabledChain() {
        return chain.enabledChain();
    }

    /**
     * Single-row counterpart of {@link #resolveEnabledChain} - resolves ONE row by id, applying the
     * exact same master-switch/enabled/SSRF-guard rules a chain build would. Used by {@code
     * core.agents}' {@code FreeTierChatClientService#rebuildLinkAfterSelfHeal} to get a fresh,
     * usable client for a row right after {@link #reautoSelectModelForRow} changed its model
     * mid-turn - the row's identity (id) is stable across that change, only its {@code model}
     * column moved, so re-resolving by id picks up the new value.
     *
     * @return empty when the pool is disabled, the row no longer exists, is no longer enabled, or
     *     now fails the SSRF guard.
     */
    public Optional<HostedProviderSelection> resolveEnabledSelectionById(Long id) {
        return chain.enabledSelectionById(id);
    }

    /**
     * {@link #resolveEnabledSelectionById} without the master-switch check: would this row serve if
     * the pool were switched on (enabled, base URL passes the SSRF guard, key decrypts).
     */
    public Optional<HostedProviderSelection> resolveEnabledSelectionByIdIgnoringPoolSwitch(
            Long id) {
        return chain.enabledSelectionByIdIgnoringPoolSwitch(id);
    }

    public record HostedProviderSelection(
            PlatformHostedLlmProvider provider, String decryptedApiKey) {}

    /**
     * The read-only "what does the pool currently route to" view - backs a GET any authenticated
     * tenant can call, so it never decrypts the stored API key (unlike {@link
     * #resolveTopPriorityEnabled}). Same lowest-priority-enabled row + master-switch check.
     */
    public PlatformHostedLlmActiveResponse getActiveSummary() {
        if (!loadOrCreateSettings().isEnabled()) {
            return PlatformHostedLlmActiveResponse.unavailable();
        }
        return repository.findAllByEnabledTrueOrderByPriorityAsc().stream()
                .findFirst()
                .map(PlatformHostedLlmActiveResponse::from)
                .orElseGet(PlatformHostedLlmActiveResponse::unavailable);
    }

    /**
     * Validates the key against the given {@code baseUrl} before ever persisting anything - a row
     * that fails validation is never written, same "nothing is persisted in that case" posture
     * {@code ProviderService#activate} already establishes for tenant BYOK keys.
     */
    public PlatformHostedLlmProviderResponse create(
            PlatformHostedLlmProviderUpsertRequest request) {
        return create(request, false);
    }

    /** As {@link #create}, but a blank key is allowed: a keyless local server (Ollama...). */
    public PlatformHostedLlmProviderResponse createAllowingBlankKey(
            PlatformHostedLlmProviderUpsertRequest request) {
        return create(request, true);
    }

    private PlatformHostedLlmProviderResponse create(
            PlatformHostedLlmProviderUpsertRequest request, boolean allowBlankKey) {
        if (!StringUtils.hasText(request.label())) {
            throw new ProviderValidationException("A label is required for a hosted provider.");
        }
        String authType = PlatformHostedLlmAuthType.normalize(request.authType());
        if (PlatformHostedLlmAuthType.isVertex(authType)) {
            return createVertex(authType, request);
        }
        if (!allowBlankKey && request.requiresApiKey() && !StringUtils.hasText(request.apiKey())) {
            throw new ProviderValidationException(
                    "An API key is required to add a hosted provider.");
        }
        ResolvedRequest resolved = requestResolver.resolve(request);
        // Pentest addendum P1: this admin-supplied baseUrl is reachable from
        // server-to-server calls once validated - SSRF-guard it (like MCP's
        // McpRemoteUrlGuard) before ever sending the API key anywhere.
        HostedLlmBaseUrlGuard.checkAllowed(resolved.baseUrl(), ssrfGuard());
        endpointProbe.validateKey(resolved.baseUrl(), request.apiKey(), resolved.completionsPath());

        PlatformHostedLlmProvider entity =
                new PlatformHostedLlmProvider(
                        request.label(),
                        resolved.baseUrl(),
                        resolved.model(),
                        StringUtils.hasText(request.apiKey())
                                ? encryptionService.encrypt(request.apiKey())
                                : null,
                        request.enabled(),
                        request.priority(),
                        request.region(),
                        resolved.completionsPath(),
                        providerNameOf(request),
                        resolved.modelAutoSelected(),
                        authType,
                        null,
                        null);
        entity.setZeroCost(request.effectiveZeroCost());
        grading.measureInto(entity, resolved, request.apiKey());
        request.applyOverridesTo(entity);
        entity.markValidatedNow();
        return PlatformHostedLlmProviderResponse.from(repository.save(entity));
    }

    /**
     * The {@code VERTEX_ADC}/{@code VERTEX_SERVICE_ACCOUNT} counterpart to {@link #create} -
     * deliberately does not go through {@link HostedPoolRequestResolver#resolve} (that method's
     * provider/base-URL derivation is entirely about the OpenAI-chat-completions-compatible path
     * {@link com.agenticai.core.chat.HostedLlmChatClientFactory}'s non-Vertex branch builds
     * against; a Vertex row is driven by project/location/credentials instead - see that factory's
     * Vertex branch). No live key-validation probe either: there is no generic "does this
     * service-account JSON or ADC actually work" endpoint to hit ahead of a real chat call, the
     * same reason {@link VertexAiChatClientFactory} never validates a tenant's BYOK Vertex
     * credential eagerly either.
     */
    private PlatformHostedLlmProviderResponse createVertex(
            String authType, PlatformHostedLlmProviderUpsertRequest request) {
        ResolvedVertexRequest resolved = VertexRequestSupport.resolve(request);
        HostedLlmBaseUrlGuard.checkAllowed(resolved.baseUrl(), ssrfGuard());

        String encryptedSecret = null;
        if (PlatformHostedLlmAuthType.VERTEX_SERVICE_ACCOUNT.equals(authType)) {
            if (request.requiresApiKey() && !StringUtils.hasText(request.apiKey())) {
                throw new ProviderValidationException(
                        "A GCP service-account JSON key is required for a Vertex AI (service"
                                + " account) hosted provider.");
            }
            VertexRequestSupport.validateServiceAccountJson(request.apiKey());
            encryptedSecret = encryptionService.encrypt(request.apiKey());
        }

        PlatformHostedLlmProvider entity =
                new PlatformHostedLlmProvider(
                        request.label(),
                        resolved.baseUrl(),
                        resolved.model(),
                        encryptedSecret,
                        request.enabled(),
                        request.priority(),
                        request.region(),
                        null,
                        providerNameOf(request),
                        false,
                        authType,
                        resolved.vertexProjectId(),
                        resolved.vertexLocation());
        return PlatformHostedLlmProviderResponse.from(repository.save(entity));
    }

    /**
     * Only re-validates against xAI-style upstream when a new key is actually submitted - editing
     * just the label/priority/enabled flag (the common case, e.g. temporarily disabling a provider
     * during an outage) shouldn't require re-proving a key that was already proven valid at
     * creation and hasn't changed.
     */
    public PlatformHostedLlmProviderResponse update(
            Long id, PlatformHostedLlmProviderUpsertRequest request) {
        PlatformHostedLlmProvider entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new PlatformHostedLlmProviderNotFoundException(id));
        if (!StringUtils.hasText(request.label())) {
            throw new ProviderValidationException("A label is required for a hosted provider.");
        }
        String authType = PlatformHostedLlmAuthType.normalize(request.authType());
        if (PlatformHostedLlmAuthType.isVertex(authType)) {
            return updateVertex(entity, authType, request);
        }

        // Blanking `model` on an edit (with providerName carried forward or
        // re-supplied) re-runs auto-selection - the intended fix path when a
        // vendor deprecates the previously-picked model id (the
        // "gemini-2.5-flash-lite / no longer available to new users"
        // incident this feature answers) without retyping the whole row.
        ResolvedRequest resolved = requestResolver.resolve(request);
        // Pentest addendum P1 - same SSRF guard as create() above.
        HostedLlmBaseUrlGuard.checkAllowed(resolved.baseUrl(), ssrfGuard());

        String newEncryptedApiKeyOrNull = null;
        if (StringUtils.hasText(request.apiKey())) {
            endpointProbe.validateKey(
                    resolved.baseUrl(), request.apiKey(), resolved.completionsPath());
            newEncryptedApiKeyOrNull = encryptionService.encrypt(request.apiKey());
        }
        // V499/V500 - re-measured only when (endpoint, model) changed or never measured.
        String keyForProbe =
                StringUtils.hasText(request.apiKey())
                        ? request.apiKey()
                        : chain.decryptSecretOrNull(entity);
        grading.forUpdateInto(entity, resolved, keyForProbe);

        entity.apply(
                request.label(),
                resolved.baseUrl(),
                resolved.model(),
                newEncryptedApiKeyOrNull,
                request.enabled(),
                request.priority(),
                request.region(),
                resolved.completionsPath(),
                providerNameOf(request),
                resolved.modelAutoSelected(),
                authType,
                null,
                null);
        entity.setZeroCost(request.effectiveZeroCost());
        request.applyOverridesTo(entity);
        if (newEncryptedApiKeyOrNull != null) {
            entity.markValidatedNow();
        }
        return PlatformHostedLlmProviderResponse.from(repository.save(entity));
    }

    /**
     * Changes ONLY a row's model - the runtime switch the local-model surface needs ({@code POST
     * /api/local-models/use}, "use this model" on /troubleshoot). Label, base URL, stored key,
     * enabled flag, priority and the per-row overrides are kept exactly as stored; because
     * (endpoint, model) changed, {@link HostedPoolToolCallGrading} re-measures the tool-call grade,
     * and the model is marked hand-picked ({@code modelAutoSelected=false}) so the invalid-model
     * self-heal never rewrites an operator's deliberate choice.
     */
    public PlatformHostedLlmProviderResponse updateModel(Long id, String model) {
        PlatformHostedLlmProvider entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new PlatformHostedLlmProviderNotFoundException(id));
        if (!StringUtils.hasText(model)) {
            throw new ProviderValidationException("A model is required.");
        }
        ResolvedRequest resolved =
                new ResolvedRequest(
                        entity.getBaseUrl(), entity.getCompletionsPath(), model.trim(), false);
        grading.forUpdateInto(entity, resolved, chain.decryptSecretOrNull(entity));
        entity.apply(
                entity.getLabel(),
                entity.getBaseUrl(),
                model.trim(),
                null,
                entity.isEnabled(),
                entity.getPriority(),
                entity.getRegion(),
                entity.getCompletionsPath(),
                entity.getProviderName(),
                false,
                entity.getAuthType(),
                entity.getVertexProjectId(),
                entity.getVertexLocation());
        return PlatformHostedLlmProviderResponse.from(repository.save(entity));
    }

    /** Spring injects the chat module's probe when present; tests inject a stub. */
    @Autowired(required = false)
    public void setToolCallCapabilityProbe(ToolCallCapabilityProbe toolCallCapabilityProbe) {
        this.grading = new HostedPoolToolCallGrading(toolCallCapabilityProbe);
    }

    /**
     * The {@code VERTEX_ADC}/{@code VERTEX_SERVICE_ACCOUNT} counterpart to {@link #update} - see
     * {@link #createVertex}'s javadoc for why this bypasses {@link
     * HostedPoolRequestResolver}/{@link HostedPoolEndpointProbe#validateKey}.
     */
    private PlatformHostedLlmProviderResponse updateVertex(
            PlatformHostedLlmProvider entity,
            String authType,
            PlatformHostedLlmProviderUpsertRequest request) {
        ResolvedVertexRequest resolved = VertexRequestSupport.resolve(request);
        HostedLlmBaseUrlGuard.checkAllowed(resolved.baseUrl(), ssrfGuard());

        String newEncryptedApiKeyOrNull = null;
        if (PlatformHostedLlmAuthType.VERTEX_SERVICE_ACCOUNT.equals(authType)
                && StringUtils.hasText(request.apiKey())) {
            VertexRequestSupport.validateServiceAccountJson(request.apiKey());
            newEncryptedApiKeyOrNull = encryptionService.encrypt(request.apiKey());
        }

        entity.apply(
                request.label(),
                resolved.baseUrl(),
                resolved.model(),
                newEncryptedApiKeyOrNull,
                request.enabled(),
                request.priority(),
                request.region(),
                null,
                providerNameOf(request),
                false,
                authType,
                resolved.vertexProjectId(),
                resolved.vertexLocation());
        if (PlatformHostedLlmAuthType.VERTEX_ADC.equals(authType)) {
            // ADC stores no secret at all - see PlatformHostedLlmProvider#clearEncryptedSecret's
            // javadoc for why this doesn't just rely on apply()'s
            // newEncryptedApiKeyOrNull-only-overwrites-never-clears posture.
            entity.clearEncryptedSecret();
        }
        return PlatformHostedLlmProviderResponse.from(repository.save(entity));
    }

    private static String providerNameOf(PlatformHostedLlmProviderUpsertRequest request) {
        return request.providerName() == null ? null : request.providerName().name();
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new PlatformHostedLlmProviderNotFoundException(id);
        }
        repository.deleteById(id);
    }

    /**
     * The {@code (baseUrl, completionsPath, model, modelAutoSelected)} an upsert actually persists
     * - see {@link HostedPoolRequestResolver#resolve}.
     */
    record ResolvedRequest(
            String baseUrl, String completionsPath, String model, boolean modelAutoSelected) {}

    /**
     * The {@code (baseUrl, model, vertexProjectId, vertexLocation)} a Vertex upsert actually
     * persists - see {@link VertexRequestSupport#resolve}.
     */
    record ResolvedVertexRequest(
            String baseUrl, String model, String vertexProjectId, String vertexLocation) {}

    /**
     * Hosted-pool invalid-model SELF-HEAL (2026-08-05) - re-runs {@link
     * HostedPoolRequestResolver#autoSelectModel} for one existing row whose stored model id the
     * upstream just rejected as unknown/retired (the "gemini-2.5-flash-lite / no longer available
     * to new users" incident shape, without waiting for an admin to blank the model by hand - see
     * the comment inside {@link #update}). Called asynchronously (and per-row rate-limited) by
     * {@code core.agents}' {@code AgentChatService} when a pool rung fails with an invalid-model
     * error.
     *
     * <p>Deliberately conservative - the row is only ever touched when ALL of:
     *
     * <ul>
     *   <li>it still exists, and its model was AUTO-selected in the first place ({@link
     *       PlatformHostedLlmProvider#isModelAutoSelected()}) - a hand-typed model id is an admin's
     *       explicit choice and is never rewritten behind their back;
     *   <li>it carries a known, non-Vertex {@code providerName} (auto-selection is only defined for
     *       the catalog-backed generic provider flow);
     *   <li>auto-selection actually picks a DIFFERENT model than the one that just failed -
     *       re-picking the same id (a stale catalog, or a non-model failure misclassified upstream)
     *       changes nothing and is logged instead.
     * </ul>
     *
     * The provider's catalog is refreshed first (best-effort) so a freshly-retired id has actually
     * disappeared from the candidate list rather than being re-picked from a stale cache.
     *
     * @param failureDetail the technical upstream message that triggered the heal - logged only, so
     *     the row's audit trail says WHY it changed.
     * @return true when the row's model was actually changed.
     */
    public boolean reautoSelectModelForRow(Long id, String failureDetail) {
        PlatformHostedLlmProvider entity = repository.findById(id).orElse(null);
        if (entity == null
                || !entity.isModelAutoSelected()
                || entity.getProviderName() == null
                || PlatformHostedLlmAuthType.isVertex(entity.getAuthType())) {
            return false;
        }
        ProviderName providerName;
        try {
            providerName = ProviderName.valueOf(entity.getProviderName());
        } catch (IllegalArgumentException unknownProviderName) {
            return false;
        }
        try {
            providerModelCatalogService.refreshProvider(providerName);
        } catch (RuntimeException refreshFailure) {
            // Best-effort: a refresh outage shouldn't block healing from the
            // existing cache (which may already have dropped the dead model).
            log.warn(
                    "Model-catalog refresh for {} failed during hosted-pool self-heal of row {} - "
                            + "continuing with the cached catalog",
                    providerName,
                    id,
                    refreshFailure);
        }
        String replacement;
        try {
            replacement = requestResolver.autoSelectModel(providerName);
        } catch (RuntimeException emptyCatalogOrLookupFailure) {
            log.warn(
                    "Hosted-pool self-heal for row {} ({}) could not auto-select a replacement"
                            + " model",
                    id,
                    providerName,
                    emptyCatalogOrLookupFailure);
            return false;
        }
        if (replacement.equals(entity.getModel())) {
            log.warn(
                    "Hosted-pool self-heal for row {} ({}) re-picked the SAME model '{}' the"
                            + " upstream just rejected ({}) - the catalog may still list it; leaving"
                            + " the row unchanged",
                    id,
                    providerName,
                    replacement,
                    failureDetail);
            return false;
        }
        log.warn(
                "Hosted-pool self-heal: row {} ('{}', {}) had auto-selected model '{}' rejected"
                        + " upstream ({}) - switching the row to freshly auto-selected model '{}'",
                id,
                entity.getLabel(),
                providerName,
                entity.getModel(),
                failureDetail,
                replacement);
        entity.apply(
                entity.getLabel(),
                entity.getBaseUrl(),
                replacement,
                null,
                entity.isEnabled(),
                entity.getPriority(),
                entity.getRegion(),
                entity.getCompletionsPath(),
                entity.getProviderName(),
                true,
                entity.getAuthType(),
                entity.getVertexProjectId(),
                entity.getVertexLocation());
        repository.save(entity);
        return true;
    }

    /**
     * Live "what models does this key actually have access to" probe for the web-admin hosted-pool
     * form's model dropdown (2026-07-26 feedback: "intelligently list all available models from the
     * provider based on their API specifications"). Unlike {@link ProviderModelCatalogService}
     * (which deliberately never calls a live per-key endpoint for a platform-wide background job -
     * see that class's javadoc on why borrowing a tenant's key would be a blast-radius problem), a
     * live call here is legitimate: this key IS the platform's own, being configured for exactly
     * this purpose, not borrowed from anyone. Only meaningful for a raw {@code baseUrl} row with no
     * {@code providerName} (Groq, Together AI, Cerebras, ...) - a compatible {@code providerName}
     * should call {@link ProviderModelCatalogService#listModels} instead (instant, no key needed,
     * same data {@link HostedPoolRequestResolver#autoSelectModel} uses).
     *
     * <p>Reuses the same models-URL derivation {@link HostedPoolEndpointProbe#validateKey} already
     * uses, so this always probes the exact same endpoint a save's validation would - no separate
     * discovery path to keep in sync. Returns an empty list (never throws) when the provider has no
     * conventional models endpoint to probe or the call fails, so the admin form degrades to manual
     * model entry rather than blocking the screen.
     */
    public List<String> discoverModels(String baseUrl, String apiKey, String completionsPath) {
        return endpointProbe.discoverModels(baseUrl, apiKey, completionsPath);
    }

    /**
     * Spring injects the configured guard (operator allow-list); tests inject a guard with a fake
     * resolver.
     */
    @Autowired(required = false)
    void setSsrfGuard(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    private SsrfGuard ssrfGuard() {
        return ssrfGuard != null ? ssrfGuard : SsrfGuard.defaultGuard();
    }
}
