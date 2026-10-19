package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.progress.JobProgressRecorder;
import com.agenticai.core.agents.job.progress.ProgressSource;
import com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance;
import com.agenticai.core.agents.tools.web.DeepResearchService;
import com.agenticai.core.agents.tools.web.DeepResearchToolCallback;
import com.agenticai.core.agents.tools.web.WebSearchProviderRegistry;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The optional deep_research collaborators of {@link BuiltInWebTools}: the "Deep research"
 * per-agent capability rows, the per-caller daily search allowance, the job progress trail and the
 * multi-provider search registry. All attached by {@link ResearchToolWiring}; each is optional
 * (null/absent = that feature off), exactly as when they were setters on {@code
 * AgentIntegrationToolResolver}.
 */
final class ResearchCapabilitySupport {

    private volatile ObjectProvider<AgentCapabilityStateRepository> capabilityStates;
    private volatile ObjectProvider<DeepResearchDailyAllowance> dailyAllowance;
    private volatile ObjectProvider<JobProgressRecorder> jobProgress;
    private volatile ObjectProvider<WebSearchProviderRegistry> webSearchRegistry;

    // The switch is read on every ordinary turn of every agent that has web_search (hot path), so
    // the answer is cached per agent for 30s. DeepResearchCapability#apply evicts the agent's entry
    // on this pod, so flipping the switch takes effect on the next turn; another pod sees it after
    // at most the TTL.
    private final TtlCache<Boolean> capabilityCache =
            new TtlCache<>(DeepResearchCapability.CACHE_NAME, Duration.ofSeconds(30), 10_000);

    void setCapabilityStates(ObjectProvider<AgentCapabilityStateRepository> states) {
        this.capabilityStates = states;
    }

    void setDailyAllowance(ObjectProvider<DeepResearchDailyAllowance> allowance) {
        this.dailyAllowance = allowance;
    }

    void setJobProgress(ObjectProvider<JobProgressRecorder> jobProgress) {
        this.jobProgress = jobProgress;
    }

    void setWebSearchRegistry(ObjectProvider<WebSearchProviderRegistry> registry) {
        this.webSearchRegistry = registry;
    }

    /**
     * The registry only when configuration actually departs from plain Brave; null = use the
     * original path.
     */
    WebSearchProviderRegistry activeSearchRegistry() {
        ObjectProvider<WebSearchProviderRegistry> provider = webSearchRegistry;
        WebSearchProviderRegistry registry = provider == null ? null : provider.getIfAvailable();
        return registry != null && registry.multiProviderActive() ? registry : null;
    }

    /**
     * Whether the agent's "Deep research" capability switch is on AND the deployment flag is on. At
     * most one primary-key read per agent per 30s; never throws (a failed read is "not opted in"
     * and is not cached).
     */
    boolean capabilityEnabled(UUID agentId, boolean deploymentEnabled) {
        ObjectProvider<AgentCapabilityStateRepository> statesProvider = capabilityStates;
        if (agentId == null || statesProvider == null || !deploymentEnabled) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    capabilityCache.get(
                            DeepResearchCapability.cacheKey(agentId),
                            () -> {
                                var states = statesProvider.getIfAvailable();
                                if (states == null) {
                                    return null; // not cached: the repository may not be up yet
                                }
                                return states.findById(
                                                new AgentCapabilityStateId(
                                                        agentId,
                                                        DeepResearchCapability.CAPABILITY_KEY))
                                        .map(AgentCapabilityState::isEnabled)
                                        .orElse(false);
                            }));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The daily search allowance of whoever this resolution runs for. Identity is captured HERE, on
     * the resolving thread (request thread or job worker, both carry TenantContext), never from a
     * tool argument: the user when there is one, otherwise the agent. Null = no allowance wired.
     */
    DeepResearchToolCallback.SearchAllowance searchAllowance(UUID agentId) {
        ObjectProvider<DeepResearchDailyAllowance> provider = dailyAllowance;
        DeepResearchDailyAllowance allowance = provider == null ? null : provider.getIfAvailable();
        if (allowance == null) {
            return null;
        }
        TenantContext context = TenantContext.current().orElse(null);
        return allowance.forCaller(
                context == null ? null : context.getTenantId(),
                context == null ? null : context.getSubject(),
                agentId);
    }

    /**
     * Where a deep_research call running under {@code sessionId} reports its phases: the job's
     * progress trail when the session is a job session ({@code job:<uuid>}), otherwise nowhere (a
     * chat turn already shows the tool step in its activity trace). Best-effort: the returned sink
     * never throws.
     */
    DeepResearchService.ProgressSink progressSink(String sessionId) {
        ObjectProvider<JobProgressRecorder> provider = jobProgress;
        Optional<UUID> jobId = AgentJobSession.jobIdOf(sessionId);
        if (provider == null || jobId.isEmpty()) {
            return DeepResearchService.ProgressSink.NONE;
        }
        UUID id = jobId.get();
        return (note, percent) -> {
            try {
                var service = provider.getIfAvailable();
                if (service != null) {
                    service.record(id, note, percent, ProgressSource.RUNTIME);
                }
            } catch (RuntimeException e) {
                // progress is commentary; the research call carries on
            }
        };
    }
}
