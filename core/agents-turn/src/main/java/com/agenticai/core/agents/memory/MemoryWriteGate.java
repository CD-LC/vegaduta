package com.agenticai.core.agents.memory;

import com.agenticai.core.graphmemory.GraphTriple;
import com.agenticai.core.knowledge.MemoryWriteScreen;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Decides the {@link MemoryProvenance} of an agent-initiated memory write from the turn's taint.
 * Fail closed: no {@link TurnTaintView} bean, or one that throws, means the taint state cannot be
 * read, so the write is QUARANTINED.
 */
public final class MemoryWriteGate {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteGate.class);

    static final String UNAVAILABLE = "taint-state-unavailable";

    private MemoryWriteGate() {}

    public static MemoryProvenance classify(ObjectProvider<TurnTaintView> taint) {
        try {
            TurnTaintView view = taint == null ? null : taint.getIfAvailable();
            if (view == null) {
                return new MemoryProvenance(MemoryTrust.QUARANTINED, List.of(UNAVAILABLE));
            }
            if (view.isTainted()) {
                List<String> sources = view.sources();
                return new MemoryProvenance(
                        MemoryTrust.QUARANTINED, sources == null || sources.isEmpty() ? List.of("unknown") : sources);
            }
            return MemoryProvenance.TRUSTED;
        } catch (RuntimeException e) {
            log.warn("Turn taint state unreadable; quarantining memory write", e);
            return new MemoryProvenance(MemoryTrust.QUARANTINED, List.of(UNAVAILABLE));
        }
    }

    /** The triples whose subject, relation and object all pass {@link MemoryWriteScreen}. */
    public static List<GraphTriple> screenTriples(List<GraphTriple> triples) {
        if (triples == null) {
            return List.of();
        }
        return triples.stream()
                .filter(t -> t != null
                        && MemoryWriteScreen.refusal(t.subject()).isEmpty()
                        && MemoryWriteScreen.refusal(t.relation()).isEmpty()
                        && MemoryWriteScreen.refusal(t.object()).isEmpty())
                .toList();
    }
}
