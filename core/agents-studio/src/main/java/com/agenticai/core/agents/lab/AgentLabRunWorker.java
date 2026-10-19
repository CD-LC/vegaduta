package com.agenticai.core.agents.lab;

import static com.agenticai.core.agents.lab.AgentLabDtos.CELL_DONE;
import static com.agenticai.core.agents.lab.AgentLabDtos.CELL_ERROR;
import static com.agenticai.core.agents.lab.AgentLabDtos.CELL_PENDING;
import static com.agenticai.core.agents.lab.AgentLabDtos.MODE_JUDGE;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_CANCELLED;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_FAILED;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_QUEUED;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_RUNNING;
import static com.agenticai.core.agents.lab.AgentLabDtos.RUN_SUCCEEDED;
import static com.agenticai.core.agents.lab.AgentLabDtos.SCORE_NONE;
import static com.agenticai.core.agents.lab.AgentLabDtos.SCORE_PENDING;
import static com.agenticai.core.agents.lab.AgentLabDtos.SCORE_SCORED;
import static com.agenticai.core.agents.lab.AgentLabDtos.SCORE_UNAVAILABLE;
import static com.agenticai.core.agents.lab.AgentLabDtos.SIMILARITY_DONE;
import static com.agenticai.core.agents.lab.AgentLabDtos.SIMILARITY_PARTIAL;
import static com.agenticai.core.agents.lab.AgentLabDtos.SIMILARITY_PENDING;
import static com.agenticai.core.agents.lab.AgentLabDtos.isTerminal;
import static com.agenticai.core.agents.lab.AgentLabRunService.DEADLINE_MESSAGE;
import static com.agenticai.core.agents.lab.AgentLabRunService.EMPTY_ANSWER_MESSAGE;
import static com.agenticai.core.agents.lab.AgentLabRunService.GENERIC_CALL_FAILURE;
import static com.agenticai.core.agents.lab.AgentLabRunService.INTERNAL_FAILURE_MESSAGE;
import static com.agenticai.core.agents.lab.AgentLabRunService.MAX_ANSWER_CHARS;
import static com.agenticai.core.agents.lab.AgentLabRunService.MAX_CELL_ERROR_CHARS;
import static com.agenticai.core.agents.lab.AgentLabRunService.RUN_DEADLINE_MINUTES;
import static com.agenticai.core.agents.lab.AgentLabRunService.TENANT_STOPPED_MESSAGE;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCellResult;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabSimilarity;
import com.agenticai.core.agents.lab.AgentLabJudge.ScoreVerdict;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantStatusGuard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker half of {@link AgentLabRunService}: runs one lab run's cells, judge scores and
 * similarity verdicts on an {@link AgentLabExecutor} thread, writing the run document through the
 * fenced {@link #write} before every model call. Split out of the service to keep both files under
 * the file-size cap; the rules it follows are the ones the service's class javadoc describes ("Who
 * may write the document").
 */
final class AgentLabRunWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentLabRunService.class);

    private static final int MAX_CONSECUTIVE_CELL_ERRORS = 3;

    private final TenantStatusGuard tenantStatusGuard;
    private final AgentLabCompletionService completionService;
    private final AgentLabJudge judge;
    private final AgentLabRunStore store;
    private final LongSupplier clock;
    /** Called with the run id when a worker ends, so the service takes the run off its heartbeat. */
    private final Consumer<String> onEnded;

    AgentLabRunWorker(
        TenantStatusGuard tenantStatusGuard,
        AgentLabCompletionService completionService,
        AgentLabJudge judge,
        AgentLabRunStore store,
        LongSupplier clock,
        Consumer<String> onEnded
    ) {
        this.tenantStatusGuard = tenantStatusGuard;
        this.completionService = completionService;
        this.judge = judge;
        this.store = store;
        this.clock = clock;
        this.onEnded = onEnded;
    }


    /** The worker's in-memory truth about one run; {@link #snapshot} is what gets stored. */
    static final class RunState {

        private final LabRunPlanner.RunPlan plan;
        private final LabCellResult[] cells;
        private String status = RUN_QUEUED;
        private int plannedCalls;
        private int completedCalls;
        private int judgeCalls;
        private boolean cancelRequested;
        private String errorMessage;
        private Long finishedAtMs;
        private String similarityStatus;
        private List<String> similarityKeys = List.of();
        private Double[][] matrix = new Double[0][0];

        RunState(LabRunPlanner.RunPlan plan) {
            this.plan = plan;
            this.plannedCalls = plan.plannedCalls();
            this.similarityStatus = plan.judgeSimilarity() ? SIMILARITY_PENDING : null;
            this.cells = new LabCellResult[plan.cells().size()];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = new LabCellResult(
                    plan.cells().get(i).key(), CELL_PENDING, null, false, null,
                    plan.judgeScoring() ? SCORE_PENDING : SCORE_NONE, null, null, null);
            }
        }

        LabRunDocument snapshot(long nowMs) {
            LabSimilarity similarity = null;
            if (similarityStatus != null) {
                List<List<Double>> rows = new ArrayList<>(matrix.length);
                for (Double[] row : matrix) {
                    // Arrays.asList, not List.of: a missing verdict is a null entry.
                    rows.add(new ArrayList<>(Arrays.asList(row)));
                }
                similarity = new LabSimilarity(MODE_JUDGE, similarityStatus, similarityKeys, rows);
            }
            return new LabRunDocument(
                new LabRunResponse(
                    plan.runId(), plan.agentId().toString(), plan.kind(), status,
                    plannedCalls, completedCalls, cancelRequested, errorMessage,
                    plan.createdAtMs(), nowMs, finishedAtMs,
                    List.of(cells.clone()), similarity),
                plan.subject());
        }
    }

    /** Thrown inside the worker to unwind once the run has reached a terminal state or lost its document. */
    private static final class RunStopped extends RuntimeException {

        RunStopped() {
            super(null, null, false, false);
        }
    }

    void run(LabRunPlanner.RunPlan plan, TenantContext tenant) {
        TenantContext.set(tenant);
        RunState state = new RunState(plan);
        try {
            state.status = RUN_RUNNING;
            runCells(state);
            if (plan.judgeSimilarity()) {
                compareAnswers(state);
            }
            finish(state, RUN_SUCCEEDED, null);
        } catch (RunStopped stopped) {
            // Terminal status already written, or the fence said this worker
            // no longer owns the run. Nothing more to do.
        } catch (RuntimeException e) {
            // Includes a store that went away mid-run: if this last write
            // fails too, the run stays live in the store with no worker. Its
            // liveness mark lapses once this worker deregisters (finally),
            // and the next poll fails it.
            log.error("Lab run {} stopped on an unexpected failure", plan.runId(), e);
            try {
                finish(state, RUN_FAILED, INTERNAL_FAILURE_MESSAGE);
            } catch (RuntimeException alreadyStopped) {
                log.debug("Lab run {} could not record its failure", plan.runId(), alreadyStopped);
            }
        } finally {
            // Compare-and-delete: a run that was ended from outside and
            // replaced cannot free the newer run's lock from here.
            releaseLockQuietly(plan.tenantId(), plan.subject(), plan.runId());
            // Off the heartbeat. The mark itself is left to lapse: the
            // document is terminal by now, and nothing reads the mark of a
            // terminal run.
            onEnded.accept(plan.runId());
            // Unconditional - these are pooled threads, and a leaked
            // TenantContext on a reused worker is a cross-tenant bug.
            TenantContext.clear();
        }
    }

    private void runCells(RunState state) {
        LabRunPlanner.RunPlan plan = state.plan;
        int consecutiveErrors = 0;
        for (int n = 0; n < plan.cells().size(); n++) {
            LabRunPlanner.Cell cell = plan.cells().get(n);
            beforeCall(state);
            long startedAtMs = clock.getAsLong();
            String answer;
            try {
                answer = completionService.complete(new LabCompletionRequest(
                    plan.agentId(),
                    cell.promptIndex() == null ? null : plan.systemPrompts().get(cell.promptIndex()),
                    cell.history(), cell.message(), null, null, plan.subject(),
                    "lab:" + plan.runId() + ":" + n));
            } catch (RuntimeException e) {
                state.completedCalls++;
                if (AgentLabCompletionService.isLimitFailure(e)) {
                    throw stop(state, RUN_FAILED, sentence(e.getMessage()));
                }
                String message = cellErrorMessage(e);
                log.warn("Lab run {} cell '{}' failed: {}", plan.runId(), cell.key(), e.toString());
                consecutiveErrors = failCell(state, n, message, startedAtMs, consecutiveErrors);
                continue;
            }
            state.completedCalls++;
            String text = answer == null ? "" : answer;
            if (text.isBlank()) {
                // Nothing came back (a thinking model that spent its whole
                // token budget on reasoning does this). That is a failed
                // call, not an answer: recorded as DONE it would be judged,
                // and k empty answers would compare as perfectly consistent.
                log.warn("Lab run {} cell '{}' got an empty completion", plan.runId(), cell.key());
                consecutiveErrors = failCell(state, n, EMPTY_ANSWER_MESSAGE, startedAtMs, consecutiveErrors);
                continue;
            }
            consecutiveErrors = 0;
            boolean truncated = text.length() > MAX_ANSWER_CHARS;
            String kept = truncated ? text.substring(0, MAX_ANSWER_CHARS) : text;
            long ms = clock.getAsLong() - startedAtMs;
            state.cells[n] = new LabCellResult(
                cell.key(), CELL_DONE, kept, truncated, null,
                plan.judgeScoring() ? SCORE_PENDING : SCORE_NONE, null, null, ms);

            if (plan.judgeScoring()) {
                beforeCall(state);
                ScoreVerdict verdict;
                try {
                    verdict = judge.score(
                        plan.agentId(), plan.subject(), judgeSession(state), cell.message(), cell.expected(), kept);
                } catch (RuntimeException e) {
                    // The judge swallows everything but a limit failure.
                    state.completedCalls++;
                    throw stop(state, RUN_FAILED, sentence(e.getMessage()));
                }
                state.completedCalls++;
                state.cells[n] = new LabCellResult(
                    cell.key(), CELL_DONE, kept, truncated,
                    verdict.scored() ? verdict.score() : null,
                    verdict.scored() ? SCORE_SCORED : SCORE_UNAVAILABLE,
                    verdict.feedback(), null, ms);
            }
        }
    }

    /**
     * Records cell {@code n} as ERROR - no answer, so no judge call and no
     * place in the similarity matrix - and fails the run on the third such
     * cell in a row.
     *
     * @return the new count of consecutive failed cells
     */
    private int failCell(RunState state, int n, String message, long startedAtMs, int consecutiveErrors) {
        LabRunPlanner.RunPlan plan = state.plan;
        state.cells[n] = new LabCellResult(
            plan.cells().get(n).key(), CELL_ERROR, null, false, null,
            plan.judgeScoring() ? SCORE_UNAVAILABLE : SCORE_NONE, null, message,
            clock.getAsLong() - startedAtMs);
        int errors = consecutiveErrors + 1;
        if (errors >= MAX_CONSECUTIVE_CELL_ERRORS) {
            throw stop(state, RUN_FAILED, "Three calls in a row failed. The last error: " + message);
        }
        return errors;
    }

    /**
     * One judge call per unordered pair of DONE cells. The question shown to
     * the judge is the first DONE cell's message: a Consistency run sends the
     * same message in every cell.
     */
    private void compareAnswers(RunState state) {
        LabRunPlanner.RunPlan plan = state.plan;
        List<Integer> done = new ArrayList<>();
        for (int i = 0; i < state.cells.length; i++) {
            if (CELL_DONE.equals(state.cells[i].status())) {
                done.add(i);
            }
        }
        int size = done.size();
        Double[][] matrix = new Double[size][size];
        List<String> keys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            matrix[i][i] = 1.0;
            keys.add(state.cells[done.get(i)].key());
        }
        state.similarityKeys = List.copyOf(keys);
        state.matrix = matrix;
        state.similarityStatus = SIMILARITY_PARTIAL;

        String question = size == 0 ? "" : plan.cells().get(done.get(0)).message();
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                beforeCall(state);
                Boolean same;
                try {
                    same = judge.same(
                        plan.agentId(), plan.subject(), judgeSession(state), question,
                        state.cells[done.get(i)].answer(), state.cells[done.get(j)].answer());
                } catch (RuntimeException e) {
                    state.completedCalls++;
                    throw stop(state, RUN_FAILED, sentence(e.getMessage()));
                }
                state.completedCalls++;
                // No verdict stays null. It is never recorded as "different".
                Double value = same == null ? null : (same ? 1.0 : 0.0);
                matrix[i][j] = value;
                matrix[j][i] = value;
            }
        }
        state.similarityStatus = SIMILARITY_DONE;
    }

    private static String judgeSession(RunState state) {
        return "lab:" + state.plan.runId() + ":judge:" + state.judgeCalls++;
    }

    /**
     * The gate in front of every model call, in this order: persist progress
     * (what the poll shows - liveness is the timer's job, not this write's), then cancel, then
     * the deadline, then the tenant kill switch - the same check {@code
     * AgentChatService#chat} makes, because a run started before an
     * emergency stop must not keep spending after it.
     */
    private void beforeCall(RunState state) {
        LabRunPlanner.RunPlan plan = state.plan;
        write(state);
        if (store.isCancelRequested(plan.tenantId(), plan.runId())) {
            state.cancelRequested = true;
            throw stop(state, RUN_CANCELLED, null);
        }
        if (clock.getAsLong() - plan.createdAtMs() >= TimeUnit.MINUTES.toMillis(RUN_DEADLINE_MINUTES)) {
            throw stop(state, RUN_FAILED, DEADLINE_MESSAGE);
        }
        if (!tenantStatusGuard.isUsable(plan.tenantId())) {
            throw stop(state, RUN_FAILED, TENANT_STOPPED_MESSAGE);
        }
    }

    /**
     * The fenced write. Re-reads the stored document first and gives up the
     * run - without writing - when it is gone or already terminal, so a
     * worker that outlived its run (ended from outside, deleted, expired) can
     * never turn it back into a live one.
     */
    private void write(RunState state) {
        LabRunPlanner.RunPlan plan = state.plan;
        Optional<LabRunDocument> stored = store.find(plan.tenantId(), plan.runId());
        if (stored.isEmpty() || isTerminal(stored.get().run().status())) {
            log.info("Lab run {} is no longer live in the store - worker stopping", plan.runId());
            throw new RunStopped();
        }
        store.save(plan.tenantId(), state.snapshot(clock.getAsLong()));
    }

    private void finish(RunState state, String status, String errorMessage) {
        state.status = status;
        state.errorMessage = errorMessage;
        state.finishedAtMs = clock.getAsLong();
        if (RUN_SUCCEEDED.equals(status)) {
            // The estimate assumed every cell answers; judge calls for failed
            // cells never happen. A finished run reports what it really made.
            state.plannedCalls = state.completedCalls;
        }
        write(state);
    }

    /** {@link #finish}, then unwind the worker. Declared to return so call sites can {@code throw} it. */
    private RunStopped stop(RunState state, String status, String errorMessage) {
        finish(state, status, errorMessage);
        throw new RunStopped();
    }

    /**
     * What a failed cell shows the user. A provider failure uses the
     * translator's consumer message; a platform exception's own message is
     * written for users; anything else is internal detail and gets a plain
     * sentence (the real one is in the log).
     */
    private static String cellErrorMessage(RuntimeException e) {
        String message = null;
        if (e instanceof UpstreamProviderException upstream) {
            message = upstream.getConsumerMessage();
        } else if (e.getClass().getName().startsWith("com.agenticai.")) {
            message = e.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = GENERIC_CALL_FAILURE;
        }
        message = sentence(message);
        return message.length() <= MAX_CELL_ERROR_CHARS
            ? message
            : message.substring(0, MAX_CELL_ERROR_CHARS - 1) + "…";
    }

    /** {@code errorMessage} is always a full sentence on the wire. */
    private static String sentence(String message) {
        if (message == null || message.isBlank()) {
            return "The run was stopped.";
        }
        String trimmed = message.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…' ? trimmed : trimmed + ".";
    }

    void releaseLockQuietly(String tenantId, String subject, String runId) {
        try {
            store.releaseLock(tenantId, subject, runId);
        } catch (RuntimeException e) {
            log.warn("Could not release the lab lock for run {} - it expires on its own", runId, e);
        }
    }
}
