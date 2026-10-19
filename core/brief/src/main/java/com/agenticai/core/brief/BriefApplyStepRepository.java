package com.agenticai.core.brief;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefApplyStepRepository extends JpaRepository<BriefApplyStepEntity, UUID> {

    List<BriefApplyStepEntity> findByBriefIdAndJobIdOrderBySeqAsc(UUID briefId, UUID jobId);

    /** Job-level rows (kind JOB, seq 0) for a brief, newest first. */
    List<BriefApplyStepEntity> findByBriefIdAndKindOrderByCreatedAtDesc(UUID briefId, String kind);
}
