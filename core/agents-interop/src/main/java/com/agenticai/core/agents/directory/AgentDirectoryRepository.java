package com.agenticai.core.agents.directory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentDirectoryRepository extends JpaRepository<AgentDirectoryEntry, UUID> {

    List<AgentDirectoryEntry> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    /**
     * Public listing query - published rows only, optional case-insensitive
     * substring match on name/description. {@code pattern} arrives pre-wrapped
     * ({@code "%term%"} or null) so the optional filter never becomes an
     * unbound parameter - the Postgres inference bug {@code AuditLogService}
     * documents.
     */
    @Query("SELECT e FROM AgentDirectoryEntry e WHERE e.status = 'PUBLISHED' "
        + "AND (:pattern IS NULL OR LOWER(e.displayName) LIKE :pattern "
        + "OR LOWER(e.description) LIKE :pattern) ORDER BY e.publishedAt DESC, e.agentId DESC")
    List<AgentDirectoryEntry> listPublished(@Param("pattern") String pattern, Pageable pageable);

    /** Total rows {@link #listPublished} matches, for {@code total} / {@code X-Total-Count}. */
    @Query("SELECT COUNT(e) FROM AgentDirectoryEntry e WHERE e.status = 'PUBLISHED' "
        + "AND (:pattern IS NULL OR LOWER(e.displayName) LIKE :pattern "
        + "OR LOWER(e.description) LIKE :pattern)")
    long countPublished(@Param("pattern") String pattern);
}
