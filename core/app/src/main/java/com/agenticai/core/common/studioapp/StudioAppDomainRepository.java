package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioAppDomainRepository extends JpaRepository<StudioAppDomain, UUID> {

    List<StudioAppDomain> findByAppIdOrderByCreatedAtAsc(UUID appId);

    Optional<StudioAppDomain> findByIdAndAppId(UUID id, UUID appId);

    Optional<StudioAppDomain> findByHostname(String hostname);

    long countByAppId(UUID appId);
}
