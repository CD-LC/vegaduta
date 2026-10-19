package com.agenticai.core.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

// JpaSpecificationExecutor backs #search(Specification, Pageable) - see
// AuditLogService#list(actionType, targetType, from, to) for why: a static
// "(:param IS NULL OR e.field = :param)" @Query (the previous approach here)
// hits a real Postgres/Hibernate parameter-type-inference bug for the date
// range - pgjdbc can't determine (and in one CAST-workaround attempt,
// actively mis-resolves to `bytea`) the type of a bind parameter that's only
// ever compared via "IS NULL". Building the predicate list in Java instead -
// only adding a createdAt >= / <= predicate when that filter is actually
// non-null - means Postgres never even sees a parameter in that ambiguous
// position, because there isn't one: an unused filter simply isn't part of
// the generated SQL at all.
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID>, JpaSpecificationExecutor<AuditLogEntry> {

    List<AuditLogEntry> findTop200ByOrderByCreatedAtDesc();

    List<AuditLogEntry> findTop200ByActionTypeOrderByCreatedAtDesc(String actionType);

    List<AuditLogEntry> findTop200ByTargetTypeOrderByCreatedAtDesc(String targetType);

    List<AuditLogEntry> findTop200ByActionTypeAndTargetTypeOrderByCreatedAtDesc(String actionType, String targetType);

    // Backs the event-type filter dropdown - a real, always-current list of
    // every actionType ever actually recorded, instead of a hardcoded
    // frontend list that drifts from whatever new "somethign.happened"
    // action type the next feature adds (see AuditLogService's own javadoc:
    // this is the ONLY place audit_log_entry gets written, so this list is
    // always exactly the alphabet of action types that can ever appear).
    @Query("SELECT DISTINCT e.actionType FROM AuditLogEntry e ORDER BY e.actionType")
    List<String> findDistinctActionTypes();

    // --- Phase P5 (Fraud & Anomaly) abuse-signal inputs -----------------
    // All scoped to the current tenant schema by Hibernate's per-tenant
    // resolver, same as every other query here. Deliberately COUNT queries
    // (not row fetches) - AbuseSignalService only needs rates/ratios, never
    // the rows themselves, so this stays cheap on hot tenants.

    /** Total state-changing actions by one actor since {@code since} - message/action flooding (burst rate) input. */
    long countByActorIdAndCreatedAtAfter(String actorId, java.time.Instant since);

    /** Actions by one actor with a given outcome since {@code since} - tool-error-abuse (FAILURE ratio) input. */
    long countByActorIdAndOutcomeAndCreatedAtAfter(String actorId, AuditOutcome outcome, java.time.Instant since);

    /**
     * Actions by one actor whose action type contains {@code fragment}
     * (case-insensitive) since {@code since} - signup-velocity input (matches
     * whatever "signup"/"create"-shaped action types this platform records,
     * without hardcoding an exact string that a future rename would break).
     */
    long countByActorIdAndActionTypeContainingIgnoreCaseAndCreatedAtAfter(
        String actorId, String fragment, java.time.Instant since
    );

    /** Distinct non-null actors active since {@code since} - the set the anomaly sweep scores, capped by the caller. */
    @Query("SELECT DISTINCT e.actorId FROM AuditLogEntry e WHERE e.actorId IS NOT NULL AND e.createdAt >= :since")
    List<String> findDistinctActorIdsSince(@Param("since") java.time.Instant since);

    // --- Tamper-evident audit seals (core/audit/seal, V422) ----------------
    // Rows are totally ordered by (created_at, id) - the id breaks ties - and all comparisons happen in
    // Postgres (uuid compares bytewise), never in Java, so sealing and verification always agree on order.
    // Native SQL because a JPQL "id > :x" on a UUID is not portable across Hibernate versions and every
    // parameter here is non-null (see this interface's header comment for the null-parameter trap).

    /** The oldest rows of a tenant that no seal covers yet and that are old enough to have settled. */
    @Query(value = "SELECT * FROM audit_log_entry WHERE created_at < :settledBefore "
        + "ORDER BY created_at, id LIMIT :limit", nativeQuery = true)
    List<AuditLogEntry> findFirstUnsealed(
        @Param("settledBefore") java.time.Instant settledBefore, @Param("limit") int limit
    );

    /** Settled rows strictly after the (created_at, id) boundary of the previous seal, oldest first. */
    @Query(value = "SELECT * FROM audit_log_entry WHERE created_at < :settledBefore "
        + "AND (created_at > :afterAt OR (created_at = :afterAt AND id > :afterId)) "
        + "ORDER BY created_at, id LIMIT :limit", nativeQuery = true)
    List<AuditLogEntry> findUnsealedAfter(
        @Param("settledBefore") java.time.Instant settledBefore,
        @Param("afterAt") java.time.Instant afterAt,
        @Param("afterId") UUID afterId,
        @Param("limit") int limit
    );

    /** Every row inside a seal's inclusive (created_at, id) boundaries, oldest first, capped at {@code limit}. */
    @Query(value = "SELECT * FROM audit_log_entry "
        + "WHERE (created_at > :fromAt OR (created_at = :fromAt AND id >= :fromId)) "
        + "AND (created_at < :toAt OR (created_at = :toAt AND id <= :toId)) "
        + "ORDER BY created_at, id LIMIT :limit", nativeQuery = true)
    List<AuditLogEntry> findInSealRange(
        @Param("fromAt") java.time.Instant fromAt,
        @Param("fromId") UUID fromId,
        @Param("toAt") java.time.Instant toAt,
        @Param("toId") UUID toId,
        @Param("limit") int limit
    );

    /**
     * Start of the oldest transaction currently open on any OTHER connection of this database, as epoch
     * seconds (empty when none). Audit rows are stamped with created_at at construction but become visible
     * at COMMIT, so a seal must not cover a time range an in-flight transaction could still write into.
     * pg_stat_activity is readable by every role (rows of other roles just show NULL xact_start), so this
     * never fails on privileges; it is best effort and the caller caps how long it may defer sealing.
     */
    @Query(value = "SELECT CAST(EXTRACT(EPOCH FROM MIN(xact_start)) AS double precision) FROM pg_stat_activity "
        + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
        + "AND backend_type = 'client backend' AND xact_start IS NOT NULL", nativeQuery = true)
    java.util.Optional<Double> findOldestOpenTransactionStartEpochSeconds();
}
