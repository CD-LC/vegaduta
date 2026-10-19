package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentFallbackTotals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    /**
     * One actor's own agents within the current schema - the DB-level
     * counterpart of the in-memory {@code createdBy == subject} filter
     * {@code AgentService} already applies for listing (see its javadoc at
     * {@code Agent#getCreatedBy}). Needed once a schema can hold more than
     * one actor's rows at a time - today that is the SHARED demo sandbox
     * (see {@code DemoSessionService}'s class javadoc): {@code
     * DemoConversionAgentCopier} uses this so a registering visitor's
     * conversion never has to load (or risk copying) every OTHER live demo
     * visitor's agents just to find their own.
     */
    // Explicit JPQL: createdBy now lives in the embedded AgentAuditActors (Agent.audit), which a derived
    // query name cannot reach (Hibernate could not resolve attribute 'createdBy' of Agent at startup).
    @Query("SELECT a FROM Agent a WHERE a.audit.createdBy = :createdBy")
    List<Agent> findByCreatedBy(@Param("createdBy") String createdBy);

    // Platform-admin alert threshold follow-up (#956) - one SUM query per
    // tenant schema instead of loading every agent row just to add two longs
    // client-side (PlatformObservabilityService.computeFallbackAlertStatus
    // calls this once per active tenant). COALESCE guards the zero-agents
    // case, where a bare SUM() would otherwise come back null, not 0.
    @Query(
        "SELECT new com.agenticai.core.agents.dto.AgentFallbackTotals("
            + "COALESCE(SUM(a.fallback.fallbackTriggeredCount), 0), COALESCE(SUM(a.fallback.fallbackFailedCount), 0)) FROM Agent a"
    )
    AgentFallbackTotals sumFallbackCounts();

    // Fallback observability follow-up (#955) - atomic bulk UPDATEs, not a
    // read-entity/increment-field/save() round trip: two concurrent chat
    // turns hitting the same agent's fallback at the same moment must both
    // land their increment, which a read-modify-write would risk losing
    // (last writer's save() would clobber the other's in-memory increment).
    // Both agentId lookups elsewhere in this class already assume the row
    // exists (same posture as every other agent mutation in this codebase);
    // a 0-row-affected update here is a silent no-op, acceptable since the
    // caller (AgentChatService) has already loaded the same agent moments
    // earlier for the chat turn itself.
    @Modifying
    @Query(
        "UPDATE Agent a SET a.fallback.fallbackTriggeredCount = a.fallback.fallbackTriggeredCount + 1, a.fallback.lastFallbackAt = :at WHERE a.id = :id"
    )
    void incrementFallbackTriggered(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Query(
        "UPDATE Agent a SET a.fallback.fallbackFailedCount = a.fallback.fallbackFailedCount + 1, a.fallback.lastFallbackAt = :at WHERE a.id = :id"
    )
    void incrementFallbackFailed(@Param("id") UUID id, @Param("at") Instant at);
}
