package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolCredentialStatusResponse;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The labelled per-agent opt-in for {@code deep_research} + {@code verify_citations}. Before this
 * the only explicit opt-in was ticking the two raw tool names in the advanced tool picker.
 *
 * <p>Row-backed ({@code agent_capability}, no migration). The runtime gate lives in {@code
 * AgentIntegrationToolResolver#researchToolsWanted}: an enabled row offers the two tools on every
 * turn of that agent (no research-looking message needed) and counts as an allow for exactly those
 * two names when the agent has a tool allow-list. The deployment flag ({@code
 * app.research.deep.enabled}, default OFF) and the platform search key still gate everything: with
 * either missing the row is inert.
 */
@Component
public class DeepResearchCapability extends StoredAgentCapability {

    /** Wire key and {@code agent_capability.capability_key} value. */
    public static final String CAPABILITY_KEY = "deep-research";

    /**
     * Name of the short-TTL cache the tool resolver keeps of this switch (it is read on every chat
     * turn). {@link #apply} evicts the agent's entry so a flip is seen on the next turn on this
     * pod.
     */
    public static final String CACHE_NAME = "research.capability-flag";

    static final String HINT_DISABLED = "Deep research is not switched on for this deployment yet.";
    static final String HINT_NO_SEARCH_KEY =
            "Needs the platform web search key - ask your platform admin to add one. "
                    + "The switch attaches the tools once that is set up.";

    private final boolean deepResearchEnabled;
    private final ObjectProvider<PlatformToolCredentialService> platformToolCredentialService;

    public DeepResearchCapability(
            AgentCapabilityStateRepository states,
            @Value("${app.research.deep.enabled:false}") boolean deepResearchEnabled,
            ObjectProvider<PlatformToolCredentialService> platformToolCredentialService) {
        super(states);
        this.deepResearchEnabled = deepResearchEnabled;
        this.platformToolCredentialService = platformToolCredentialService;
    }

    @Override
    public String key() {
        return CAPABILITY_KEY;
    }

    @Override
    public String label() {
        return "Deep research";
    }

    @Override
    public String description() {
        return "Multi-source web research: several searches in parallel, the best pages read, and a report "
                + "with quoted citations. The citation check compares wording only - it confirms a quote appears "
                + "in the source, not that the source is right.";
    }

    @Override
    public String category() {
        return "tools";
    }

    @Override
    public CapabilityAvailability availability() {
        if (!deepResearchEnabled) {
            return CapabilityAvailability.UNAVAILABLE;
        }
        return searchKeyPresent()
                ? CapabilityAvailability.AVAILABLE
                : CapabilityAvailability.NEEDS_SETUP;
    }

    @Override
    public String availabilityHint() {
        return switch (availability()) {
            case UNAVAILABLE -> HINT_DISABLED;
            case NEEDS_SETUP -> HINT_NO_SEARCH_KEY;
            case AVAILABLE -> null;
        };
    }

    /**
     * Cache key of one agent's switch: tenant first (the {@link TtlCache} convention), then the
     * agent.
     */
    public static String cacheKey(UUID agentId) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        return TtlCache.key(tenantId, String.valueOf(agentId));
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        super.apply(agent, enabled);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String agentId = String.valueOf(agent.getId());
        // Now, and again once the surrounding transaction has committed: a turn resolving between
        // this
        // write and its commit would otherwise re-cache the old value for a full TTL.
        TtlCache.evictPrefix(CACHE_NAME, tenantId, agentId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            TtlCache.evictPrefix(CACHE_NAME, tenantId, agentId);
                        }
                    });
        }
    }

    /**
     * Existence check only - the status read does not decrypt the key, and this runs on every
     * capability-catalogue listing. Non-throwing.
     */
    private boolean searchKeyPresent() {
        try {
            ObjectProvider<WebSearchAvailability> provider = webSearchRegistry;
            WebSearchAvailability registry = provider == null ? null : provider.getIfAvailable();
            return registry == null
                    ? braveKeyPresent()
                    : registry.searchAvailable(this::braveKeyPresent);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean braveKeyPresent() {
        PlatformToolCredentialService credentials = platformToolCredentialService.getIfAvailable();
        if (credentials == null) {
            return false;
        }
        PlatformToolCredentialStatusResponse status =
                credentials.status(PlatformToolKey.BRAVE_SEARCH);
        return status != null && status.configured();
    }

    // Multi-provider web search (app.web-search.*), optional and setter-injected so the constructor
    // stays
    // untouched. Absent or inactive (the shipped default) = the original Brave-key check.
    // The registry lives in core/agents-browser and is reached through the WebSearchAvailability
    // port.
    private ObjectProvider<WebSearchAvailability> webSearchRegistry;

    @Autowired(required = false)
    void setWebSearchRegistry(ObjectProvider<WebSearchAvailability> webSearchRegistry) {
        this.webSearchRegistry = webSearchRegistry;
    }
}
