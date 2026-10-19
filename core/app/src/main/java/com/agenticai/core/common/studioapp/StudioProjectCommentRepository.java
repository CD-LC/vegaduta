package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioProjectCommentRepository extends JpaRepository<StudioProjectComment, UUID> {

    List<StudioProjectComment> findByProjectIdOrderByCreatedAtAsc(String projectId);

    Optional<StudioProjectComment> findByIdAndProjectId(UUID id, String projectId);

    long countByProjectId(String projectId);
}
