package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.EpisodePolicyArm;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EpisodePolicyArmRepository extends JpaRepository<EpisodePolicyArm, UUID> {

    /** Every dimension's arm selection recorded for an episode - what the human-reward loop folds back into. */
    List<EpisodePolicyArm> findByEpisodeId(UUID episodeId);
}
