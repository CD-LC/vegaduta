package com.agenticai.core.agents.learning.consolidation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tombstone writes only; the consolidation path never updates or deletes through it. */
public interface MemoryTombstoneRepository extends JpaRepository<MemoryTombstone, UUID> {}
