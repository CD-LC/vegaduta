package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Exactly-once bookkeeping for the irreversible nodes of the workflow engines (V680 {@code
 * workflow_effect_journal}). Workflow execution is at-least-once - node retries, Temporal activity
 * retries, approval resumes and re-runs after a crash can all reach the same {@code (run, node,
 * iteration)} twice - so around the node's real side effect:
 *
 * <ol>
 *   <li>claim the key with a durable {@code STARTED} row <em>before</em> the effect;
 *   <li>on success record {@code COMPLETED} with the result <em>after</em> it;
 *   <li>a later execution of a {@code COMPLETED} key returns the recorded result with a trace line
 *       and does <b>not</b> run the effect;
 *   <li>a {@code STARTED}/{@code UNCERTAIN} row with no completion means the process died between
 *       the call and the bookkeeping: whether the effect happened is unknown, so it is never
 *       silently re-run - {@code app.workflow.effects.on-uncertain=fail} (default) halts the node
 *       FAILED with an explicit message, {@code rerun} takes the key over and runs it again;
 *   <li>a handler exception, or a halt (e.g. awaiting approval), releases the key ({@code FAILED})
 *       so a legitimate retry can claim it again - same posture as the Redis last-hop guards.
 * </ol>
 *
 * <p>The iteration key is the loop / fan-out / sub-flow item index of the bound {@link
 * ExpressionScope} ({@code -} outside an iteration), so parallel branches (distinct nodes) and
 * iterations (distinct indexes) never share a key. The same stable key is exposed to handlers via
 * {@link EffectIdempotency} for external APIs that accept one. Disabled
 * ({@code app.workflow.effects.journal.enabled=false}) the executor calls the effect directly, as
 * before.
 */
