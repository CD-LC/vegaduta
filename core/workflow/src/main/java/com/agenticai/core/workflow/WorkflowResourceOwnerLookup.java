package com.agenticai.core.workflow;

import com.agenticai.core.sharing.ResourceOwnerLookup;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cross-user resource sharing retrofit (Round 2) - the {@code core/workflow}
 * adapter for core/sharing's {@link ResourceOwnerLookup} seam, mirroring
 * {@code AgentResourceOwnerLookup} in the sibling module exactly. Lets {@code
 * ShareController} resolve "who owns this workflow" from a bare id off the
 * URL without core/sharing needing a forward dependency on this module - see
 * {@link ResourceOwnerLookup}'s own javadoc for the full "why".
 *
 * <p>Deliberately a thin {@link WorkflowDefinitionRepository} lookup, not a
 * call through {@link WorkflowService} - {@code WorkflowService#get} 404s on
 * a missing/cross-tenant id, which isn't what an owner-resolution seam needs
 * ({@code Optional.empty()} is the right shape for "no such workflow" here,
 * not an exception).
 */
@Component
public class WorkflowResourceOwnerLookup implements ResourceOwnerLookup {

    private final WorkflowDefinitionRepository repository;

    public WorkflowResourceOwnerLookup(WorkflowDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public ResourceType supportedType() {
        return ResourceType.WORKFLOW;
    }

    @Override
    public Optional<String> findCreatedBy(UUID resourceId) {
        return repository.findById(resourceId).map(WorkflowDefinition::getCreatedBy);
    }
}
