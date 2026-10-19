package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.LiveProviderModelFetcher.LiveModel;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProviderModelListingService}'s contract in one line: with a usable
 * key the LIVE list defines which models exist (enriched from the catalog),
 * and in every other case the response is the cached catalog exactly as
 * before the live path existed - the compatibility half is what the 8
 * existing frontend call sites of {@code GET /api/providers/{provider}/models}
 * depend on, so it gets equal coverage with the merge half.
 */
class ProviderModelListingServiceTest {

    private ProviderModelCatalogService catalogService;
    private LiveProviderModelFetcher liveFetcher;
    private LlmProviderCredentialRepository credentialRepository;
    private EncryptionService encryptionService;
    private ProviderModelListingService service;

    @BeforeEach
    void setUp() {
        catalogService = mock(ProviderModelCatalogService.class);
        liveFetcher = mock(LiveProviderModelFetcher.class);
        credentialRepository = mock(LlmProviderCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        service = new ProviderModelListingService(
            catalogService, liveFetcher, credentialRepository, encryptionService
        );
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubActiveKey(ProviderName provider, String decryptedKey) {
        LlmProviderCredential credential = new LlmProviderCredential(provider, "enc:" + decryptedKey, ProviderStatus.ACTIVE);
        when(credentialRepository.findByProviderName(provider)).thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("enc:" + decryptedKey)).thenReturn(decryptedKey);
    }

    // --- no key: catalog served untouched, live fetch never attempted --------

    @Test
    void withoutAnActiveKeyTheCatalogIsServedExactlyAsBefore() {
        List<ProviderModelResponse> catalog = List.of(
            new ProviderModelResponse("grok-4.5", "Grok 4.5", 500_000, true, false, false, "summary")
        );
        when(catalogService.listModels(ProviderName.GROK)).thenReturn(catalog);
        when(credentialRepository.findByProviderName(ProviderName.GROK)).thenReturn(Optional.empty());

        List<ProviderModelResponse> models = service.listModels(ProviderName.GROK);

        assertEquals(catalog, models);
        assertEquals(ProviderModelResponse.SOURCE_CATALOG, models.get(0).source());
        verify(liveFetcher, never()).fetch(any(), anyString());
    }

    // --- live defines existence, catalog enriches -----------------------------

    @Test
    void liveListDefinesWhichModelsExistAndCatalogEnrichesMatchingRows() {
        // Catalog knows grok-4.5 (with a generated blurb + image flag) and a
        // stale entry the vendor no longer serves; live knows grok-4.5 and a
        // brand-new id the catalog hasn't caught up on.
        when(catalogService.listModels(ProviderName.GROK)).thenReturn(List.of(
            new ProviderModelResponse("grok-4.5", "Grok 4.5", 500_000, true, false, false, "generated blurb"),
            new ProviderModelResponse("grok-stale", "Grok Stale", 100_000, false, false, false, "stale blurb")
        ));
        stubActiveKey(ProviderName.GROK, "xai-key");
        when(liveFetcher.fetch(ProviderName.GROK, "xai-key")).thenReturn(List.of(
            new LiveModel("grok-4.5", "grok-4.5", null, null, null, null),
            new LiveModel("grok-5-brand-new", "grok-5-brand-new", "Fresh model", 2_000_000, true, null)
        ));

        List<ProviderModelResponse> models = service.listModels(ProviderName.GROK);

        assertEquals(2, models.size());
        // Sorted by model id; the stale catalog-only row is gone.
        ProviderModelResponse enriched = models.get(0);
        assertEquals("grok-4.5", enriched.modelId());
        assertEquals(ProviderModelResponse.SOURCE_LIVE, enriched.source());
        // Catalog enrichment: display name, context window, image flag and
        // the generated capability blurb all survive the merge.
        assertEquals("Grok 4.5", enriched.displayName());
        assertEquals(500_000, enriched.contextWindow());
        assertTrue(enriched.supportsImageInput());
        assertEquals("generated blurb", enriched.capabilitySummary());

        ProviderModelResponse brandNew = models.get(1);
        assertEquals("grok-5-brand-new", brandNew.modelId());
        assertEquals("Fresh model", brandNew.description());
        assertEquals(2_000_000, brandNew.contextWindow());
        assertTrue(brandNew.supportsImageInput());
        // Live-only row still gets a non-blank deterministic summary.
        assertTrue(brandNew.capabilitySummary() != null && !brandNew.capabilitySummary().isBlank());
        assertNull(models.get(0).deprecated());
    }

    // --- live failure: catalog fallback ---------------------------------------

    @Test
    void anEmptyLiveFetchFallsBackToTheCatalog() {
        List<ProviderModelResponse> catalog = List.of(
            new ProviderModelResponse("deepseek-v4-flash", "DeepSeek V4 Flash", 1_000_000, false, false, false, "summary")
        );
        when(catalogService.listModels(ProviderName.DEEPSEEK)).thenReturn(catalog);
        stubActiveKey(ProviderName.DEEPSEEK, "ds-key");
        when(liveFetcher.fetch(ProviderName.DEEPSEEK, "ds-key")).thenReturn(List.of());

        assertEquals(catalog, service.listModels(ProviderName.DEEPSEEK));
    }

    // --- OpenRouter: live without any key -------------------------------------

    @Test
    void openRouterIsFetchedLiveWithoutAnyCredential() {
        when(catalogService.listModels(ProviderName.OPENROUTER)).thenReturn(List.of());
        when(liveFetcher.fetch(ProviderName.OPENROUTER, "")).thenReturn(List.of(
            new LiveModel("vendor/model-x", "Model X", null, null, null, null)
        ));

        List<ProviderModelResponse> models = service.listModels(ProviderName.OPENROUTER);

        assertEquals(1, models.size());
        assertEquals(ProviderModelResponse.SOURCE_LIVE, models.get(0).source());
        verify(credentialRepository, never()).findByProviderName(any());
    }

    // --- TTL cache: one vendor round trip per tenant+provider window ----------

    @Test
    void repeatedRequestsWithinTheTtlHitTheCacheNotTheVendor() {
        when(catalogService.listModels(ProviderName.GROK)).thenReturn(List.of());
        stubActiveKey(ProviderName.GROK, "xai-key");
        when(liveFetcher.fetch(ProviderName.GROK, "xai-key")).thenReturn(List.of(
            new LiveModel("grok-4.5", "grok-4.5", null, null, null, null)
        ));

        service.listModels(ProviderName.GROK);
        service.listModels(ProviderName.GROK);
        service.listModels(ProviderName.GROK);

        verify(liveFetcher, times(1)).fetch(ProviderName.GROK, "xai-key");
    }

    // --- non-ACTIVE credential: treated as no key -----------------------------

    @Test
    void anInvalidCredentialServesTheCatalogOnly() {
        List<ProviderModelResponse> catalog = List.of(
            new ProviderModelResponse("mistral-small-latest", "Mistral Small", 128_000, true, false, false, "summary")
        );
        when(catalogService.listModels(ProviderName.MISTRAL)).thenReturn(catalog);
        LlmProviderCredential invalid = new LlmProviderCredential(ProviderName.MISTRAL, "enc:key", ProviderStatus.INVALID);
        when(credentialRepository.findByProviderName(ProviderName.MISTRAL)).thenReturn(Optional.of(invalid));

        assertEquals(catalog, service.listModels(ProviderName.MISTRAL));
        verify(liveFetcher, never()).fetch(any(), anyString());
    }
}
