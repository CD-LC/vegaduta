package com.agenticai.core.omnichannel.trigger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowTriggerNodeRepository extends JpaRepository<WorkflowTriggerNode, UUID> {

    Optional<WorkflowTriggerNode> findByChannel(WorkflowTriggerChannel channel);

    Optional<WorkflowTriggerNode> findByWebhookTokenHash(String webhookTokenHash);

    Optional<WorkflowTriggerNode> findByNodeId(String nodeId);
}
