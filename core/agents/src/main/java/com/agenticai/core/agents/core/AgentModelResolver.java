package com.agenticai.core.agents.core;

import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves which model an agent actually runs: the per-provider default for a new agent, the
 * effective model for a chat turn (self-healing a null persisted model, redirecting a vendor-retired
 * one) and whether a provider's catalog knows a model. Behavior moved verbatim from {@link
 * AgentService}, which keeps delegating methods so callers are unchanged.
 */
@Service
public class AgentModelResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentModelResolver.class);

    private final ProviderModelCatalogService providerModelCatalogService;
    private final AgentRepository repository;
    private final AgentDefaults defaults;

    public AgentModelResolver(
            ProviderModelCatalogService providerModelCatalogService,
            AgentRepository repository,
            AgentDefaults defaults) {
        this.providerModelCatalogService = providerModelCatalogService;
        this.repository = repository;
        this.defaults = defaults;
    }

    /**
     * The provider's own catalog default (its fast/cheap-keyword model), falling back to the
     * configured platform default only when the catalog is unexpectedly empty for that provider.
     */
    public String resolveDefaultModelFor(ProviderName provider) {
        return providerModelCatalogService.defaultModelFor(provider).orElse(defaults.model());
    }

    /**
     * The model a chat turn should use. A vendor-retired model is substituted at runtime without
     * rewriting the row; a null persisted model is self-healed with a bare save (deliberately not a
     * versioned save: it is a repair on an ordinary chat turn, not a tenant edit).
     */
    @Transactional
    public String resolveEffectiveModel(Agent agent) {
        if (agent.getModel() != null) {
            Optional<String> replacement =
                    providerModelCatalogService.replacementForRetiredModel(agent.getModel());
            if (replacement.isPresent()) {
                log.warn(
                        "Agent {} is configured with retired model '{}' (provider {}) -"
                            + " substituting '{}' for this call. Update the agent's model to clear"
                            + " this warning.",
                        agent.getId(),
                        agent.getModel(),
                        agent.getProviderName(),
                        replacement.get());
                return replacement.get();
            }
            return agent.getModel();
        }
        String resolved = resolveDefaultModelFor(agent.getProviderName());
        agent.setProviderModel(agent.getProviderName(), resolved);
        repository.save(agent);
        log.warn(
                "Agent {} had a null model persisted (likely created before #1088's create()-time"
                        + " fix) - self-healed to '{}' for provider {}",
                agent.getId(),
                resolved,
                agent.getProviderName());
        return resolved;
    }

    /**
     * Whether {@code provider}'s cached model catalog knows {@code model}; side-effect-free. Used to
     * re-check an RL MODEL_ROUTE arm at selection time, never to police {@code agent.model}
     * itself.
     */
    @Transactional
    public boolean providerServesModel(ProviderName provider, String model) {
        if (provider == null || model == null || model.isBlank()) {
            return false;
        }
        return providerModelCatalogService.findModel(provider, model).isPresent();
    }
}
