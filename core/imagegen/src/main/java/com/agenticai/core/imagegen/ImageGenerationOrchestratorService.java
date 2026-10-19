package com.agenticai.core.imagegen;

import com.agenticai.core.providers.LlmProviderCredentialRepository;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Picks whichever image-generation provider this tenant actually has configured, instead of the
 * agent tool being hardcoded to OpenAI ({@code GenerateImageToolCallback} previously called {@link
 * OpenAiImageGenerationClient} directly - a tenant with only a Google Vertex credential and no
 * OpenAI key got no image tool at all, even though {@link VertexImageGenerationClient} already
 * exists and is registered in {@link ImageGenerationClientRegistry}). Resolves credentials/quota
 * itself so the one real caller ({@code imagegen.agenttools.GenerateImageToolCallback}, agents
 * module) does not need to know which provider ends up serving a given request.
 *
 * <p><b>Provider order is cost order (2026-09-20), in two tiers.</b> Tier 1 is the tenant's OWN
 * keys: every active BYOK provider, cheapest first per {@link MediaModelCostService} ({@code
 * control_plane.media_model_cost}). Tier 2 is the platform pool. BYOK always outranks the pool even
 * when the pool model is cheaper per image, because a tenant who configured a key chose that
 * provider, and the pool spends the platform's money rather than theirs. With the seeded costs the
 * order is OpenAI, Vertex Imagen, pool - identical to the fixed order this replaces - and an
 * operator re-orders it by editing a cost row, not this class. Providers missing from the table
 * keep their old relative order (stable sort), so an empty or unreadable table changes nothing.
 *
 * <p>Gemini (the anonymous {@code /try} demo/pool client, {@link GeminiImageGenerationClient}) is
 * deliberately not in this list - that class serves the unauthenticated platform pool, not a
 * tenant's own BYOK credential, and has no {@link ImageGenerationClient} shape to dispatch through
 * this registry the way OpenAI/Vertex do.
 *
 * <p><b>Quota gating.</b> {@link OpenAiImageGenerationClient#generate} already calls {@link
 * ImageGenerationQuotaService#checkAllowed} internally (see its own 2026-07-27 billing-audit
 * javadoc), so this class does not double-check quota on that branch. The Vertex branch goes
 * through {@link ImageGenerationClientRegistry}, whose {@link OpenAiCreativeImageClient} sibling
 * {@link VertexImageGenerationClient} is a raw registry client with no quota awareness of its own
 * (see {@code OpenAiCreativeImageClient}'s javadoc: "this registry path isn't wired to any
 * controller yet, so there's no tenant plan to gate against") - this class is the first real caller
 * of that path, so it applies {@link ImageGenerationQuotaService} itself before the call, closing
 * the same billing gap for the second provider rather than reintroducing it.
 *
 * <p>Provider availability is checked via the cheap, non-throwing {@link
 * LlmProviderCredentialRepository#existsByProviderNameAndStatus} rather than the throwing {@link
 * ProviderCredentialResolver#resolveActiveKey} - the same "cheap boolean gate, throwing resolver
 * only at actual call time" split {@code AgentToolProvider}'s optional-tool gates use, and it also
 * sidesteps a real ordering hazard: {@code OpenAiImageGenerationClient#generate} checks quota
 * BEFORE resolving its credential, so calling it speculatively and catching a "not active" failure
 * would surface a quota error for a tenant who was never going to use OpenAI's quota in the first
 * place, whenever they are simultaneously over cap and missing an OpenAI key.
 */
@Service
public class ImageGenerationOrchestratorService {

    private final LlmProviderCredentialRepository credentialRepository;
    private final ProviderCredentialResolver credentialResolver;
    private final OpenAiImageGenerationClient openAiClient;
    private final ImageGenerationClientRegistry registry;
    // The platform-pool image client - the SAME Gemini API-key pool that powers
    // FREE-tier chat and the /try demo (proven-working, correct :generateContent
    // + Gemini image model pairing). Injected directly because the pool path
    // needs no tenant BYOK key, so it can't go through
    // registry.generate(provider, apiKey, ...). generate_video is deliberately
    // NOT given this pool treatment (Veo is billed per-second, too expensive to
    // subsidise) - see AgentToolProvider#resolveVideoGenTools.
    private final GeminiImageGenerationClient poolImageClient;
    private final ImageGenerationQuotaService quotaService;
    private final TenantLimits tenantLimits;
    private final PlatformSettingsService platformSettings;
    private final ObjectProvider<MediaModelCostService> modelCostService;
    private final String vertexModelFallback;
    private final String poolImageModelFallback;

    /**
     * BYOK providers this class can dispatch to, in the pre-cost-table order - the tie-break when
     * the cost table does not list one.
     */
    private static final List<ProviderName> BYOK_PROVIDERS =
            List.of(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX);

    public ImageGenerationOrchestratorService(
            LlmProviderCredentialRepository credentialRepository,
            ProviderCredentialResolver credentialResolver,
            OpenAiImageGenerationClient openAiClient,
            ImageGenerationClientRegistry registry,
            GeminiImageGenerationClient poolImageClient,
            ImageGenerationQuotaService quotaService,
            TenantLimits tenantLimits,
            PlatformSettingsService platformSettings,
            ObjectProvider<MediaModelCostService> modelCostService,
            @Value("${app.image.generation.vertex-model:imagen-3.0-generate-002}")
                    String defaultVertexModelFallback,
            @Value("${app.image.generation.pool-model:gemini-2.5-flash-image}")
                    String poolImageModelFallback) {
        this.credentialRepository = credentialRepository;
        this.credentialResolver = credentialResolver;
        this.openAiClient = openAiClient;
        this.registry = registry;
        this.poolImageClient = poolImageClient;
        this.quotaService = quotaService;
        this.tenantLimits = tenantLimits;
        this.platformSettings = platformSettings;
        this.modelCostService = modelCostService;
        this.vertexModelFallback = defaultVertexModelFallback;
        this.poolImageModelFallback = poolImageModelFallback;
    }

    /**
     * Model for a provider: an explicit platform setting wins (an operator's deliberate override),
     * else the cheapest model the cost table lists for that provider, else the property default.
     */
    private String modelFor(String settingKey, String costTableProvider, String propertyFallback) {
        MediaModelCostService costs = modelCostService.getIfAvailable();
        String costPick =
                costs == null
                        ? propertyFallback
                        : costs.cheapestModel(MediaModality.IMAGE, costTableProvider)
                                .orElse(propertyFallback);
        String configured = platformSettings.getString(settingKey, costPick);
        return configured == null || configured.isBlank() ? costPick : configured.trim();
    }

    /** Active BYOK providers, cheapest first; package-private for the ordering test. */
    List<ProviderName> activeByokProvidersCheapestFirst() {
        List<ProviderName> active = new ArrayList<>();
        for (ProviderName provider : BYOK_PROVIDERS) {
            if (credentialRepository.existsByProviderNameAndStatus(
                    provider, ProviderStatus.ACTIVE)) {
                active.add(provider);
            }
        }
        MediaModelCostService costs = modelCostService.getIfAvailable();
        if (costs != null && active.size() > 1) {
            // List.sort is stable: unlisted providers (rank MAX_VALUE) keep BYOK_PROVIDERS order.
            active.sort(
                    Comparator.comparingInt(
                            p -> costs.providerRank(MediaModality.IMAGE, p.name())));
        }
        return active;
    }

    public record GeneratedImageResult(
            byte[] bytes, String contentType, String model, String provider) {}

    /**
     * Cheap, non-throwing - whether ANY tenant-BYOK provider this class knows how to use is active
     * for this tenant.
     */
    public boolean isAnyProviderActive() {
        return HotpathLookups.tenant(
                "imagegen.any-provider-active",
                () ->
                        credentialRepository.existsByProviderNameAndStatus(
                                        ProviderName.OPENAI, ProviderStatus.ACTIVE)
                                || credentialRepository.existsByProviderNameAndStatus(
                                        ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE));
    }

    /**
     * Cheap, non-throwing - whether the platform Vertex image pool can serve a generation with NO
     * tenant BYOK key. Mirrors {@code VertexVeoVideoGenerationClient#isPoolAvailable} so image
     * generation gets the same platform-pool fallback video has, letting a FREE/pool-only tenant
     * use {@code generate_image}. The per-tenant daily/monthly image quota ({@link
     * ImageGenerationQuotaService}) is what bounds the platform spend.
     */
    public boolean isPoolAvailable() {
        // The answer is the same for every tenant and reads the hosted pool settings and chain
        // (decrypting every key) on each call, so it goes through the platform lookup cache. A
        // write to the pool entities evicts it (@PlatformConfigEntity); TTL 0 reads every time.
        return HotpathLookups.platform("imagegen.pool-available", "", poolImageClient::isAvailable);
    }

    /**
     * @throws ImageGenNotConfiguredException if no provider in the priority list is active for this
     *     tenant.
     */
    public GeneratedImageResult generate(String prompt, String requestedSize) {
        List<ProviderName> byok = activeByokProvidersCheapestFirst();
        ProviderName chosen = byok.isEmpty() ? null : byok.get(0);
        if (chosen == ProviderName.OPENAI) {
            OpenAiImageGenerationClient.GeneratedImageBytes result =
                    openAiClient.generate(prompt, null, requestedSize);
            return new GeneratedImageResult(
                    result.bytes(),
                    result.contentType(),
                    result.model(),
                    ProviderName.OPENAI.name());
        }
        if (chosen == ProviderName.GOOGLE_VERTEX) {
            String apiKey = credentialResolver.resolveActiveKey(ProviderName.GOOGLE_VERTEX);
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
            quotaService.checkAllowed(plan);
            ImageGenerationClient.GeneratedImage result =
                    registry.generate(
                            ProviderName.GOOGLE_VERTEX,
                            apiKey,
                            prompt,
                            modelFor(
                                    "image.generation.vertex-model",
                                    ProviderName.GOOGLE_VERTEX.name(),
                                    vertexModelFallback));
            return new GeneratedImageResult(
                    result.bytes(),
                    result.contentType(),
                    result.model(),
                    ProviderName.GOOGLE_VERTEX.name());
        }
        // Platform-pool fallback (no tenant BYOK key) - the FREE/pool path, same
        // as generate_video's pool submit. Quota-gated exactly like the BYOK
        // Vertex branch above, since here the PLATFORM is paying: the per-tenant
        // daily/monthly image cap is what keeps that spend bounded.
        if (poolImageClient.isAvailable()) {
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
            quotaService.checkAllowed(plan);
            GeminiImageGenerationClient.GeneratedImageBytes result =
                    poolImageClient.generate(
                            prompt,
                            modelFor(
                                    "image.generation.pool-model",
                                    "GOOGLE",
                                    poolImageModelFallback));
            return new GeneratedImageResult(
                    result.bytes(), result.mimeType(), result.model(), "GOOGLE");
        }
        throw new ImageGenNotConfiguredException(
                "No image generation provider is configured for this tenant (needs an active OpenAI"
                        + " or Google Vertex credential, or the platform image pool).");
    }
}
