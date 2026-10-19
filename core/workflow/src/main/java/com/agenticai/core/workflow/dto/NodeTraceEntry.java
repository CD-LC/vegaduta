package com.agenticai.core.workflow.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One row of a run's execution trace - the data source for the canvas's
 * "every step shows its work" run-trace overlay (PRD Section 5). {@code
 * status} is one of {@code OK}, {@code ERROR}, {@code AWAITING_APPROVAL}, {@code PINNED} (draft/test runs only: the node did not run and its pinned data stood in - success-like, the run continues), or
 * {@code SKIPPED} (a node type whose backing feature - e.g. knowledge search
 * with the "knowledge" profile off - degraded gracefully rather than
 * failing the whole run).
 *
 * <p>{@code logLines} (added for #151, "customer should be able to see the
 * logs what exactly its happening") is a short, human-readable blow-by-blow
 * of what that one node actually did - e.g. "Calling agent <id>...",
 * "Received reply (128 chars)", "Sent email to ops@example.com via
 * smtp.example.com" - distinct from {@code summary}, which stays a single
 * truncated line for the compact list view. Free to extend per node type
 * with zero migration since {@link com.agenticai.core.workflow.WorkflowRun#getTraceJson()}
 * is a serialized JSON blob, not a normalized child table.
 *
 * <p><b>Per-step run data (2026-09-29, canvas run-overlay).</b> Additive
 * fields, all nullable so every trace persisted before them still
 * deserializes unchanged:
 * <ul>
 *   <li>{@code durationMs} - {@code completedAt - startedAt}, derived in the
 *       compact constructor whenever both instants are present, so every
 *       construction site gets it without passing it.</li>
 *   <li>{@code inputJson}/{@code outputJson} - the string the node received
 *       and the string it handed on, secret-redacted and capped by
 *       {@link com.agenticai.core.workflow.NodeTracePayloads} before they
 *       ever reach this record (traces are readable at VIEWER level).
 *       {@code inputTruncated}/{@code outputTruncated} say the cap cut them.
 *       Named "Json" to match the wire contract; the value is whatever text
 *       flowed between nodes, JSON or not.</li>
 *   <li>{@code tokensIn}/{@code tokensOut}/{@code costUsd} - summed from
 *       {@code llm_call_log} for agent/supervisor nodes only, and only when
 *       the node's LLM calls can be attributed to it unambiguously. Null means
 *       "unknown", never zero.</li>
 *   <li>{@code toolCalls} - Agent nodes only: the tools the agent called
 *       while this node ran ({@link ToolCallTrace}), from {@code
 *       tool_call_log}; null when none / not attributable.</li>
 * </ul>
 *
 * <p><b>{@code dryRun}</b> (draft runs started with {@code simulateSends}): a derived, write-only
 * JSON property - {@code true} on an entry whose node was simulated instead of sending anything,
 * absent on every other entry (so ordinary traces serialise byte-for-byte as before). It is not a
 * record component: it is computed from the {@code summary} prefix, and ignored when a trace is
 * read back.
 */
@JsonIgnoreProperties(value = "dryRun", allowGetters = true)
public record NodeTraceEntry(
    String nodeId,
    String nodeType,
    String label,
    String status,
    String summary,
    Instant startedAt,
    Instant completedAt,
    List<String> logLines,
    Long durationMs,
    String inputJson,
    String outputJson,
    Boolean inputTruncated,
    Boolean outputTruncated,
    Long tokensIn,
    Long tokensOut,
    BigDecimal costUsd,
    List<ToolCallTrace> toolCalls
) {
    /** The summary prefix of a node that was simulated in a dry run instead of sending anything. */
    public static final String DRY_RUN_SUMMARY_PREFIX = "DRY RUN - ";

    /** True when this node's send was simulated (draft run with {@code simulateSends}); otherwise absent. */
    @JsonProperty("dryRun")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean dryRun() {
        return summary != null && summary.startsWith(DRY_RUN_SUMMARY_PREFIX) ? Boolean.TRUE : null;
    }

    public NodeTraceEntry {
        logLines = logLines == null ? List.of() : logLines;
        if (durationMs == null && startedAt != null && completedAt != null) {
            durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
        }
    }

    /** Back-compat constructor for call sites that predate per-step run data. */
    public NodeTraceEntry(
        String nodeId, String nodeType, String label, String status, String summary, Instant startedAt,
        Instant completedAt, List<String> logLines
    ) {
        this(nodeId, nodeType, label, status, summary, startedAt, completedAt, logLines,
            null, null, null, null, null, null, null, null, null);
    }

    /** Back-compat factory for call sites that predate detailed logging. */
    public NodeTraceEntry(
        String nodeId, String nodeType, String label, String status, String summary, Instant startedAt, Instant completedAt
    ) {
        this(nodeId, nodeType, label, status, summary, startedAt, completedAt, List.of());
    }

    /**
     * Copy with the node's input/output attached. Callers pass values that
     * have ALREADY been through {@link com.agenticai.core.workflow.NodeTracePayloads}
     * - this record never stores a raw payload.
     */
    public NodeTraceEntry withPayloads(
        String inputJson, boolean inputTruncated, String outputJson, boolean outputTruncated
    ) {
        return new NodeTraceEntry(nodeId, nodeType, label, status, summary, startedAt, completedAt, logLines,
            durationMs, inputJson, outputJson, inputTruncated, outputTruncated, tokensIn, tokensOut, costUsd, toolCalls);
    }

    /** Copy with LLM usage attached; nulls mean unknown and are kept as null. */
    public NodeTraceEntry withUsage(Long tokensIn, Long tokensOut, BigDecimal costUsd) {
        return new NodeTraceEntry(nodeId, nodeType, label, status, summary, startedAt, completedAt, logLines,
            durationMs, inputJson, outputJson, inputTruncated, outputTruncated, tokensIn, tokensOut, costUsd, toolCalls);
    }

    /** Copy with the node's tool-call child rows attached (already redacted/capped by the caller). */
    public NodeTraceEntry withToolCalls(List<ToolCallTrace> toolCalls) {
        return new NodeTraceEntry(nodeId, nodeType, label, status, summary, startedAt, completedAt, logLines,
            durationMs, inputJson, outputJson, inputTruncated, outputTruncated, tokensIn, tokensOut, costUsd, toolCalls);
    }

    /**
     * One tool call an Agent node made - a child row under the node on the
     * canvas. {@code input}/{@code output} are redacted and capped the same way
     * as the node's own payloads; {@code error} is null on success.
     */
    public record ToolCallTrace(
        String toolName, String input, String output, String error, Instant startedAt, Long durationMs
    ) {
    }
}
