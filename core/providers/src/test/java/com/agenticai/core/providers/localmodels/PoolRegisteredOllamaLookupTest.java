package com.agenticai.core.providers.localmodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import com.agenticai.core.security.net.SsrfGuard;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The embeddings path asks this which Ollama the install registered: the one a pool row points at. */
class PoolRegisteredOllamaLookupTest {

    private static LocalModelOllamaGateway gateway(String baseUrl) {
        return new LocalModelOllamaGateway(SsrfGuard.strict(), baseUrl);
    }

    private static PlatformHostedLlmProvider row(String baseUrl) {
        return new PlatformHostedLlmProvider("Local", baseUrl, "qwen3.5:4b", "enc", true, 1, null, null);
    }

    private static PlatformHostedLlmProviderRepository pool(PlatformHostedLlmProvider... rows) {
        PlatformHostedLlmProviderRepository repository = mock(PlatformHostedLlmProviderRepository.class);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(rows));
        return repository;
    }

    @Test
    void aPoolRowPointingAtTheManagedOllamaCounts() {
        PoolRegisteredOllamaLookup lookup =
                new PoolRegisteredOllamaLookup(
                        gateway("http://host.docker.internal:11434"), pool(row("http://host.docker.internal:11434/v1")));

        assertThat(lookup.baseUrl()).contains("http://host.docker.internal:11434");
    }

    @Test
    void aRowPointingElsewhereDoesNot() {
        PoolRegisteredOllamaLookup lookup =
                new PoolRegisteredOllamaLookup(gateway("http://ollama:11434"), pool(row("https://api.example.com/v1")));

        assertThat(lookup.baseUrl()).isEmpty();
    }

    @Test
    void noPoolRowsMeansNothingIsRegistered() {
        PoolRegisteredOllamaLookup lookup = new PoolRegisteredOllamaLookup(gateway("http://ollama:11434"), pool());

        assertThat(lookup.baseUrl()).isEmpty();
    }

    @Test
    void aBlankManagedUrlIsNeverRegistered() {
        PoolRegisteredOllamaLookup lookup = new PoolRegisteredOllamaLookup(gateway(""), pool(row("http://ollama:11434")));

        assertThat(lookup.baseUrl()).isEmpty();
    }
}
