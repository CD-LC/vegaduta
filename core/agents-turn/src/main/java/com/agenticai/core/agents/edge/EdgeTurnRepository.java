package com.agenticai.core.agents.edge;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Edge Wave 2 (EDGE-B) - write path for {@link EdgeTurn} rows (see V191). Read/aggregation
 * queries (the web-admin edge-share panel the strategy doc names) are deliberately NOT added
 * yet: no consumer exists in this wave, and speculative query methods on a hot fire-and-forget
 * write path would be dead surface area to maintain.
 */
public interface EdgeTurnRepository extends JpaRepository<EdgeTurn, UUID> {
}
