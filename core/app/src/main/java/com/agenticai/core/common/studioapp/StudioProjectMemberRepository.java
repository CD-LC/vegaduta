package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioProjectMemberRepository extends JpaRepository<StudioProjectMember, UUID> {

    List<StudioProjectMember> findByProjectIdOrderByCreatedAtAsc(String projectId);

    Optional<StudioProjectMember> findByProjectIdAndMemberSubject(String projectId, String memberSubject);

    List<StudioProjectMember> findByMemberSubject(String memberSubject);

    long countByProjectId(String projectId);
}
