package com.agenticai.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    Optional<FeatureFlag> findByKey(String key);

    List<FeatureFlag> findByEnabledTrue();

    List<FeatureFlag> findAllByOrderByKeyAsc();

    void deleteByKey(String key);
}
