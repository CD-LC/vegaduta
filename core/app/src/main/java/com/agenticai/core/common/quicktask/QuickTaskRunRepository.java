package com.agenticai.core.common.quicktask;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuickTaskRunRepository extends JpaRepository<QuickTaskRun, String> {

    /** Ownership-scoped fetch for {@code GET /api/quick-task/{taskId}} - never returns another user's task. */
    Optional<QuickTaskRun> findByTaskIdAndRequestedByKeycloakUserId(String taskId, String requestedByKeycloakUserId);

    /** Recent-tasks list ("any point of time... view it") - newest first, capped so one heavy user can't return an unbounded list. */
    List<QuickTaskRun> findTop50ByRequestedByKeycloakUserIdOrderByStartedAtDesc(String requestedByKeycloakUserId);

    /** Paged recent-tasks list, newest first (taskId tiebreaker keeps offset pages stable). Page.getTotalElements() backs X-Total-Count. */
    Page<QuickTaskRun> findByRequestedByKeycloakUserIdOrderByStartedAtDescTaskIdDesc(
            String requestedByKeycloakUserId, Pageable pageable);
}
