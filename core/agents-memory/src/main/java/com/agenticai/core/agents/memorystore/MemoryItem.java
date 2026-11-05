package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A {@code memory_item} row (without its embedding). The compact constructor repeats the CHECK
 * constraints of V681 as service-level rules, so code can never build a value the database would
 * reject, and a unit test needs no database to prove them:
 *
 * <ul>
 *   <li>only EPISODIC, PROCEDURAL and SELF_MODEL are stored here (SEMANTIC lives in agent_memory);
 *   <li>ACTIVE requires TRUSTED;
 *   <li>TRUSTED with any source taint requires {@code approvedBy};
 *   <li>an ACTIVE PROCEDURAL item requires {@code approvedBy};
 *   <li>generation is 0..8.
 * </ul>
 */
public record MemoryItem(
        UUID id,
        UUID agentId,
        MemoryKind kind,
        String content,
        MemoryTrustLevel trust,
        MemoryItemStatus status,
        MemoryWriter writer,
        String sourceType,
        String sourceRef,
        String turnId,
        List<String> sourceTaint,
        boolean modelGenerated,
        int generation,
        List<String> subjectKeys,
        int version,
        UUID supersedesId,
        Instant createdAt,
        Instant expiresAt,
        String approvedBy,
        Instant approvedAt,
        boolean pinned) {

    public static final int MAX_GENERATION = 8;

    /** An unpinned item (every row starts that way; only a person pins, see {@link MemoryItemReviewService#pin}). */
    public MemoryItem(
            UUID id,
            UUID agentId,
            MemoryKind kind,
            String content,
            MemoryTrustLevel trust,
            MemoryItemStatus status,
            MemoryWriter writer,
            String sourceType,
            String sourceRef,
            String turnId,
            List<String> sourceTaint,
            boolean modelGenerated,
            int generation,
            List<String> subjectKeys,
            int version,
            UUID supersedesId,
            Instant createdAt,
            Instant expiresAt,
            String approvedBy,
            Instant approvedAt) {
        this(id, agentId, kind, content, trust, status, writer, sourceType, sourceRef, turnId, sourceTaint,
                modelGenerated, generation, subjectKeys, version, supersedesId, createdAt, expiresAt, approvedBy,
                approvedAt, false);
    }

    public MemoryItem {
        if (id == null || agentId == null || kind == null || content == null) {
            throw new IllegalArgumentException("id, agentId, kind and content are required");
        }
        if (kind == MemoryKind.SEMANTIC) {
            throw new IllegalArgumentException("SEMANTIC memory is not stored in memory_item");
        }
        trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
        status = status == null ? MemoryItemStatus.PENDING : status;
        writer = writer == null ? MemoryWriter.AGENT : writer;
        sourceType = sourceType == null ? "UNKNOWN" : sourceType;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
        subjectKeys = subjectKeys == null ? List.of() : List.copyOf(subjectKeys);
        approvedBy = approvedBy == null || approvedBy.isBlank() ? null : approvedBy;
        if (status == MemoryItemStatus.ACTIVE && trust != MemoryTrustLevel.TRUSTED) {
            throw new IllegalArgumentException("an ACTIVE item must be TRUSTED");
        }
        if (trust == MemoryTrustLevel.TRUSTED && !sourceTaint.isEmpty() && approvedBy == null) {
            throw new IllegalArgumentException("a TRUSTED item with source taint needs an approver");
        }
        if (kind == MemoryKind.PROCEDURAL && status == MemoryItemStatus.ACTIVE && approvedBy == null) {
            throw new IllegalArgumentException("an ACTIVE PROCEDURAL item needs an approver");
        }
        if (generation < 0 || generation > MAX_GENERATION) {
            throw new IllegalArgumentException("generation must be between 0 and " + MAX_GENERATION);
        }
    }

    /** A new row exactly as a proposal creates it: QUARANTINED and PENDING, no approver. */
    public static MemoryItem pending(
            UUID id,
            UUID agentId,
            MemoryKind kind,
            String content,
            MemoryWriter writer,
            String sourceType,
            String sourceRef,
            String turnId,
            List<String> sourceTaint,
            boolean modelGenerated,
            int generation,
            List<String> subjectKeys,
            int version,
            UUID supersedesId,
            Instant createdAt,
            Instant expiresAt) {
        return new MemoryItem(
                id,
                agentId,
                kind,
                content,
                MemoryTrustLevel.QUARANTINED,
                MemoryItemStatus.PENDING,
                writer,
                sourceType,
                sourceRef,
                turnId,
                sourceTaint,
                modelGenerated,
                generation,
                subjectKeys,
                version,
                supersedesId,
                createdAt,
                expiresAt,
                null,
                null);
    }

    /** The row after a human approval: TRUSTED and ACTIVE with the approver recorded. */
    MemoryItem withApproval(String approver, Instant at) {
        return new MemoryItem(
                id,
                agentId,
                kind,
                content,
                MemoryTrustLevel.TRUSTED,
                MemoryItemStatus.ACTIVE,
                writer,
                sourceType,
                sourceRef,
                turnId,
                sourceTaint,
                modelGenerated,
                generation,
                subjectKeys,
                version,
                supersedesId,
                createdAt,
                expiresAt,
                approver,
                at,
                pinned);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** True once a human approved it; the only way a model-generated item becomes usable as distiller input. */
    public boolean humanConfirmed() {
        return approvedBy != null;
    }
}
