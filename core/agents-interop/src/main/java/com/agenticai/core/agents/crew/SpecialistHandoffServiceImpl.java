package com.agenticai.core.agents.crew;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.AgentChatService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * Implements the {@link SpecialistHandoffService} port from {@code core/agents} (ADR-0023). The
 * bean keeps its pre-split name, {@code specialistHandoffService}.
 *
 * <p>The facade {@code AgentChatService#delegateToSpecialist} and {@code
 * AgentToolProvider#resolveSpecialistDelegationTools} are wired to through a single {@code
 * ObjectProvider}, following the same "one field, one setter, fail-open when absent" posture
 * {@code AgentChatService#setAgentExperimentService} already established for {@code
 * AgentExperimentService} - see that setter's javadoc for why: a dozen test fixtures construct
 * {@code AgentChatService} by hand, so a new required constructor parameter would be a guaranteed
 * merge conflict for a feature unrelated to whatever those fixtures are testing.
 *
 * <p>Bundles {@link SpecialistRegistry} (capability lookup) and {@link AgentHandoffAuditService}
 * (the durable trail) behind the one surface a caller mid-turn actually needs, so neither
 * {@code AgentChatService} nor {@code AgentToolProvider} has to carry two new optional fields for
 * one feature.
 */
@Service("specialistHandoffService")
public class SpecialistHandoffServiceImpl implements SpecialistHandoffService {

    private static final Logger log = LoggerFactory.getLogger(SpecialistHandoffServiceImpl.class);

    private final SpecialistRegistry specialistRegistry;
    private final AgentHandoffAuditService auditService;
    private final AgentService agentService;

    public SpecialistHandoffServiceImpl(
        SpecialistRegistry specialistRegistry, AgentHandoffAuditService auditService, AgentService agentService
    ) {
        this.specialistRegistry = specialistRegistry;
        this.auditService = auditService;
        this.agentService = agentService;
    }

    /** Every distinct capability tag registered in this tenant - the roster {@code delegate_to_specialist}'s tool description lists. */
    @Override
    public List<String> listCapabilityTags() {
        return specialistRegistry.listDistinctCapabilityTags();
    }

    /**
     * The first currently-resolvable specialist registered for {@code capabilityTag}, oldest
     * registration first. A registered agent id that no longer resolves (deleted since being
     * registered) is skipped with a warning rather than failing the lookup - same fail-open
     * posture {@code AgentToolProvider#resolveCollaboratorTools} already applies to a stale
     * {@code collaboratorAgentIds} entry.
     */
    @Override
    public Optional<SpecialistCandidate> findSpecialist(String capabilityTag) {
        for (AgentSpecialization candidate : specialistRegistry.findByCapability(capabilityTag)) {
            try {
                AgentResponse agent = agentService.get(candidate.getAgentId());
                return Optional.of(new SpecialistCandidate(candidate.getAgentId(), agent.name(), capabilityTag));
            } catch (RuntimeException e) {
                log.warn("Skipping specialist registration for agent {} (capability '{}') - could not resolve its details",
                    candidate.getAgentId(), capabilityTag, e);
            }
        }
        return Optional.empty();
    }

    /** See {@link AgentHandoffAuditService#recordStart}. */
    @Override
    public UUID recordHandoffStart(HandoffChain chain, UUID toAgentId, String capabilityTag, String subTask) {
        return auditService.recordStart(chain, toAgentId, capabilityTag, subTask);
    }

    /** See {@link AgentHandoffAuditService#recordOutcome}. */
    @Override
    public void recordHandoffOutcome(UUID auditId, HandoffStatus status, String errorMessage) {
        auditService.recordOutcome(auditId, status, errorMessage);
    }

    /** See {@link AgentHandoffAuditService#recordRefusal}. */
    @Override
    public void recordHandoffRefusal(
        HandoffChain chain, UUID toAgentId, String capabilityTag, String subTask, HandoffStatus status, String reason
    ) {
        auditService.recordRefusal(chain, toAgentId, capabilityTag, subTask, status, reason);
    }

    /** See {@link AgentHandoffAuditService#findChainForTurn}. */
    public List<AgentHandoffAudit> findChainForTurn(String turnSessionId) {
        return auditService.findChainForTurn(turnSessionId);
    }

    /** A {@link DelegateToSpecialistToolCallback} over the given chain. */
    @Override
    public ToolCallback delegationTool(
        AgentChatService chatService, List<String> capabilityTags, HandoffChain chain
    ) {
        return new DelegateToSpecialistToolCallback(chatService, capabilityTags, chain);
    }
}
