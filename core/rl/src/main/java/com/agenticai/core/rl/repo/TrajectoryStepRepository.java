package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.TrajectoryStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrajectoryStepRepository extends JpaRepository<TrajectoryStep, UUID> {

    List<TrajectoryStep> findByEpisodeIdOrderByStepIndexAsc(UUID episodeId);

    long countByEpisodeId(UUID episodeId);
}
