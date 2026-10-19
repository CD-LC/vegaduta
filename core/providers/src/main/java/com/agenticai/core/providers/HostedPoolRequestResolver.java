package com.agenticai.core.providers;

import com.agenticai.core.providers.PlatformHostedLlmProviderService.ResolvedRequest;
import com.agenticai.core.providers.validation.ProviderValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Turns a hosted-pool upsert request into the {@code (baseUrl, completionsPath, model,
 * modelAutoSelected)} a row persists, including model auto-selection from {@link
 * ProviderModelCatalogService}. Split out of {@link PlatformHostedLlmProviderService}, which also
 * uses {@link #autoSelectModel} for its invalid-model self-heal.
 */
final class HostedPoolRequestResolver {


    /**
     * Auto-selection preference order (checked as a case-insensitive substring of the model id,
     * first match wins) - this pool primarily serves FREE-tier traffic, where a fast/cheap model
     * matters far more than raw capability, so "best" here means "best for that job," not "most
     * powerful." Mirrors the operator-recommended chain {@code FreeTierChatClientService}'s javadoc
     * already documents (Groq {@code llama-3.1-8b-instant}, Gemini {@code flash-lite}). Falls back
     * to the catalog's first entry (alphabetical by model id - see {@code
     * ProviderModelCatalogService#listModels}) if nothing matches, so auto-selection always
     * resolves to *something* rather than failing.
     */
    static final List<String> AUTO_MODEL_PREFERENCE_KEYWORDS =
            List.of("flash-lite", "flash", "mini", "haiku", "lite", "8b", "instant");

    private final ProviderModelCatalogService providerModelCatalogService;

    HostedPoolRequestResolver(ProviderModelCatalogService providerModelCatalogService) {
        this.providerModelCatalogService = providerModelCatalogService;
    }

    /**
     * Fills in whatever an admin left blank, using {@code providerName} as the "generic, like
     * tenant BYOK" convenience source (2026-07-26 feedback) - an explicit {@code baseUrl}/{@code
     * completionsPath}/{@code model} the admin actually typed always wins over anything derived
     * here.
     *
     * <ul>
     *   <li>{@code providerName} present but not in {@link
     *       PlatformHostedLlmProviderService#HOSTED_POOL_COMPATIBLE_PROVIDERS}:
     *       rejected outright - this pool has no client factory that can authenticate against it
     *       (see that set's javadoc), so silently accepting the row would only fail later, at chat
     *       time, for every FREE-tier user.
     *   <li>{@code baseUrl} blank + a compatible {@code providerName}: filled from {@link
     *       HostedPoolKnownEndpoints#KNOWN_PROVIDER_ENDPOINTS}.
     *   <li>{@code baseUrl} still blank (no {@code providerName}, or one not in the known-endpoint
     *       map): rejected - same message as before this change, so every pre-existing raw-{@code
     *       baseUrl} row (Groq, Together AI, ...) keeps behaving identically.
     *   <li>{@code model} blank + a {@code providerName}: auto-selected via {@link
     *       #autoSelectModel} - {@link ProviderModelCatalogService} is the source of truth for what
     *       that provider's models actually are, same dropdown data the tenant BYOK model picker
     *       already reads, rather than a second hand-maintained list.
     *   <li>{@code model} still blank (no {@code providerName}): rejected - same message as before
     *       this change. There is no provider identity to safely auto-pick a model for on an
     *       arbitrary hand-typed {@code baseUrl}.
     * </ul>
     */
    ResolvedRequest resolve(PlatformHostedLlmProviderUpsertRequest request) {
        ProviderName providerName = request.providerName();
        if (providerName != null && !PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS.contains(providerName)) {
            throw new ProviderValidationException(
                    providerName
                            + " isn't supported for the hosted pool's generic provider+key flow yet"
                            + " - hosted-pool providers must be OpenAI-chat-completions-compatible"
                            + " with standard bearer-token auth (currently "
                            + PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS
                            + "). Enter a custom OpenAI-compatible base URL "
                            + "and model instead.");
        }

        String rawBaseUrl = request.baseUrl();
        String rawCompletionsPath = HostedPoolEndpointProbe.trimToNull(request.completionsPath());
        if (!StringUtils.hasText(rawBaseUrl) && providerName != null) {
            HostedPoolKnownEndpoints.KnownEndpoint known =
                    HostedPoolKnownEndpoints.KNOWN_PROVIDER_ENDPOINTS.get(providerName);
            rawBaseUrl = known.baseUrl();
            if (rawCompletionsPath == null) {
                rawCompletionsPath = known.completionsPath();
            }
        }
        if (!StringUtils.hasText(rawBaseUrl)) {
            throw new ProviderValidationException("A base URL is required for a hosted provider.");
        }
        String baseUrl = HostedPoolEndpointProbe.effectiveBaseUrl(rawBaseUrl, rawCompletionsPath);

        String model = request.model();
        boolean modelAutoSelected = false;
        if (!StringUtils.hasText(model)) {
            if (providerName == null) {
                throw new ProviderValidationException(
                        "A model id is required for a hosted provider (e.g."
                                + " \"gemini-2.0-flash-lite\", \"llama-3.1-8b-instant\") - or set"
                                + " providerName to auto-select one.");
            }
            model = autoSelectModel(providerName);
            modelAutoSelected = true;
        }
        return new ResolvedRequest(baseUrl, rawCompletionsPath, model, modelAutoSelected);
    }

    /**
     * "Best" = cheapest/fastest currently-cataloged model for {@code providerName}, since this pool
     * primarily serves FREE-tier traffic - see {@link #AUTO_MODEL_PREFERENCE_KEYWORDS}. Reuses
     * {@link ProviderModelCatalogService} (the same daily-refreshed catalog backing the tenant BYOK
     * model dropdown) rather than a second, parallel discovery mechanism - exactly the reuse the
     * maintainer's feedback asked for.
     */
    String autoSelectModel(ProviderName providerName) {
        List<ProviderModelResponse> models = providerModelCatalogService.listModels(providerName);
        // Cost-ordered pick first (control_plane.media_model_cost via the catalog
        // service), keyword preference only as the fallback - see
        // ProviderModelCatalogService#costPreferredModel. Null-safe because a
        // mocked catalog service hands back null rather than Optional.empty().
        Optional<String> byCost =
                models == null
                        ? Optional.empty()
                        : providerModelCatalogService.costPreferredModel(providerName, models);
        String picked =
                byCost != null && byCost.isPresent()
                        ? byCost.get()
                        : pickBestModel(providerName, models);
        if (picked == null) {
            throw new ProviderValidationException(
                    "Could not auto-select a model for "
                            + providerName
                            + " - its model catalog is currently empty. Enter a model id manually,"
                            + " or try again after the catalog refreshes.");
        }
        return picked;
    }

    static String pickBestModel(List<ProviderModelResponse> models) {
        return pickBestModel(null, models);
    }

    /**
     * Fast/cheap keyword match first; when no keyword applies (Meta's muse-spark line, Grok,
     * Mistral, DeepSeek - none of their ids contain "mini"/"flash"/...), defer to the catalog's
     * documented default for the provider ({@link ProviderModelCatalogService#pickDefaultModel})
     * instead of the alphabetical first row - which for META would have silently picked the oldest
     * release, {@code muse-spark-1.1}, over the recommended 1.3. Alphabetical-first remains the
     * last resort for a null provider.
     */
    static String pickBestModel(ProviderName provider, List<ProviderModelResponse> models) {
        for (String keyword : AUTO_MODEL_PREFERENCE_KEYWORDS) {
            for (ProviderModelResponse model : models) {
                if (model.modelId().toLowerCase(Locale.ROOT).contains(keyword)) {
                    return model.modelId();
                }
            }
        }
        if (provider != null) {
            String preferred = ProviderModelCatalogService.pickDefaultModel(provider, models);
            if (preferred != null) {
                return preferred;
            }
        }
        return models.isEmpty() ? null : models.get(0).modelId();
    }
}
