package com.agenticai.core.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The full (un-redacted) output of every node that has completed in a run, so a later node's
 * {@code {{ $node["Label"].x }}} expression can read it (spec 4.1). Traces cannot serve: they are
 * redacted and truncated by {@link NodeTracePayloads}. Keyed by run id, which is also what makes
 * approval-resume and Temporal activity retries correct: any thread, process or later leg of the run
 * reads the same rows, and nothing rides through Temporal history.
 *
 * <p>The run input is stored as pseudo node {@value #TRIGGER_NODE_ID} with label
 * {@value #TRIGGER_LABEL}, which is what {@code $trigger} resolves to.
 */
@Service
public class RunDataStore {

    private static final Logger log = LoggerFactory.getLogger(RunDataStore.class);

    /** Runs in these states resume later, so the TTL sweep must not delete the outputs they will read. */
    static final java.util.Set<WorkflowRunStatus> PARKED_STATUSES = java.util.EnumSet.of(
        WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.AWAITING_INPUT,
        WorkflowRunStatus.AWAITING_VOICE_INPUT, WorkflowRunStatus.PAUSED);

    public static final String TRIGGER_NODE_ID = "__trigger";
    public static final String TRIGGER_LABEL = "$trigger";
    /** Stored output cap (chars); longer outputs are cut and flagged {@code truncated}. */
    public static final int MAX_OUTPUT_CHARS = 256_000;

    /** What a read returns. {@code outputText} is cut off when {@code truncated}. */
    public record Stored(String nodeId, String label, int seq, String status, String outputText, boolean truncated) {
    }

    private final WorkflowRunNodeOutputRepository repository;

    public RunDataStore(WorkflowRunNodeOutputRepository repository) {
        this.repository = repository;
    }

    /** Idempotent per {@code (runId, nodeId, seq)}: a retry overwrites its own row. */
    public void record(UUID runId, String nodeId, String label, int seq, String status, String output) {
        String text = output == null ? "" : output;
        boolean truncated = text.length() > MAX_OUTPUT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_OUTPUT_CHARS);
        }
        repository.save(new WorkflowRunNodeOutput(runId, nodeId, Math.max(seq, 0), label, status, text, truncated));
    }

    /** Latest iteration of the node carrying this label in the run, or null. */
    public Stored latestByLabel(UUID runId, String label) {
        if (runId == null || label == null) {
            return null;
        }
        return repository.findFirstByRunIdAndNodeLabelOrderBySeqDescCreatedAtDesc(runId, label)
            .map(RunDataStore::toStored).orElse(null);
    }

    /** Latest iteration of this node id in the run, or null. */
    public Stored latestById(UUID runId, String nodeId) {
        if (runId == null || nodeId == null) {
            return null;
        }
        return repository.findFirstByRunIdAndNodeIdOrderBySeqDesc(runId, nodeId)
            .map(RunDataStore::toStored).orElse(null);
    }

    public List<Stored> listForRun(UUID runId) {
        return repository.findByRunIdOrderByCreatedAtAscSeqAsc(runId).stream().map(RunDataStore::toStored).toList();
    }

    /** TTL cleanup for the current tenant schema; returns the number of rows removed. */
    public int deleteOlderThan(Instant cutoff) {
        int n = repository.deleteOlderThan(cutoff, PARKED_STATUSES);
        if (n > 0) {
            log.info("Deleted {} expired workflow run data row(s) older than {}", n, cutoff);
        }
        return n;
    }

    private static Stored toStored(WorkflowRunNodeOutput o) {
        return new Stored(o.getNodeId(), o.getNodeLabel(), o.getSeq(), o.getStatus(), o.getOutputText(), o.isTruncated());
    }
}
