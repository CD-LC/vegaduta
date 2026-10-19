package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioAppConnectionRepository extends JpaRepository<StudioAppConnection, UUID> {

    List<StudioAppConnection> findByAppIdOrderBySortOrder(UUID appId);

    Optional<StudioAppConnection> findByAppIdAndAlias(UUID appId, String alias);

    void deleteByAppId(UUID appId);
}
