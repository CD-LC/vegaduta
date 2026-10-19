package com.agenticai.core.providers.localmodels;

import com.agenticai.core.knowledge.embedding.RegisteredOllamaLookup;
import com.agenticai.core.providers.HostedPoolEndpointProbe;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tells the embeddings path which Ollama this install registered: the model server the local-model
 * gateway manages, when an enabled platform-pool row points at it (matched by normalized base URL, as
 * {@code LocalModelsService} does for the serving row). A row that happens to share a label but points
 * elsewhere does not count, and neither does an Ollama nobody registered.
 */
@Component
class PoolRegisteredOllamaLookup implements RegisteredOllamaLookup {

    private final LocalModelOllamaGateway gateway;
    private final PlatformHostedLlmProviderRepository pool;

    PoolRegisteredOllamaLookup(LocalModelOllamaGateway gateway, PlatformHostedLlmProviderRepository pool) {
        this.gateway = gateway;
        this.pool = pool;
    }

    @Override
    public Optional<String> baseUrl() {
        String base = HostedPoolEndpointProbe.normalizeBaseUrl(gateway.baseUrl());
        if (base == null || base.isBlank()) {
            return Optional.empty();
        }
        boolean registered =
                pool.findAllByEnabledTrueOrderByPriorityAsc().stream()
                        .anyMatch(
                                row ->
                                        row.getBaseUrl() != null
                                                && base.equalsIgnoreCase(HostedPoolEndpointProbe.normalizeBaseUrl(row.getBaseUrl())));
        return registered ? Optional.of(base) : Optional.empty();
    }
}
