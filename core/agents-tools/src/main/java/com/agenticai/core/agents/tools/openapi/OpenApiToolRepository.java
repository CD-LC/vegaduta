package com.agenticai.core.agents.tools.openapi;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpenApiToolRepository extends JpaRepository<OpenApiTool, UUID> {

    /**
     * The chat-turn hot path ({@code AgentToolProvider#resolveOpenApiTools}): enabled tools of
     * enabled sources, source fetched in the same query so the callback never touches a lazy proxy
     * later on Spring AI's streaming thread.
     */
    @Query(
            "select t from OpenApiTool t join fetch t.source s where t.enabled = true and s.enabled = true order by t.toolName")
    List<OpenApiTool> findRunnable();

    /** One runnable tool by id, source fetched: what a call re-reads before it executes. */
    @Query(
            "select t from OpenApiTool t join fetch t.source s where t.id = :id and t.enabled = true and s.enabled = true")
    java.util.Optional<OpenApiTool> findRunnableById(@Param("id") UUID id);

    @Query("select t from OpenApiTool t where t.source.id = :sourceId order by t.toolName")
    List<OpenApiTool> findAllBySourceId(@Param("sourceId") UUID sourceId);

    @Query("select count(t) from OpenApiTool t where t.source.id = :sourceId")
    long countBySourceId(@Param("sourceId") UUID sourceId);

    @Query("select count(t) from OpenApiTool t where t.source.id = :sourceId and t.enabled = true")
    long countEnabledBySourceId(@Param("sourceId") UUID sourceId);

    boolean existsByToolName(String toolName);
}
