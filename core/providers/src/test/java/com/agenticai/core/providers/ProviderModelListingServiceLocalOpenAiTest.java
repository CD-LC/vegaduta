package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.LocalOpenAiServerClient.Listing;
import com.agenticai.core.providers.LocalOpenAiServerClient.ServerModel;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** LOCAL_OPENAI's dropdown comes from the tenant's own server, never from the platform catalog. */
class ProviderModelListingServiceLocalOpenAiTest {

    private static final String CRED = new LocalOpenAiCredential("http://localhost:8000/v1", null).encode();

    private ProviderModelCatalogService catalog;
    private LiveProviderModelFetcher liveFetcher;
    private LlmProviderCredentialRepository credentials;
    private EncryptionService encryption;
    private LocalOpenAiServerClient serverClient;
    private ProviderModelListingService service;

    @BeforeEach
    void setUp() {
        catalog = mock(ProviderModelCatalogService.class);
        liveFetcher = mock(LiveProviderModelFetcher.class);
        credentials = mock(LlmProviderCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        serverClient = mock(LocalOpenAiServerClient.class);
        service = new ProviderModelListingService(catalog, liveFetcher, credentials, encryption);
        service.setLocalOpenAiServerClient(serverClient);
        when(catalog.listModels(ProviderName.LOCAL_OPENAI)).thenReturn(List.of());
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void connect() {
        when(credentials.findByProviderName(ProviderName.LOCAL_OPENAI)).thenReturn(Optional.of(
            new LlmProviderCredential(ProviderName.LOCAL_OPENAI, "enc", ProviderStatus.ACTIVE)));
        when(encryption.decrypt("enc")).thenReturn(CRED);
    }

    @Test
    void listsTheServersModelsWithContextWindowsAndDropsEmbeddingModels() {
        connect();
        when(serverClient.listModels(any())).thenReturn(new Listing(200, true, List.of(
            new ServerModel("qwen3-8b", 40960), new ServerModel("text-embedding-nomic", null),
            new ServerModel("llama-3.2-3b", null))));

        List<ProviderModelResponse> models = service.listModels(ProviderName.LOCAL_OPENAI);

        assertThat(models).extracting(ProviderModelResponse::modelId).containsExactly("llama-3.2-3b", "qwen3-8b");
        assertThat(models).allMatch(m -> ProviderModelResponse.SOURCE_LIVE.equals(m.source()));
        assertThat(models.stream().filter(m -> m.modelId().equals("qwen3-8b")).findFirst().orElseThrow().contextWindow())
            .isEqualTo(40960);
        verifyNoInteractions(liveFetcher);
    }

    @Test
    void noConnectionMeansTheEmptyCatalogAndNoOutboundCall() {
        when(credentials.findByProviderName(ProviderName.LOCAL_OPENAI)).thenReturn(Optional.empty());

        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
        verifyNoInteractions(serverClient);
    }

    @Test
    void anUnreachableServerDegradesToTheCatalogInsteadOfFailingTheDropdown() {
        connect();
        when(serverClient.listModels(any())).thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
    }

    @Test
    void anSsrfRefusalDegradesToTheCatalog() {
        connect();
        when(serverClient.listModels(any())).thenThrow(new ProviderValidationException("This base URL is not allowed"));

        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
    }

    @Test
    void aNonModelServerAnswerDegradesToTheCatalog() {
        connect();
        when(serverClient.listModels(any())).thenReturn(new Listing(200, false, List.of()));

        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
    }

    @Test
    void aSuccessfulListIsCachedBrieflyButAFailureIsNot() {
        connect();
        when(serverClient.listModels(any()))
            .thenThrow(new ResourceAccessException("down"))
            .thenReturn(new Listing(200, true, List.of(new ServerModel("m1", null))));

        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
        // The failed attempt was not cached: the next open of the dropdown asks the server again ...
        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).extracting(ProviderModelResponse::modelId).containsExactly("m1");
        // ... and the success is then served from cache.
        assertThat(service.listModels(ProviderName.LOCAL_OPENAI)).hasSize(1);
        verify(serverClient, times(2)).listModels(any());
    }

    @Test
    void withoutTheClientBeanTheListingStillWorksFromTheCatalog() {
        ProviderModelListingService bare = new ProviderModelListingService(catalog, liveFetcher, credentials, encryption);
        connect();

        assertThat(bare.listModels(ProviderName.LOCAL_OPENAI)).isEmpty();
    }
}
