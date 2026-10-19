package com.agenticai.core.tenancy.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups for {@link DemoChatMessage}. The only request path is the ordered
 * per-session transcript read behind {@code GET /api/demo/history} - bounded
 * at the per-session message cap by construction, so no paging needed.
 */
public interface DemoChatMessageRepository extends JpaRepository<DemoChatMessage, UUID> {

    List<DemoChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
