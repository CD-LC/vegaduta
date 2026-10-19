package com.agenticai.core.providers;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.LiveProviderModelFetcher.LiveModel;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The read path behind {@code GET /api/providers/{provider}/models} - layers
 * a live, per-tenant model fetch ({@link LiveProviderModelFetcher}) on top of
 * the cached platform catalog ({@link ProviderModelCatalogService}):
 *
 * <ul>
 *   <li>Tenant has an active BYOK key for the provider (or the provider is
 *   OPENROUTER, whose list is public): the LIVE list defines WHICH models
 *   exist - it's the ground truth for what that specific key can reach,
 *   including brand-new releases and minus retired ids the hand-maintained
 *   seed list hasn't caught up on. Each live row is then ENRICHED from the
 *   catalog where a matching row exists (generated capabilitySummary,
 *   modality flags, context window), since the flat live endpoints mostly
 *   don't carry those.
 *   <li>No key, or the live fetch fails/returns empty: the cached catalog is
 *   served exactly as before this class existed - byte-for-byte the same
 *   response shape (the appended DTO fields are null/"CATALOG"), so the 8
 *   existing frontend call sites see no behavioral change.
 * </ul>
 *
 * <p>Live results go through a small in-process TTL cache keyed by
 * tenant+provider (15 min, bounded) - a dropdown open should not cost a
 * vendor round trip per render, and a failing endpoint should not add its
 * timeout to every request for the same window. Empty results are cached
 * too, deliberately: serving the catalog for 15 minutes after a vendor
 * hiccup beats re-paying an 8-second timeout on every dropdown load.
 *
 * <p>Key handling mirrors {@link ProviderCredentialResolver}'s decrypt-and-
 * drop posture (the decrypted key lives only on this request's stack), but
 * resolves to {@code null} instead of throwing when nothing is active -
 * "no key" is the normal catalog-only case here, not an error. SARVAM is
 * deliberately not live-fetched (its key lives in its own credential store
 * and it has no models-list endpoint), so it always serves the catalog.
 */
@Service
public class ProviderModelListingService {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelListingService.class);

    private static final Duration LIVE_CACHE_TTL = Duration.ofMinutes(15);
    // tenants x providers is small in practice; this bound only guards
    // against unbounded growth (e.g. a tenant-id churn bug), not real usage.
    private static final int LIVE_CACHE_MAX_ENTRIES = 1_000;
    // A local server's loaded models change whenever its owner starts or swaps one, so its list is cached
    // briefly and an unreachable server (empty result) is not cached at all.
    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(1);

    private final ProviderModelCatalogService catalogService;
    private final LiveProviderModelFetcher liveFetcher;
    private final LlmProviderCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;

    private record CachedLiveList(List<LiveModel> models, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, CachedLiveList> liveCache = new ConcurrentHashMap<>();

    /** LOCAL_OPENAI's listing goes to the tenant's own server through the SSRF-guarded client; absent in slim contexts. */
    private LocalOpenAiServerClient localOpenAiServerClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLocalOpenAiServerClient(LocalOpenAiServerClient localOpenAiServerClient) {
        this.localOpenAiServerClient = localOpenAiServerClient;
    }

    public ProviderModelListingService(
        ProviderModelCatalogService catalogService,
        LiveProviderModelFetcher liveFetcher,
        LlmProviderCredentialRepository credentialRepository,
        EncryptionService encryptionService
    ) {
        this.catalogService = catalogService;
        this.liveFetcher = liveFetcher;
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
    }

    // Deliberately NOT @Transactional: the catalog read and the credential
    // read each carry their own transaction (ProviderModelCatalogService's
    // proxied @Transactional, Spring Data's per-query default), and wrapping
    // this method would pin a DB connection for the full duration of an
    // up-to-8s live HTTP call - exactly the kind of pool starvation the
    // PgBouncer session-pooling setup makes expensive.
    public List<ProviderModelResponse> listModels(ProviderName provider) {
        List<ProviderModelResponse> catalog = catalogService.listModels(provider);

        String apiKey = provider == ProviderName.OPENROUTER ? "" : resolveActiveKeyOrNull(provider);
        if (apiKey == null) {
            return catalog;
        }

        List<LiveModel> live = cachedLiveFetch(provider, apiKey);
        if (live.isEmpty()) {
            return catalog;
        }
        return merge(live, catalog);
    }

    /**
     * The live list defines which models exist; the catalog enriches each
     * row it has a match for. Catalog-only rows are NOT appended - if the
     * vendor's own list (as seen by this very key) doesn't name a model, the
     * dropdown offering it would just set the tenant up for an upstream 404.
     * Sorted by model id to preserve the catalog path's stable ordering.
     */
    private static List<ProviderModelResponse> merge(List<LiveModel> live, List<ProviderModelResponse> catalog) {
        Map<String, ProviderModelResponse> catalogById = catalog.stream()
            .collect(Collectors.toMap(ProviderModelResponse::modelId, Function.identity(), (a, b) -> a));
        return live.stream()
            .map(liveModel -> {
                ProviderModelResponse catalogRow = catalogById.get(liveModel.modelId());
                Integer contextWindow = liveModel.contextWindow() != null
                    ? liveModel.contextWindow()
                    : (catalogRow != null ? catalogRow.contextWindow() : null);
                boolean image = liveModel.supportsImageInput() != null
                    ? liveModel.supportsImageInput()
                    : catalogRow != null && catalogRow.supportsImageInput();
                boolean audio = catalogRow != null && catalogRow.supportsAudioInput();
                boolean video = catalogRow != null && catalogRow.supportsVideoInput();
                // Prefer the catalog's (possibly LLM-generated) blurb; a
                // live-only model gets the same deterministic template the
                // catalog path uses, so the field is never blank either way.
                String capabilitySummary = catalogRow != null
                    ? catalogRow.capabilitySummary()
                    : ProviderModelResponse.summarize(contextWindow, image, false, false);
                // Some providers' minimal /v1/models endpoints just echo the
                // id back as the "name" (no real display name given) - in
                // that case prefer the catalog's friendlier name over the
                // live echo, when we have one.
                boolean liveDisplayNameIsJustTheId = liveModel.displayName() == null
                    || liveModel.displayName().isBlank()
                    || liveModel.displayName().equals(liveModel.modelId());
                String displayName = !liveDisplayNameIsJustTheId
                    ? liveModel.displayName()
                    : (catalogRow != null && catalogRow.displayName() != null ? catalogRow.displayName() : liveModel.modelId());
                return new ProviderModelResponse(
                    liveModel.modelId(),
                    displayName,
                    contextWindow,
                    image,
                    audio,
                    video,
                    capabilitySummary,
                    liveModel.description(),
                    liveModel.deprecated(),
                    ProviderModelResponse.SOURCE_LIVE
                );
            })
            .sorted(Comparator.comparing(ProviderModelResponse::modelId))
            .toList();
    }

    private List<LiveModel> cachedLiveFetch(ProviderName provider, String apiKey) {
        boolean local = provider == ProviderName.LOCAL_OPENAI;
        Duration ttl = local ? LOCAL_CACHE_TTL : LIVE_CACHE_TTL;
        String cacheKey = TenantContext.current().map(TenantContext::getTenantId).orElse("platform")
            + ":" + provider.name();
        CachedLiveList cached = liveCache.get(cacheKey);
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minus(ttl))) {
            return cached.models();
        }
        List<LiveModel> fetched = local ? fetchLocalOpenAi(apiKey) : liveFetcher.fetch(provider, apiKey);
        if (local && fetched.isEmpty()) {
            return fetched;
        }
        if (liveCache.size() >= LIVE_CACHE_MAX_ENTRIES) {
            // Crude but sufficient bound: expire-sweep first, clear if a
            // sweep freed nothing (only reachable via pathological key churn).
            Instant cutoff = Instant.now().minus(LIVE_CACHE_TTL);
            liveCache.entrySet().removeIf(entry -> entry.getValue().fetchedAt().isBefore(cutoff));
            if (liveCache.size() >= LIVE_CACHE_MAX_ENTRIES) {
                log.warn("Live model cache still over its {}-entry bound after expiry sweep - clearing", LIVE_CACHE_MAX_ENTRIES);
                liveCache.clear();
            }
        }
        liveCache.put(cacheKey, new CachedLiveList(fetched, Instant.now()));
        return fetched;
    }

    /**
     * The tenant's own server's model list (the decrypted credential blob stands in for the key). Empty - never an
     * exception - when the server is unreachable, refused by the SSRF guard, or not an OpenAI-compatible server, so
     * the dropdown degrades to free-text model entry like every other provider.
     */
    private List<LiveModel> fetchLocalOpenAi(String credentialJson) {
        if (localOpenAiServerClient == null) {
            return List.of();
        }
        try {
            LocalOpenAiServerClient.Listing listing =
                localOpenAiServerClient.listModels(LocalOpenAiCredential.decode(credentialJson));
            if (!listing.ok()) {
                return List.of();
            }
            return listing.models().stream()
                .filter(m -> LiveProviderModelFetcher.isLikelyChatModel(m.id()))
                .map(m -> new LiveModel(m.id(), m.id(), null, m.contextWindow(), null, null))
                .toList();
        } catch (RuntimeException e) {
            log.warn("Live model listing from the tenant's local OpenAI-compatible server failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * The current tenant's decrypted BYOK key for {@code provider}, or null
     * when none is ACTIVE - unlike {@link
     * ProviderCredentialResolver#resolveActiveKey}, absence is expected here
     * (it just means "catalog-only listing"), so no exception. Also null for
     * SARVAM (see class javadoc) and on a decrypt failure (a key that can't
     * be decrypted can't make a live call either).
     */
    private String resolveActiveKeyOrNull(ProviderName provider) {
        if (provider == ProviderName.SARVAM) {
            return null;
        }
        try {
            return credentialRepository.findByProviderName(provider)
                .filter(credential -> credential.getStatus() == ProviderStatus.ACTIVE)
                .map(credential -> encryptionService.decrypt(credential.getEncryptedApiKey()))
                .orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve an active {} key for live model listing - serving the catalog", provider, e);
            return null;
        }
    }
}
