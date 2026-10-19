package com.agenticai.core.brief;

import com.agenticai.core.agents.experiment.AgentExperiment;
import com.agenticai.core.agents.experiment.AgentExperimentConflictException;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentRequest;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentStarted;
import com.agenticai.core.quality.dataset.AgentHeadVersionLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Run as experiment" ({@code POST /api/briefs/{id}/experiments}): sets the running configuration of one of the brief's
 * agents against an earlier stored version of it, on the platform's own experiment feature
 * ({@link AgentExperimentService}, the service behind {@code AgentExperimentController}) - this class only resolves the
 * brief's agent reference and applies the conventions the proof ledger relies on:
 * <ul>
 *   <li>control = the agent as it runs now, treatment = {@code versionB}, an {@code agent_version} id of the same agent;</li>
 *   <li>the experiment is named {@code brief:<briefId> ...} so the ledger can tell which experiments compare "running
 *       vs earlier" (only those feed the trust ladder's "experiment win" rule).</li>
 * </ul>
 * The platform allows one active experiment per agent and clones the treatment version into a hidden agent; both
 * refusals surface as 409 with the platform's own sentence.
 */
@Service
public class BriefExperimentService {

    static final int DEFAULT_TRAFFIC_PERCENT = 50;

    private final BriefVersionStore store;
    private final BriefApplyLedger ledger;
    private final AgentExperimentService experiments;
    private final AgentHeadVersionLookup headVersions;

    public BriefExperimentService(
        BriefVersionStore store, BriefApplyLedger ledger, AgentExperimentService experiments, AgentHeadVersionLookup headVersions
    ) {
        this.store = store;
        this.ledger = ledger;
        this.experiments = experiments;
        this.headVersions = headVersions;
    }

    public ExperimentStarted start(UUID briefId, ExperimentRequest request) {
        BriefEntity entity = store.require(briefId);
        if (request == null || request.agentRef() == null || request.agentRef().isBlank()) {
            throw new BriefValidationException("agentRef", "Say which of the brief's agents to run the experiment on (agentRef, e.g. \"a1\").");
        }
        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            throw new BriefConflictException("An experiment runs on the live agent, so apply the solution first.");
        }
        Binding agent = ledger.bindings(briefId).get(request.agentRef());
        if (agent == null || !"AGENT".equals(agent.kind())) {
            throw new BriefValidationException("agentRef", "\"" + request.agentRef() + "\" is not an agent of this brief that is running.");
        }
        UUID agentId = UUID.fromString(agent.entityId());
        UUID versionB = parse(request.versionB(), "versionB");
        if (versionB == null) {
            throw new BriefValidationException("versionB", "Give the earlier agent version to compare against (versionB).");
        }
        UUID versionA = parse(request.versionA(), "versionA");
        if (versionA != null) {
            Optional<UUID> head = headVersions.findHeadVersionId(agentId);
            if (head.isEmpty() || !head.get().equals(versionA)) {
                throw new BriefValidationException("versionA",
                    "The control is always the agent as it runs now, which is not that version. Leave versionA out, or give the agent's current version.");
            }
        }
        int traffic = request.trafficPercent() == null ? DEFAULT_TRAFFIC_PERCENT : request.trafficPercent();
        if (traffic < 1 || traffic > 99) {
            throw new BriefValidationException("trafficPercent", "trafficPercent must be between 1 and 99.");
        }
        String name = ProofLedgerService.EXPERIMENT_PREFIX + briefId + " running vs earlier version";
        AgentExperiment created;
        try {
            created = experiments.create(agentId, name, versionB, traffic, null, BriefVersionStore.actor());
            if (request.start() == null || request.start()) {
                created = experiments.start(agentId, created.getId());
            }
        } catch (AgentExperimentConflictException e) {
            throw new BriefConflictException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BriefValidationException("versionB", e.getMessage());
        }
        return new ExperimentStarted(created.getId().toString(), request.agentRef(), agent.entityId(), created.getName(),
            created.getStatus().name(), created.getTrafficPercent());
    }

    private static UUID parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new BriefValidationException(field, field + " must be an agent version id (a UUID).");
        }
    }
}
