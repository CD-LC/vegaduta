package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Mirrors {@code core.imagegen.ImageGenerationClientRegistry} /
 * {@code core.chat.ChatClientRegistry} - one {@link ProviderCachingStrategy}
 * bean per caching-capable {@link ProviderName}, collected by Spring's
 * ordinary {@code List<T>} constructor injection (every {@code @Component}
 * implementing the interface lands in this list automatically - "registration"
 * is just implementing the interface and annotating the class, nothing calls
 * a {@code register(...)} method anywhere). A provider with no caching
 * support simply has no bean implementing the interface, so it's absent from
 * the map rather than present-and-disabled.
 *
 * <p>Existence purpose: any caller - most notably a future cost-transparency
 * panel per the roadmap - can ask "which providers have caching active" or
 * "is this provider+model combination cached" without hardcoding a provider
 * list that will drift the moment a new strategy is added.
 */
@Component
public class ProviderCachingRegistry {

    private final Map<ProviderName, ProviderCachingStrategy<?>> strategiesByProvider;

    public ProviderCachingRegistry(List<ProviderCachingStrategy<?>> strategies) {
        this.strategiesByProvider = strategies.stream()
            .collect(Collectors.toMap(ProviderCachingStrategy::supportedProvider, Function.identity()));
    }

    /** Whether any caching strategy is registered for this provider at all. */
    public boolean supports(ProviderName providerName) {
        return strategiesByProvider.containsKey(providerName);
    }

    /**
     * Whether caching is currently enabled for this provider+model. {@code
     * false} for an unregistered provider - no "not supported" special case
     * needed by callers.
     */
    public boolean isEnabled(ProviderName providerName, String model) {
        ProviderCachingStrategy<?> strategy = strategiesByProvider.get(providerName);
        return strategy != null && strategy.isEnabled(model);
    }

    /**
     * Every provider whose caching strategy is registered AND currently
     * enabled (ignoring model-level gating - see {@link
     * ProviderCachingStrategy#isEnabled}'s {@code null}-model contract).
     * This is the enumeration a transparency panel wants: "which providers
     * currently have caching active."
     */
    public Set<ProviderName> activeProviders() {
        return strategiesByProvider.values().stream()
            .filter(strategy -> strategy.isEnabled(null))
            .map(ProviderCachingStrategy::supportedProvider)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