@Component
public class WorkflowEffectJournal {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEffectJournal.class);

    /** Node types whose side effect cannot be undone (http only when the method is not safe). */
    static final Set<String> IRREVERSIBLE_TYPES =
            Set.of(
                    "bulk_email",
                    "tool",
                    "crm_lead",
                    "device_control",
                    "media",
                    "output",
                    "workflow");

    private static final Set<String> SAFE_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final WorkflowEffectJournalRepository repository;
    private final boolean enabled;
    private final boolean rerunOnUncertain;
    private final int maxOutputChars;

    public WorkflowEffectJournal(
            WorkflowEffectJournalRepository repository,
            @Value("${app.workflow.effects.journal.enabled:true}") boolean enabled,
            @Value("${app.workflow.effects.on-uncertain:fail}") String onUncertain,
            @Value("${app.workflow.effects.journal.max-output-chars:200000}") int maxOutputChars) {
        this.repository = repository;
        this.enabled = enabled;
        this.rerunOnUncertain = "rerun".equalsIgnoreCase(onUncertain == null ? "" : onUncertain.strip());
        this.maxOutputChars = Math.max(1_000, maxOutputChars);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when {@code node} (already expression-resolved) has an irreversible side effect. */
    static boolean isIrreversible(WorkflowNodeDto node) {
        if (node == null || node.type() == null) {
            return false;
        }
        String type = node.type().toLowerCase(Locale.ROOT);
        if ("http".equals(type)) {
            Object method = node.config() == null ? null : node.config().get("method");
            String m =
                    method == null || method.toString().isBlank()
                            ? "GET"
                            : method.toString().strip().toUpperCase(Locale.ROOT);
            return !SAFE_HTTP_METHODS.contains(m);
        }
        return IRREVERSIBLE_TYPES.contains(type);
    }

    /** Iteration part of the key: the scope's item index, or {@code -} outside an iteration. */
    static String iterationKey(ExpressionScope scope) {
        return scope == null || scope.index() == null ? "-" : String.valueOf(scope.index());
    }

    /**
     * Runs {@code effect} under the journal protocol, or directly when the journal is off, the node
     * is reversible, or the run is not a persisted run (step test).
     */
    public NodeExecutionResult guard(
            String runIdText,
            WorkflowNodeDto node,
            ExpressionScope scope,
            Supplier<NodeExecutionResult> effect) {
        UUID runId = parseRunId(runIdText);
        if (!enabled
                || runId == null
                || node.id() == null
                || !isIrreversible(node)
                || (scope != null && scope.recordingSkipped())) {
            return effect.get();
        }
        String iteration = iterationKey(scope);
        String key = EffectIdempotency.keyFor(runIdText, node.id(), iteration);
        String type = node.type().toLowerCase(Locale.ROOT);

        List<String> notes = new java.util.ArrayList<>();
        if (repository.claim(runId, node.id(), type, iteration, key) == 0) {
            WorkflowEffectJournalEntry existing =
                    repository
                            .findByRunIdAndNodeIdAndIterationKey(runId, node.id(), iteration)
                            .orElse(null);
            if (existing == null) {
                // Purged between the failed insert and the read: treat as a fresh claim.
                return guard(runIdText, node, scope, effect);
            }
            switch (existing.getStatus()) {
                case WorkflowEffectJournalEntry.COMPLETED:
                    return replay(existing, key);
                case WorkflowEffectJournalEntry.FAILED:
                    if (repository.takeOver(
                                    runId, node.id(), iteration, List.of(WorkflowEffectJournalEntry.FAILED))
                            == 0) {
                        return uncertain(runId, node, iteration, existing, "claimed concurrently");
                    }
                    break;
                default: // STARTED / UNCERTAIN
                    if (!rerunOnUncertain) {
                        return uncertain(runId, node, iteration, existing, null);
                    }
                    if (repository.takeOver(
                                    runId,
                                    node.id(),
                                    iteration,
                                    List.of(
                                            WorkflowEffectJournalEntry.STARTED,
                                            WorkflowEffectJournalEntry.UNCERTAIN))
                            == 0) {
                        return uncertain(runId, node, iteration, existing, "claimed concurrently");
                    }
                    String warn =
                            "Effect journal: a previous attempt (started "
                                    + existing.getStartedAt()
                                    + ") never recorded completing; re-running because"
                                    + " app.workflow.effects.on-uncertain=rerun - the external effect"
                                    + " may now happen twice.";
                    log.warn("Run {} node {} (iteration {}): {}", runId, node.id(), iteration, warn);
                    notes.add(warn);
            }
        }

        EffectIdempotency.bind(key);
        NodeExecutionResult result;
        try {
            result = effect.get();
        } catch (RuntimeException | Error failure) {
            release(runId, node.id(), iteration);
            throw failure;
        } finally {
            EffectIdempotency.clear();
        }
        if (result.haltRun() != null) {
            // Halted (e.g. awaiting approval): the effect did not run; free the key for the resume.
            release(runId, node.id(), iteration);
            return result;
        }
        try {
            String output = result.output();
            boolean truncated = output != null && output.length() > maxOutputChars;
            repository.complete(
                    runId,
                    node.id(),
                    iteration,
                    result.status(),
                    truncated ? output.substring(0, maxOutputChars) : output,
                    truncated,
                    digest(output),
                    result.branch(),
                    result.summary(),
                    result.fileName());
        } catch (RuntimeException bookkeepingFailure) {
            // The effect already happened: never fail the node for it. The row stays STARTED, so a
            // re-execution is treated as uncertain rather than silently duplicated.
            log.error(
                    "Effect journal: could not record completion of run {} node {} (iteration {});"
                            + " the row stays STARTED and a re-run will be treated as uncertain.",
                    runId,
                    node.id(),
                    iteration,
                    bookkeepingFailure);
        }
        if (notes.isEmpty()) {
            return result;
        }
        List<String> lines = new java.util.ArrayList<>(result.logLines());
        lines.addAll(notes);
        return new NodeExecutionResult(
                result.output(),
                result.status(),
                result.summary(),
                result.branch(),
                result.haltRun(),
                lines,
                result.fileName(),
                result.fileData());
    }

    /**
     * Operator decision for an uncertain effect. {@code effectHappened=true} records the node as
     * COMPLETED with {@code outputIfHappened} (so a re-execution replays it); {@code false} releases
     * the key (FAILED) so the node may run again. Returns false when no such uncertain row exists.
     */
    public boolean resolveUncertain(
            UUID runId,
            String nodeId,
            String iterationKey,
            boolean effectHappened,
            String outputIfHappened) {
        Optional<WorkflowEffectJournalEntry> row =
                repository.findByRunIdAndNodeIdAndIterationKey(runId, nodeId, iterationKey);
        if (row.isEmpty()
                || !(WorkflowEffectJournalEntry.STARTED.equals(row.get().getStatus())
                        || WorkflowEffectJournalEntry.UNCERTAIN.equals(row.get().getStatus()))) {
            return false;
        }
        if (!effectHappened) {
            return repository.transition(runId, nodeId, iterationKey, WorkflowEffectJournalEntry.FAILED)
                    > 0;
        }
        String out = outputIfHappened == null ? "" : outputIfHappened;
        boolean truncated = out.length() > maxOutputChars;
        return repository.complete(
                        runId,
                        nodeId,
                        iterationKey,
                        "OK",
                        truncated ? out.substring(0, maxOutputChars) : out,
                        truncated,
                        digest(out),
                        null,
                        "Resolved by operator: effect confirmed as done.",
                        null)
                > 0;
    }

    /** Rows of a run whose effect is unknown (STARTED/UNCERTAIN), oldest first. */
    public List<WorkflowEffectJournalEntry> uncertainEffects(UUID runId) {
        return repository.findByRunIdAndStatusInOrderByStartedAtAsc(
                runId,
                List.of(WorkflowEffectJournalEntry.STARTED, WorkflowEffectJournalEntry.UNCERTAIN));
    }

    /** Retention purge; returns rows deleted. */
    public int purgeOlderThan(java.time.Instant cutoff) {
        return repository.purgeStartedBefore(cutoff);
    }

    /** Called for a dead run: every still-STARTED row becomes UNCERTAIN. */
    public int markRunEffectsUncertain(UUID runId) {
        return repository.markStartedUncertain(runId);
    }

    private void release(UUID runId, String nodeId, String iteration) {
        try {
            repository.transition(runId, nodeId, iteration, WorkflowEffectJournalEntry.FAILED);
        } catch (RuntimeException e) {
            log.warn(
                    "Effect journal: could not release run {} node {} (iteration {}); a retry will"
                            + " be treated as uncertain.",
                    runId,
                    nodeId,
                    iteration,
                    e);
        }
    }

    private NodeExecutionResult replay(WorkflowEffectJournalEntry e, String key) {
        String line =
                "Effect journal: this node already COMPLETED for this run/iteration (attempt "
                        + e.getAttemptCount()
                        + ", "
                        + e.getCompletedAt()
                        + ", idempotency key "
                        + key
                        + "); recorded result replayed, side effect NOT re-run."
                        + (e.isResultOutputTruncated()
                                ? " (Recorded output was truncated to the journal limit.)"
                                : "");
        String status = e.getResultStatus() == null ? "OK" : e.getResultStatus();
        return new NodeExecutionResult(
                e.getResultOutput(),
                status,
                (e.getResultSummary() == null ? "" : e.getResultSummary() + " ")
                        + "[replayed from effect journal - not re-executed]",
                e.getResultBranch(),
                null,
                List.of(line),
                e.getResultFileName(),
                null);
    }

    private NodeExecutionResult uncertain(
            UUID runId,
            WorkflowNodeDto node,
            String iteration,
            WorkflowEffectJournalEntry existing,
            String why) {
        try {
            repository.transition(
                    runId, node.id(), iteration, WorkflowEffectJournalEntry.UNCERTAIN);
        } catch (RuntimeException e) {
            log.warn("Effect journal: could not mark uncertain ({}).", e.toString());
        }
        String message =
                "Effect uncertain at node '"
                        + node.label()
                        + "' ("
                        + node.id()
                        + (iteration.equals("-") ? "" : ", iteration " + iteration)
                        + "): its external side effect was started at "
                        + existing.getStartedAt()
                        + " but never recorded as completed"
                        + (why == null ? "" : " (" + why + ")")
                        + ", so it may or may not have happened. Not re-running automatically"
                        + " (app.workflow.effects.on-uncertain=fail). Check the destination, then"
                        + " resolve journal entry "
                        + EffectIdempotency.keyFor(runId.toString(), node.id(), iteration)
                        + " (completed or released) or set on-uncertain=rerun. Resumable from node '"
                        + node.label()
                        + "' once decided.";
        log.warn("Run {}: {}", runId, message);
        return new NodeExecutionResult(
                null,
                WorkflowRunStatus.FAILED.name(),
                message,
                null,
                WorkflowRunStatus.FAILED,
                List.of(
                        "Effect journal: "
                                + existing.getStatus()
                                + " row found without COMPLETED (attempt "
                                + existing.getAttemptCount()
                                + "); effect uncertain, node not executed."),
                null,
                null);
    }

    private static UUID parseRunId(String runIdText) {
        if (runIdText == null) {
            return null;
        }
        try {
            return UUID.fromString(runIdText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String digest(String output) {
        if (output == null) {
            return null;
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(output.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
