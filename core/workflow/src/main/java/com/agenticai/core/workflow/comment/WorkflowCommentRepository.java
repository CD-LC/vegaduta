package com.agenticai.core.workflow.comment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowCommentRepository extends JpaRepository<WorkflowComment, UUID> {

    List<WorkflowComment> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);

    Optional<WorkflowComment> findByIdAndWorkflowId(UUID id, UUID workflowId);

    boolean existsByParentIdAndDeletedAtIsNull(UUID parentId);

    /** Mention display names: the name this user last wrote a comment under, in this tenant. */
    Optional<WorkflowComment> findFirstByAuthorIdAndAuthorNameIsNotNullOrderByCreatedAtDesc(String authorId);
}
