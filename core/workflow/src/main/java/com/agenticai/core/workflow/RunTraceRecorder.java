package com.agenticai.core.workflow;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Run-trace recording for {@link WorkflowExecutionEngine}: attaches the per-step run data
 * (sanitised payloads and LLM usage) to a trace entry, publishes it on the live run stream,
 * persists the trace so far, and wraps a traversal leg with the stream's run-level events. Pulled
 * out of the engine so the per-construct steps share it.
 */
final class RunTraceRecorder {

    /**
     * The run whose traversal is executing on this thread. Set by {@link #streamed}; trace entries
     * are always appended on that thread (parallel and fan-out results are joined back before their
     * entries are added), so {@link #publishNodeCompleted} can rely on it.
     */
    private static final ThreadLocal<String> CURRENT_STREAM_RUN = new ThreadLocal<>();

    private static final java.util.regex.Pattern APPROVAL_REQUEST_ID =
            java.util.regex.Pattern.compile("request ([0-9a-fA-F-]{36})");

    private final WorkflowRunRepository runRepository;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowGraphSupport graphSupport;
    private final ObjectMapper objectMapper;

    /** Nullable only for unit tests that construct the engine by hand. */
    private final NodeUsageResolver usageResolver;

    /** Live run events (phase 3); nullable for hand-built test engines. */
    private final WorkflowRunStreamHub streamHub;

    RunTraceRecorder(
            WorkflowRunRepository runRepository,
            WorkflowEventPublisher eventPublisher,
            WorkflowGraphSupport graphSupport,
            ObjectMapper objectMapper,
            NodeUsageResolver usageResolver,
            WorkflowRunStreamHub streamHub) {
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
        this.graphSupport = graphSupport;
        this.objectMapper = objectMapper;
        this.usageResolver = usageResolver;
        this.streamHub = streamHub;
    }

    /**
     * Per-step run data for the canvas run-overlay: attaches the node's sanitized input/output
     * ({@link NodeTracePayloads} - redaction, per-field cap, per-run budget against what {@code
     * trace} already stores) and, when {@code usageRunId} is non-null, its LLM tokens/cost ({@link
     * NodeUsageResolver}). Callers pass a null {@code usageRunId} wherever the node's calls cannot
     * be attributed unambiguously (concurrent parallel / fan-out entries, synthetic engine
     * entries).
     */
    NodeTraceEntry withRunData(
            List<NodeTraceEntry> trace,
            NodeTraceEntry entry,
            WorkflowNodeDto node,
            String input,
            String output,
            int perFieldCap,
            UUID usageRunId) {
        NodeTraceEntry enriched =
                NodeTracePayloads.attach(
                        entry,
                        input,
                        output,
                        perFieldCap,
                        NodeTracePayloads.payloadCharsUsed(trace));
        if (usageRunId != null && usageResolver != null) {
            enriched = usageResolver.attach(enriched, node, usageRunId.toString());
        }
        publishNodeCompleted(trace, enriched);
        return enriched;
    }

    /** {@link #withRunData} then append to the leg's trace. */
    void add(
            RunTraversalState state,
            NodeTraceEntry entry,
            WorkflowNodeDto node,
            String input,
            String output,
            int perFieldCap,
            UUID usageRunId) {
        state.trace.add(
                withRunData(state.trace, entry, node, input, output, perFieldCap, usageRunId));
    }

    /**
     * Real-time log viewer (#393): persist the trace so far, so a concurrent {@code GET
     * .../runs/{runId}} poll sees incremental progress instead of nothing until the run finishes.
     */
    void persist(RunTraversalState state) {
        state.run.updateTrace(graphSupport.writeTrace(state.trace));
        runRepository.save(state.run);
    }

    /** The {@code workflow.node.completed} bus event for one finished node. */
    void nodeCompleted(RunTraversalState state, WorkflowNodeDto node, String status) {
        eventPublisher.publish(
                "workflow.node.completed",
                state.runIdText(),
                Map.of("nodeId", node.id(), "nodeType", node.type(), "status", status));
    }

