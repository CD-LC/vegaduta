package com.agenticai.core.agents.capability;

import java.util.function.BooleanSupplier;

/**
 * Port (ADR-0023): whether platform web search can run, for {@link DeepResearchCapability}'s
 * availability check. Implemented in core/agents-browser by {@code tools.web.WebSearchProviderRegistry}
 * (multi-provider search, {@code app.web-search.*}). No bean = the plain Brave-key check.
 */
public interface WebSearchAvailability {

    /**
     * True when the configured provider chain can search; {@code braveKeyPresent} answers for the
     * plain Brave provider.
     */
    boolean searchAvailable(BooleanSupplier braveKeyPresent);
}
