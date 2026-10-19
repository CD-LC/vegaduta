package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioAppUserRepository extends JpaRepository<StudioAppUser, UUID> {

    Optional<StudioAppUser> findByAppIdAndEmail(UUID appId, String email);

    Optional<StudioAppUser> findByIdAndAppId(UUID id, UUID appId);

    List<StudioAppUser> findByAppIdOrderByCreatedAtDesc(UUID appId);

    long countByAppId(UUID appId);
}
