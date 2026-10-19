package com.agenticai.core.workflow;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowNodePinRepository extends JpaRepository<WorkflowNodePin, WorkflowNodePin.Key> {

    List<WorkflowNodePin> findByWorkflowIdOrderByNodeId(UUID workflowId);

    /** Pins of a workflow other than {@code nodeId}: what a (re-)pin of that node is counted against. */
    @Query("select count(p) from WorkflowNodePin p where p.workflowId = :workflowId and p.nodeId <> :nodeId")
    long countOthers(@Param("workflowId") UUID workflowId, @Param("nodeId") String nodeId);

    @Query("select coalesce(sum(p.sizeChars), 0) from WorkflowNodePin p where p.workflowId = :workflowId and p.nodeId <> :nodeId")
    long sumSizeOthers(@Param("workflowId") UUID workflowId, @Param("nodeId") String nodeId);

    @Modifying
    @Query("delete from WorkflowNodePin p where p.workflowId = :workflowId and p.nodeId in :nodeIds")
    int deleteByWorkflowIdAndNodeIds(@Param("workflowId") UUID workflowId, @Param("nodeIds") Collection<String> nodeIds);
}
