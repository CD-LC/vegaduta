package com.agenticai.core.agents.research;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobOutcomeListener;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The run counter only keeps runs that worked. A research job that ends FAILED (timeout, orphaned,
 * schema violation, provider error...), is CANCELLED, or "succeeds" without a report gives its slot
 * back, so the user's "runs left today" never drops for a run that returned nothing.
 *
 * <p>Applies to every job of an agent with the deep-research capability, matching {@link
 * ResearchAgentSubmitGate}, which is what counted it at submit.
 */
@Component
public class ResearchRunOutcomeListener implements AgentJobOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(ResearchRunOutcomeListener.class);

    private final AgentCapabilityStateRepository capabilityStates;
    private final ResearchRunUsageService usage;
    private final ObjectMapper objectMapper;

    public ResearchRunOutcomeListener(
            AgentCapabilityStateRepository capabilityStates,
            ResearchRunUsageService usage,
            ObjectMapper objectMapper) {
        this.capabilityStates = capabilityStates;
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onTerminal(AgentJob job) {
        String subject = job.getRequestedBy();
        if (subject == null || subject.isBlank() || job.getTenantId() == null) {
            return;
        }
        // Only API submissions are counted at submit; schedules and triggers never reserved a slot.
        if (job.getOrigin() != AgentJobOrigin.API || countsAsRun(job)) {
            return;
        }
        // The window is 24h from the first run; a slot counted in an earlier window is not ours to
        // refund.
        Instant created = job.getCreatedAt();
        if (created != null && created.isBefore(Instant.now().minus(Duration.ofHours(24)))) {
            return;
        }
        if (!hasDeepResearch(job)) {
            return;
        }
        log.info(
                "Research job {} ended {} ({}) - not counted against {}'s daily runs",
                job.getId(),
                job.getStatus(),
                job.getErrorCode(),
                subject);
        usage.release(job.getTenantId(), subject);
    }

    /** True only for a success that really carries a report. */
    boolean countsAsRun(AgentJob job) {
        if (job.getStatus() != AgentJobStatus.SUCCEEDED) {
            return false;
        }
        try {
            JsonNode result =
                    job.getResultJson() == null ? null : objectMapper.readTree(job.getResultJson());
            return result != null && !result.path("reportMarkdown").asString("").isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasDeepResearch(AgentJob job) {
        try {
            return capabilityStates
                    .findById(
                            new AgentCapabilityStateId(
                                    job.getAgentId(), DeepResearchCapability.CAPABILITY_KEY))
                    .map(AgentCapabilityState::isEnabled)
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
