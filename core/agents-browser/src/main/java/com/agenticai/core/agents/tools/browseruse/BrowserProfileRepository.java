package com.agenticai.core.agents.tools.browseruse;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link BrowserProfile}; tenant-routed like every other JPA repository. */
public interface BrowserProfileRepository extends JpaRepository<BrowserProfile, UUID> {

    List<BrowserProfile> findAllByOrderByNameAsc();

    boolean existsByOwnerSubjectAndNameIgnoreCase(String ownerSubject, String name);

    boolean existsByOwnerSubjectIsNullAndNameIgnoreCase(String name);
}
