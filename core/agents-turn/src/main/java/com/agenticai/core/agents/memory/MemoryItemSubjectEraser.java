package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.memorystore.MemoryItemErasure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Subject erasure for the unified memory store ({@code memory_item}, ADR-0028 section 12): when a
 * person is erased (self-service {@code DELETE /api/memory} or the operator's contact erasure)
 * every episodic / procedural / self-model item about them goes too, together with everything
 * derived from it, its lineage and its history, leaving only content-free ERASED events. Always
 * registered and independent of {@code app.memory.unified.*}: rows written while the feature was on
 * are still personal data once it is switched off.
 */
@Component
public class MemoryItemSubjectEraser implements SubjectDataEraser {

    private final ObjectProvider<MemoryItemErasure> erasure;

    public MemoryItemSubjectEraser(ObjectProvider<MemoryItemErasure> erasure) {
        this.erasure = erasure;
    }

    @Override
    public String store() {
        return "memory_items";
    }

    @Override
    public int eraseSubject(String subjectKey) {
        MemoryItemErasure target = erasure.getIfAvailable();
        return target == null ? 0 : target.eraseSubject(subjectKey);
    }
}
