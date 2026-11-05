package com.agenticai.core.agents.memorystore;

import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Subject erasure for the unified store (DPDP, ADR-0028 section 12): removes every {@code
 * memory_item} about a person, everything derived from those items, their lineage and their
 * history, leaving content-free ERASED events. The store port ({@link MemoryItemStorage}) is
 * deliberately unreachable from outside this package (MemoryPromotionRulesTest), so the erasure
 * hook of the turn module ({@code SubjectDataEraser}) calls this class instead. It consults no
 * feature flag: rows written while the feature was on are still personal data once it is off. Needs
 * the tenant on the calling thread.
 */
public class MemoryItemErasure {

    /** Actor recorded on the ERASED events this erasure leaves behind. */
    public static final String ACTOR = "subject-erasure";

    private final ObjectProvider<MemoryItemStorage> storage;

    public MemoryItemErasure(ObjectProvider<MemoryItemStorage> storage) {
        this.storage = storage;
    }

    /**
     * Erases everything about {@code subjectKey} across the tenant's agents; returns the number of
     * rows erased.
     */
    public int eraseSubject(String subjectKey) {
        MemoryItemStorage items = storage.getIfAvailable();
        if (items == null || subjectKey == null || subjectKey.isBlank()) {
            return 0;
        }
        return items.eraseSubject(subjectKey, MemoryActor.system(ACTOR), Instant.now()).size();
    }
}