    /**
     * {@code node.completed} on the live run stream: the entry exactly as the trace will store it,
     * plus running totals over the trace so far. Every {@link #withRunData} call happens on a
     * traversal thread inside {@link #streamed}, which is what {@link #CURRENT_STREAM_RUN} names.
     */
    private void publishNodeCompleted(List<NodeTraceEntry> trace, NodeTraceEntry entry) {
        String runId = CURRENT_STREAM_RUN.get();
        if (streamHub == null || runId == null) {
            return;
        }
        Long tokensIn = entry.tokensIn();
        Long tokensOut = entry.tokensOut();
        BigDecimal cost = entry.costUsd();
        for (NodeTraceEntry prior : trace) {
            tokensIn = addNullable(tokensIn, prior.tokensIn());
            tokensOut = addNullable(tokensOut, prior.tokensOut());
            if (prior.costUsd() != null) {
                cost = cost == null ? prior.costUsd() : cost.add(prior.costUsd());
            }
        }
        Map<String, Object> totals = new HashMap<>();
        totals.put("tokensIn", tokensIn);
        totals.put("tokensOut", tokensOut);
        totals.put("costUsd", cost);
        Map<String, Object> data = new HashMap<>();
        data.put("entry", entry);
        data.put("runTotals", totals);
        streamHub.publish(runId, "node.completed", entry.nodeId(), data);
    }

    private static Long addNullable(Long a, Long b) {
        // if/else, not a ternary: a mixed Long/long conditional unboxes a null.
        if (b == null) {
            return a;
        }
        if (a == null) {
            return b;
        }
        return Long.valueOf(a + b);
    }

    /**
     * Wraps one traversal leg with the live stream's run-level events: {@code run.started}/{@code
     * run.resumed} before, and {@code run.finished} or {@code run.awaiting} (approval / input /
     * pause - with the halted node and, when its summary names one, the approval request id) after.
     */
    WorkflowRun streamed(
            WorkflowRun run,
            WorkflowDefinition definition,
            boolean resumed,
            Supplier<WorkflowRun> traversal) {
        if (streamHub == null) {
            return traversal.get();
        }
        String runId = run.getId().toString();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        streamHub.runStarted(runId, tenantId, definition.getId().toString(), resumed);
        String previous = CURRENT_STREAM_RUN.get();
        CURRENT_STREAM_RUN.set(runId);
        try {
            WorkflowRun settled = traversal.get();
            streamHub.runSettled(runId, String.valueOf(settled.getStatus()), settledData(settled));
            return settled;
        } catch (RuntimeException e) {
            Map<String, Object> data = new HashMap<>();
            data.put("status", WorkflowRunStatus.FAILED.name());
            data.put("error", e.getMessage());
            streamHub.runSettled(runId, WorkflowRunStatus.FAILED.name(), data);
            throw e;
        } finally {
            if (previous == null) {
                CURRENT_STREAM_RUN.remove();
            } else {
                CURRENT_STREAM_RUN.set(previous);
            }
        }
    }

    private Map<String, Object> settledData(WorkflowRun run) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", String.valueOf(run.getStatus()));
        data.put("error", run.getErrorMessage());
        List<NodeTraceEntry> trace = readTraceQuietly(run.getTraceJson());
        if (!trace.isEmpty() && run.getStatus() != WorkflowRunStatus.COMPLETED) {
            NodeTraceEntry last = trace.get(trace.size() - 1);
            data.put("nodeId", last.nodeId());
            data.put("label", last.label());
            data.put("summary", last.summary());
            java.util.regex.Matcher m = APPROVAL_REQUEST_ID.matcher(String.valueOf(last.summary()));
            if (m.find()) {
                data.put("requestId", m.group(1));
            }
        }
        return data;
    }

    private List<NodeTraceEntry> readTraceQuietly(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, NodeTraceEntry.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
