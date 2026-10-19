package com.agenticai.core.rl.repo;

import com.agenticai.core.rl.domain.RewardRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardRecordRepository extends JpaRepository<RewardRecord, UUID> {

    List<RewardRecord> findByEpisodeId(UUID episodeId);
}
