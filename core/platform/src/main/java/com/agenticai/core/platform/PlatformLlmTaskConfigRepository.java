package com.agenticai.core.platform;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformLlmTaskConfigRepository extends JpaRepository<PlatformLlmTaskConfig, Long> {

    Optional<PlatformLlmTaskConfig> findByTask(PlatformTask task);
}
