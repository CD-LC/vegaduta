package com.agenticai.core.agents.tools.browseruse;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link BrowserProfileAgentLink} (agent id is the primary key: one profile per
 * agent).
 */
public interface BrowserProfileAgentLinkRepository
        extends JpaRepository<BrowserProfileAgentLink, UUID> {

    List<BrowserProfileAgentLink> findByProfileId(UUID profileId);
}
