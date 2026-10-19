package com.agenticai.core.agents.skills;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findAllByOrderByNameAsc();

    /** Chat-turn hot path (Level 1): names + descriptions only, bundled files never touched. */
    List<Skill> findAllByEnabledTrueOrderByNameAsc();

    boolean existsByName(String name);

    /** Row only (no bundled files) - the supply-chain gate reads columns, never file bodies. */
    Optional<Skill> findByName(String name);

    @Query("select s from Skill s left join fetch s.files where s.name = :name")
    Optional<Skill> findByNameWithFiles(@Param("name") String name);

    @Query("select s from Skill s left join fetch s.files where s.id = :id")
    Optional<Skill> findByIdWithFiles(@Param("id") UUID id);

    long countByStatus(SkillStatus status);

    /**
     * Usage counters (V590) are bulk UPDATEs on purpose: they bypass the entity, so a tool call
     * neither dirties a loaded Skill nor evicts the per-turn skill cache the way a real edit does.
     */
    @Modifying
    @Query(
            "update Skill s set s.uses = s.uses + 1, s.lastUsedAt = :now"
                    + " where s.name = :name and s.enabled = true")
    int recordUse(@Param("name") String name, @Param("now") Instant now);

    @Modifying
    @Query("update Skill s set s.successes = s.successes + 1 where s.name in :names")
    int recordSuccess(@Param("names") Collection<String> names);

    @Modifying
    @Query("update Skill s set s.failures = s.failures + 1 where s.name in :names")
    int recordFailure(@Param("names") Collection<String> names);

    /**
     * Stores the self-check of a freshly drafted skill (V653). Bulk UPDATE like the counters above,
     * and only while the draft is still PENDING: a verdict never lands on a skill that went live.
     */
    @Modifying
    @Query(
            "update Skill s set s.selfCheckVerdict = :verdict, s.selfCheckReason = :reason,"
                    + " s.selfCheckedAt = :at where s.name = :name and s.status = :pending")
    int recordSelfCheck(
            @Param("name") String name,
            @Param("verdict") String verdict,
            @Param("reason") String reason,
            @Param("at") Instant at,
            @Param("pending") SkillStatus pending);
}
