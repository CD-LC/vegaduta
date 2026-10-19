package com.agenticai.core.providers;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderCredentialRepository extends JpaRepository<LlmProviderCredential, UUID> {

    Optional<LlmProviderCredential> findByProviderName(ProviderName providerName);

    // Ask-AI helper (AssistService) needs "any usable provider", not one
    // specific to an agent - oldest-activated-first is an arbitrary but
    // stable tie-breaker so repeated calls don't bounce between providers.
    Optional<LlmProviderCredential> findFirstByStatusOrderByCreatedAtAsc(ProviderStatus status);

    // Every usable credential, oldest first - ProviderCredentialResolver ranks
    // these by the tenant's own call history (ProviderUsageSignal) so "any
    // usable provider" is one that has been answering, not just the oldest row.
    java.util.List<LlmProviderCredential> findByStatusOrderByCreatedAtAsc(ProviderStatus status);

    // Master-Roadmap-2026.md item #3 (image generation) - AgentToolProvider's
    // resolveImageGenTools tool-availability gate needs a cheap, non-throwing
    // "does this tenant have an ACTIVE OpenAI credential" check, the same
    // "cheap boolean gate, throwing resolver only at actual call time" split
    // every other optional-tool gate in that class already uses (see
    // resolveSarvamTools' javadoc) - never resolveActiveKey(), which throws.
    boolean existsByProviderNameAndStatus(ProviderName providerName, ProviderStatus status);
}
