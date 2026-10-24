package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;

/**
 * {@code app.models.*}: preferred default model per provider, retired-id replacements and keyword orders, driven
 * through the real {@link ProviderModelCatalogService} and {@link HostedPoolRequestResolver}.
 */
class ProviderModelDefaultsPropertiesTest {

    private static ProviderModelDefaultsProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("app.models", Bindable.of(ProviderModelDefaultsProperties.class))
            .orElseGet(ProviderModelDefaultsProperties::new);
    }

    private static List<ProviderModelResponse> models(String... ids) {
        return java.util.Arrays.stream(ids)
            .map(id -> new ProviderModelResponse(id, id, 128_000, false, false, false, null, null, null, null))
            .toList();
    }

    private static ProviderModelCatalogService service(ProviderModelDefaultsProperties props) {
        ProviderModelCatalogService service = new ProviderModelCatalogService(
            mock(ProviderModelCatalogRepository.class), RestClient.builder(), mock(PgAdvisoryJobLock.class));
        service.setModelDefaults(props);
        return service;
    }

    @Test
    void theDefaultsAreTheValuesTheCodeAlwaysCarried() {
        ProviderModelDefaultsProperties props = new ProviderModelDefaultsProperties();

        assertEquals(Optional.of("claude-haiku-4-5-20251001"), props.defaultFor(ProviderName.ANTHROPIC));
        assertEquals(Optional.of("grok-4-1-fast-non-reasoning"), props.defaultFor(ProviderName.GROK));
        assertEquals(Optional.of("qwen3.5:4b"), props.defaultFor(ProviderName.OLLAMA));
        assertEquals(Optional.of("grok-4.3"), props.replacementFor("grok-3"));
        assertEquals(Optional.of("llama-3.1-8b-instant"), props.replacementFor("gemma2-9b-it"));
        assertEquals(
            List.of("flash-lite", "flash", "mini", "haiku", "lite", "8b", "instant", "non-reasoning"),
            props.getDefaultPreferenceKeywords());
        assertEquals(
            List.of("flash-lite", "flash", "mini", "haiku", "lite", "8b", "instant"), props.getHostedPoolAutoKeywords());
    }

    @Test
    void aConfiguredDefaultReplacesOnlyThatProvidersPick() {
        ProviderModelDefaultsProperties props = bind(Map.of("app.models.defaults.grok", "grok-5"));

        assertEquals("grok-5", ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, models("grok-4.3", "grok-5"), props));
        assertEquals(Optional.of("gpt-4o-mini"), props.defaultFor(ProviderName.OPENAI));
    }

    @Test
    void aStalePickStillDegradesToTheKeywordScan() {
        ProviderModelDefaultsProperties props = bind(Map.of("app.models.defaults.grok", "grok-gone"));

        assertEquals(
            "grok-4-mini",
            ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, models("grok-4.3", "grok-4-mini"), props));
    }

    @Test
    void aBlankDefaultMeansNoHandPickedModelForThatProvider() {
        ProviderModelDefaultsProperties props = bind(Map.of("app.models.defaults.grok", ""));

        assertTrue(props.defaultFor(ProviderName.GROK).isEmpty());
        List<ProviderModelResponse> catalog = models("grok-b", "grok-4-1-fast-non-reasoning", "zz-mini");
        assertEquals("grok-4-1-fast-non-reasoning", ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, catalog));
        assertEquals("zz-mini", ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, catalog, props));
    }

    @Test
    void aConfiguredRetirementIsReplacedAndTheBuiltInOnesStay() {
        ProviderModelCatalogService service = service(bind(Map.of("app.models.retired.old-model", "new-model")));

        assertEquals(Optional.of("new-model"), service.replacementForRetiredModel("old-model"));
        assertEquals(Optional.of("grok-4.3"), service.replacementForRetiredModel("grok-3"));
        assertTrue(service.replacementForRetiredModel("claude-haiku-4-5").isEmpty());
    }

    @Test
    void aBlankReplacementWithdrawsABuiltInRetirement() {
        ProviderModelCatalogService service = service(bind(Map.of("app.models.retired.grok-3", "")));

        assertTrue(service.replacementForRetiredModel("grok-3").isEmpty());
    }

    @Test
    void theKeywordOrderIsConfigurable() {
        ProviderModelDefaultsProperties props = bind(Map.of("app.models.default-preference-keywords", "pro,flash"));

        assertEquals(
            "model-pro",
            ProviderModelCatalogService.pickDefaultModel(ProviderName.MISTRAL, models("model-flash", "model-pro"), props));
    }

    @Test
    void theHostedPoolAutoKeywordsAreConfigurable() {
        ProviderModelDefaultsProperties props = bind(Map.of("app.models.hosted-pool-auto-keywords", "turbo"));

        assertEquals(
            "x-turbo",
            HostedPoolRequestResolver.pickBestModel(null, models("a-mini", "x-turbo"), props));
    }

    @Test
    void aConfiguredContextWindowReplacesTheCatalogValueInTheModelList() {
        ProviderModelCatalogRepository repository = mock(ProviderModelCatalogRepository.class);
        org.mockito.Mockito.when(repository.countByProviderName(ProviderName.OPENAI)).thenReturn(2L);
        org.mockito.Mockito.when(repository.findAllByProviderNameOrderByModelIdAsc(ProviderName.OPENAI)).thenReturn(List.of(
            new ProviderModelCatalogEntry(ProviderName.OPENAI, "gpt-5.1", "GPT-5.1", 400_000),
            new ProviderModelCatalogEntry(ProviderName.OPENAI, "gpt-4o", "GPT-4o", 128_000)));
        org.mockito.Mockito.when(repository.findByProviderNameAndModelId(ProviderName.OPENAI, "gpt-5.1")).thenReturn(
            Optional.of(new ProviderModelCatalogEntry(ProviderName.OPENAI, "gpt-5.1", "GPT-5.1", 400_000)));
        ProviderModelCatalogService service =
            new ProviderModelCatalogService(repository, RestClient.builder(), mock(PgAdvisoryJobLock.class));
        service.setModelDefaults(bind(Map.of("app.models.context-windows.[gpt-5.1]", "272000")));

        List<ProviderModelResponse> listed = service.listModels(ProviderName.OPENAI);

        assertEquals(272_000, listed.stream().filter(m -> m.modelId().equals("gpt-5.1")).findFirst().orElseThrow().contextWindow());
        assertEquals(128_000, listed.stream().filter(m -> m.modelId().equals("gpt-4o")).findFirst().orElseThrow().contextWindow());
        assertEquals(272_000, service.findModel(ProviderName.OPENAI, "gpt-5.1").orElseThrow().contextWindow());
    }

    @Test
    void noContextWindowOverrideByDefault() {
        assertTrue(new ProviderModelDefaultsProperties().contextWindowFor("gpt-5.1").isEmpty());
    }
}
