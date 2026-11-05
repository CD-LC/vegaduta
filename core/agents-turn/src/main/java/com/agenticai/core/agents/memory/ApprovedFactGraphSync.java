package com.agenticai.core.agents.memory;

import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.GraphTriple;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Mirrors a person-approved fact into the knowledge graph (ADR-0028 gap G6). The graph has no
 * quarantine state, so a fact written on a tainted turn skips it at write time and, until now,
 * stayed vector-only after a person approved it. The approve path calls this once the approval has
 * been committed: it extracts triples from the fact with {@link EntityExtractionService} and writes
 * them through {@link GraphMemoryService}, stamping each new edge with the fact's row id.
 *
 * <p>Best-effort and fail-open for the graph only: nothing here throws, and a failure is logged
 * (ids only, never the fact text) and counted in {@code memory.graph.approve.sync}. It does nothing
 * unless graph memory is wired (the {@code graph-memory} profile). Triples go through the same
 * {@link MemoryWriteGate#screenTriples} screen as a normal write.
 */
@Service
public class ApprovedFactGraphSync {

    private static final Logger log = LoggerFactory.getLogger(ApprovedFactGraphSync.class);
    static final String METRIC = "memory.graph.approve.sync";

    /** What happened, for the metric tag and for tests. */
    public enum Outcome {
        WRITTEN,
        NO_TRIPLES,
        SKIPPED,
        FAILED
    }

    private final ObjectProvider<GraphMemoryService> graph;
    private final ObjectProvider<EntityExtractionService> extraction;
    private final ObjectProvider<MemoryExtractionChatClients> clients;
    private final ObjectProvider<MeterRegistry> registry;

    public ApprovedFactGraphSync(
            ObjectProvider<GraphMemoryService> graph,
            ObjectProvider<EntityExtractionService> extraction,
            ObjectProvider<MemoryExtractionChatClients> clients,
            ObjectProvider<MeterRegistry> registry) {
        this.graph = graph;
        this.extraction = extraction;
        this.clients = clients;
        this.registry = registry;
    }

    /** An approved agent-scope fact: written to the agent's tier of the graph. */
    public Outcome agentFactApproved(
            UUID agentId, String subjectKey, UUID memoryId, String content) {
        return sync(
                "agent",
                memoryId,
                agentId,
                content,
                (g, triples) -> g.remember(agentId, subjectKey, triples, memoryId));
    }

    /**
     * An approved tenant-shared fact: written to the shared tier ({@code agentId} is the creating
     * agent, may be null).
     */
    public Outcome sharedFactApproved(
            UUID agentId, String subjectKey, UUID memoryId, String content) {
        return sync(
                "shared",
                memoryId,
                agentId,
                content,
                (g, triples) -> g.rememberShared(subjectKey, triples, memoryId));
    }

    private interface Write {
        void to(GraphMemoryService graph, List<GraphTriple> triples);
    }

    private Outcome sync(String scope, UUID memoryId, UUID agentId, String content, Write write) {
        Outcome outcome;
        try {
            GraphMemoryService g = graph.getIfAvailable();
            EntityExtractionService ex = extraction.getIfAvailable();
            MemoryExtractionChatClients provider = clients.getIfAvailable();
            if (g == null
                    || ex == null
                    || provider == null
                    || content == null
                    || content.isBlank()) {
                outcome = Outcome.SKIPPED;
            } else {
                List<GraphTriple> triples =
                        MemoryWriteGate.screenTriples(
                                ex.extractTriples(
                                        provider.forBackgroundExtraction(), content, agentId));
                if (triples.isEmpty()) {
                    outcome = Outcome.NO_TRIPLES;
                } else {
                    write.to(g, triples);
                    outcome = Outcome.WRITTEN;
                }
            }
        } catch (RuntimeException e) {
            outcome = Outcome.FAILED;
            log.warn(
                    "Graph write for approved {} memory {} failed; the approval stands and the fact stays vector-only",
                    scope,
                    memoryId,
                    e);
        }
        count(scope, outcome);
        return outcome;
    }

    private void count(String scope, Outcome outcome) {
        try {
            MeterRegistry r = registry.getIfAvailable();
            if (r != null) {
                r.counter(
                                METRIC,
                                "scope",
                                scope,
                                "outcome",
                                outcome.name().toLowerCase(java.util.Locale.ROOT))
                        .increment();
            }
        } catch (RuntimeException e) {
            log.debug("Could not count the approved-fact graph sync", e);
        }
    }
}
