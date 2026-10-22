package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.util.Set;

/** Keyless pool rows (local Ollama and friends): only the explicit entry point allows them. */
class PlatformHostedLlmProviderKeylessTest {

    private PlatformHostedLlmProviderRepository repository;
    private EncryptionService encryptionService;
    private MockRestServiceServer mockServer;
    private PlatformHostedLlmProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHostedLlmProviderRepository.class);
        encryptionService = mock(EncryptionService.class);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service =
                new PlatformHostedLlmProviderService(
                        repository,
                        mock(HostedLlmPoolSettingsRepository.class),
                        encryptionService,
                        builder,
                        mock(ProviderModelCatalogService.class));
        service.setSsrfGuard(
                SsrfGuard.of(
                        Set.of(),
                        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")}));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PlatformHostedLlmProviderUpsertRequest request(String apiKey) {
        return new PlatformHostedLlmProviderUpsertRequest(
                "Local", "https://ollama.example", "llama3.2", apiKey, true, 0, null, null, null);
    }

    @Test
    void regularCreateStillRequiresAKey() {
        assertThrows(ProviderValidationException.class, () -> service.create(request("  ")));
        verify(repository, never()).save(any());
    }

    @Test
    void blankKeyCreateStoresNoSecretAndProbesWithoutAuthorization() {
        mockServer
                .expect(requestTo("https://ollama.example/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(r -> assertFalse(r.getHeaders().containsHeader("Authorization")))
                .andRespond(withSuccess());

        service.createAllowingBlankKey(request("  "));

        mockServer.verify();
        ArgumentCaptor<PlatformHostedLlmProvider> saved =
                ArgumentCaptor.forClass(PlatformHostedLlmProvider.class);
        verify(repository).save(saved.capture());
        assertNull(saved.getValue().getEncryptedApiKey());
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void suppliedKeyIsStillEncryptedAndSentAsBearer() {
        when(encryptionService.encrypt("ollama")).thenReturn("ciphertext");
        mockServer
                .expect(requestTo("https://ollama.example/v1/models"))
                .andExpect(header("Authorization", "Bearer ollama"))
                .andRespond(withSuccess());

        service.createAllowingBlankKey(request("ollama"));

        mockServer.verify();
        ArgumentCaptor<PlatformHostedLlmProvider> saved =
                ArgumentCaptor.forClass(PlatformHostedLlmProvider.class);
        verify(repository).save(saved.capture());
        assertEquals("ciphertext", saved.getValue().getEncryptedApiKey());
    }
}
