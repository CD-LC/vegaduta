package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.job.progress.JobProgressRecorder;
import com.agenticai.core.agents.tools.web.DeepResearchDailyAllowance;
import com.agenticai.core.agents.tools.web.WebSearchProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The optional collaborators of the deep_research tools, kept out of {@link BuiltInWebTools}'
 * constructor. {@code BuiltInWebToolConfiguration} hands this bean the web tools it builds; a slim
 * context without this bean simply runs with none of the three (allow-list / intent opt-ins only,
 * no progress notes, no daily allowance).
 *
 * <p>Everything is an {@link ObjectProvider}: nothing is resolved until a tool resolution needs it,
 * so this bean adds no start-up ordering or cycle between the tool provider and the services behind
 * it.
 */
@Component
public class ResearchToolWiring {

    private final ObjectProvider<AgentCapabilityStateRepository> capabilityStates;
    private final ObjectProvider<JobProgressRecorder> jobProgress;
    private final ObjectProvider<DeepResearchDailyAllowance> dailyAllowance;

    public ResearchToolWiring(
            ObjectProvider<AgentCapabilityStateRepository> capabilityStates,
            ObjectProvider<JobProgressRecorder> jobProgress,
            ObjectProvider<DeepResearchDailyAllowance> dailyAllowance) {
        this.capabilityStates = capabilityStates;
        this.jobProgress = jobProgress;
        this.dailyAllowance = dailyAllowance;
    }

    // Multi-provider web search registry (app.web-search.*). Setter-injected and optional so the
    // 3-arg
    // constructor (used by tests) is untouched; absent = the original Brave-only wiring.
    private ObjectProvider<WebSearchProviderRegistry> webSearchRegistry;

    @Autowired(required = false)
    void setWebSearchRegistry(ObjectProvider<WebSearchProviderRegistry> webSearchRegistry) {
        this.webSearchRegistry = webSearchRegistry;
    }

    /**
     * Attaches: the "Deep research" per-agent capability rows, the job progress trail for phase
     * notes (used only when a turn runs under a job session), and the per-caller daily search
     * allowance.
     */
    void attach(BuiltInWebTools webTools) {
        if (webTools == null) {
            return;
        }
        ResearchCapabilitySupport research = webTools.research();
        research.setCapabilityStates(capabilityStates);
        research.setJobProgress(jobProgress);
        research.setDailyAllowance(dailyAllowance);
        research.setWebSearchRegistry(webSearchRegistry);
    }
}
