package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudioProjectVersionRepository extends JpaRepository<StudioProjectVersion, UUID> {

    List<StudioProjectVersion> findByProjectIdOrderByAtMsAsc(String projectId);

    long countByProjectId(String projectId);

    @Query("select v.atMs from StudioProjectVersion v where v.projectId = :projectId")
    List<Long> findAtMsByProjectId(@Param("projectId") String projectId);

    @Modifying
    @Query("delete from StudioProjectVersion v where v.projectId = :projectId and v.atMs in :atMs")
    void deleteByProjectIdAndAtMsIn(@Param("projectId") String projectId, @Param("atMs") List<Long> atMs);
}
