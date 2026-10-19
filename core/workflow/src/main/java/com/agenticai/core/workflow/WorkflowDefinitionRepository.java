package com.agenticai.core.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    // Per-node workflow triggers (#912-ish, "each trigger node bound to its own
    // channel") - the webhook token lookup that used to live here
    // (findByWebhookTokenHash) moved to the omnichannel module's
    // WorkflowTriggerNodeService#findByWebhookTokenHash, since the token/hash
    // now belongs to a WorkflowTriggerNode row, not this entity - see
    // WorkflowWebhookController for the new lookup path.

    // Schedule trigger (#166) - WorkflowScheduler calls this once per active
    // tenant schema (via TenantSchemaIterator), so this is naturally already
    // scoped to one tenant's own workflow_definition table by the time it runs.
    List<WorkflowDefinition> findByScheduleEnabledTrueAndStatusAndNextRunAtLessThanEqual(
        WorkflowStatus status, Instant now
    );
}
