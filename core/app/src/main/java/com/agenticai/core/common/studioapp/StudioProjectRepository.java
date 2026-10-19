package com.agenticai.core.common.studioapp;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioProjectRepository extends JpaRepository<StudioProject, String> {

    List<StudioProject> findByOwnerSubjectOrderByUpdatedAtDesc(String ownerSubject);
}
