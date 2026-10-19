package com.agenticai.core.agents.crew;

import com.agenticai.core.agents.turn.AgentChatService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port (ADR-0023) for Crew-style chat-time specialist handoff. {@code AgentChatService} (through
 * {@code ChatSpecialistHandoff}) and the tool resolver ({@code
 * AgentCrewBrowserToolResolver#resolveSpecialistDelegationTools}) are wired to it through a single
 * {@code ObjectProvider}, fail-open when absent. The implementation, {@code
 * SpecialistHandoffServiceImpl} in {@code core/agents-interop} (bean name {@code
 * specialistHandoffService}, unchanged), bundles the specialist registry (capability lookup) and
 * the handoff audit trail behind the one surface a caller mid-turn needs.
 */
public interface SpecialistHandoffService {

    /**
     * Every distinct capability tag registered in this tenant - the roster {@code
     * delegate_to_specialist}'s tool description lists.
     */
    List<String> listCapabilityTags();

    /**
     * The first currently-resolvable specialist registered for {@code capabilityTag}, oldest
     * registration first. A registered agent id that no longer resolves is skipped.
     */
    Optional<SpecialistCandidate> findSpecialist(String capabilityTag);

    /** Records a dispatched hop (status PENDING) and returns its audit id. */
    UUID recordHandoffStart(HandoffChain chain, UUID toAgentId, String capabilityTag, String subTask);

    /** Records the terminal outcome of a hop started with {@link #recordHandoffStart}. */
    void recordHandoffOutcome(UUID auditId, HandoffStatus status, String errorMessage);

    /** Records a hop refused before dispatch (depth, cycle, no specialist). */
    void recordHandoffRefusal(
            HandoffChain chain,
            UUID toAgentId,
            String capabilityTag,
            String subTask,
            HandoffStatus status,
            String reason);

    /**
     * The {@code delegate_to_specialist} tool for one resolved tool set: offers {@code
     * capabilityTags} and dispatches through {@code chatService} with {@code chain} as the
     * current hand-off chain.
     */
    ToolCallback delegationTool(
            AgentChatService chatService, List<String> capabilityTags, HandoffChain chain);
}
