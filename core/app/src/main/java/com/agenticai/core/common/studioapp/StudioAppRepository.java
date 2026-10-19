package com.agenticai.core.common.studioapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudioAppRepository extends JpaRepository<StudioApp, UUID> {

    /** Public-surface lookup - only ever called after TenantContext has been set to the slug's owning schema. */
    Optional<StudioApp> findBySlug(String slug);

    List<StudioApp> findAllByOrderByUpdatedAtDesc();
}
