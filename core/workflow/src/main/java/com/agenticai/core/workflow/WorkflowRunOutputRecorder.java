package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowExpressionResolver.NodeEvalInfo;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists each finished node's output to the {@link RunDataStore} so later expressions can read it, and remembers
 * the runs whose write failed so a later missing row is reported instead of read as an empty value.
 */
final class WorkflowRunOutputRecorder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunOutputRecorder.class);

    private final RunDataStore store;

    WorkflowRunOutputRecorder(RunDataStore store) {
        this.store = store;
    }

    /** Persists a finished node's output for later expressions. Best effort: a store failure never fails the node. */
    void record(NodeEvalInfo info, WorkflowNodeDto node, ExpressionScope scope, String input, NodeExecutionResult result) {
        UUID runId = parseUuid(info == null ? null : info.runId());
        if (store == null || runId == null || result == null || result.haltRun() != null
            || (scope != null && scope.recordingSkipped())) {
            return;
        }
        try {
            int seq = scope == null ? 0 : scope.seq();
            if ("trigger".equalsIgnoreCase(node.type())) {
                store.record(runId, RunDataStore.TRIGGER_NODE_ID, RunDataStore.TRIGGER_LABEL, 0, "OK", input);
            }
            store.record(runId, node.id(), node.label(), seq, result.status(), result.output());
        } catch (RuntimeException e) {
            markRecordingFailed(runId);
            log.warn("Could not record the output of node '{}' for run {} - later expressions will not see it: {}",
                node.id(), runId, e.getMessage());
        }
    }

    /** Runs whose run-data write failed, so a later missing row is reported instead of read as an empty value. */
    private final Set<UUID> recordingFailedRuns = Collections.newSetFromMap(
        new LinkedHashMap<UUID, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > 2_000;
            }
        });

    private void markRecordingFailed(UUID runId) {
        synchronized (recordingFailedRuns) {
            recordingFailedRuns.add(runId);
        }
    }

    boolean recordingFailed(UUID runId) {
        synchronized (recordingFailedRuns) {
            return recordingFailedRuns.contains(runId);
        }
    }

    static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
