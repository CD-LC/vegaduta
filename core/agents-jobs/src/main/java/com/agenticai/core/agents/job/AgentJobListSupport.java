package com.agenticai.core.agents.job;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Paging and summary helpers shared by {@link AgentJobService}'s list reads. Pure functions of
 * their arguments; split out of the service to keep it readable.
 */
final class AgentJobListSupport {

    private AgentJobListSupport() {}

    /** {@code null} and empty both mean "every status" - see {@link AgentJobService#listForAgent}. */
    static boolean isUnfiltered(Set<AgentJobStatus> statuses) {
        return statuses == null || statuses.isEmpty();
    }

    static List<AgentJobSummaryResponse> summarize(
            AgentRepository agentRepository, List<AgentJob> jobs) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        // One lookup per DISTINCT agent, not per row - a list of fifty jobs
        // across ten agents costs ten reads, not fifty.
        Set<UUID> agentIds = new LinkedHashSet<>(jobs.stream().map(AgentJob::getAgentId).toList());
        Map<UUID, String> names = new HashMap<>();
        for (Agent agent : agentRepository.findAllById(agentIds)) {
            names.put(agent.getId(), agent.getName());
        }
        // A missing name is normal, not an error: agent_id is intentionally not
        // a foreign key so run history outlives the agent definition.
        return jobs.stream()
                .map(job -> AgentJobSummaryResponse.from(job, names.get(job.getAgentId())))
                .toList();
    }

    static Pageable pageOf(int limit, int offset) {
        int size = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        if (safeOffset % size == 0) {
            return PageRequest.of(safeOffset / size, size);
        }
        // PageRequest can only express offsets that are whole multiples of the page size; an
        // unaligned
        // offset used to be rounded down, silently returning rows the caller did not ask for.
        return new OffsetLimitRequest(safeOffset, size);
    }
}
