package com.agenticai.core.workflow.guidance;

import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunRepository;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-workflow and per-node run statistics for the canvas's badges (phase 4, "eval badges"), and
 * the source of the lint panel's cost/latency hints.
 *
 * <p><b>What the numbers are.</b> Everything except {@code evalScore} is derived from STORED runs:
 * the newest {@link #MAX_RUNS_SCANNED} runs inside the {@code days} window, and each run's
 * persisted trace. A node that ran several times in one run (loop / fan-out / sub-flow items,
 * retries recorded as entries) counts every entry as one visit; {@code runs} counts the distinct
 * runs it appeared in. Success rate = OK / (OK + ERROR) visits - SKIPPED, AWAITING_* and CANCELLED
 * visits are neither. Durations are medians; cost is the mean over visits that HAVE a cost (null
 * when none do, never a fabricated zero). {@code sampled: true} says the window held more runs than
 * were scanned.
 *
 * <p><b>evalScore</b> is real evaluation, not run telemetry: for an Agent node, the latest
 * SUCCEEDED eval run of that agent (core/quality), with its verdict and finish time. Workflows have
 * no eval datasets of their own, so the workflow-level {@code passRate} is always null today - the
 * canvas shows it only when present.
 *
 * <p>Cached for {@link #CACHE_TTL} per tenant+workflow+window: the lint endpoint is called on a 1.5
 * s debounce while someone edits, and its hints must not re-parse every trace each time.
 */
@Service
public class WorkflowInsightsService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInsightsService.class);

    static final int MAX_RUNS_SCANNED = 500;
    static final int MAX_DAYS = 90;
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int MAX_CACHE_ENTRIES = 2000;

    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EvalRunRepository> evalRuns;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    // @Autowired is required: the Clock constructor below exists for tests,
    // and with two constructors and no marker Spring cannot pick one (the
    // 2026-09-24 prod crash-loop class - InjectableConstructorTest).
    @Autowired
    public WorkflowInsightsService(
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper,
            ObjectProvider<EvalRunRepository> evalRuns) {
        this(runRepository, objectMapper, evalRuns, Clock.systemUTC());
    }

    WorkflowInsightsService(
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper,
            ObjectProvider<EvalRunRepository> evalRuns,
            Clock clock) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.evalRuns = evalRuns;
        this.clock = clock;
    }

    private record Cached(Instant at, Map<String, Object> value) {}

    /**
     * The badge summary. Caller has checked VIEWER on {@code workflowId}; {@code graph} (the saved
     * graph) supplies node types/agent ids for eval scores and may be null.
     */
    public Map<String, Object> summary(UUID workflowId, WorkflowGraphDto graph, int days) {
        int window = Math.max(1, Math.min(MAX_DAYS, days));
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String key = tenant + "|" + workflowId + "|" + window;
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && Duration.between(hit.at(), now).compareTo(CACHE_TTL) < 0) {
            return hit.value();
        }
        Map<String, Object> value = compute(workflowId, graph, window, now);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(now, value));
        return value;
    }

    private Map<String, Object> compute(
            UUID workflowId, WorkflowGraphDto graph, int days, Instant now) {
        List<WorkflowRun> runs =
                runRepository.findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(
                        workflowId,
                        now.minus(Duration.ofDays(days)),
                        PageRequest.of(0, MAX_RUNS_SCANNED + 1));
        boolean sampled = runs.size() > MAX_RUNS_SCANNED;
        if (sampled) {
            runs = runs.subList(0, MAX_RUNS_SCANNED);
        }

        int completed = 0;
        int failed = 0;
        List<Long> runDurations = new ArrayList<>();
        List<BigDecimal> runCosts = new ArrayList<>();
        Map<String, NodeAcc> nodes = new LinkedHashMap<>();
        Instant lastRunAt = null;
        String lastRunStatus = null;

        for (WorkflowRun run : runs) {
            if (lastRunAt == null) {
                lastRunAt = run.getStartedAt();
                lastRunStatus = String.valueOf(run.getStatus());
            }
            if (run.getStatus() == WorkflowRunStatus.COMPLETED) {
                completed++;
            } else if (run.getStatus() == WorkflowRunStatus.FAILED) {
                failed++;
            }
            if (run.getStartedAt() != null
                    && run.getCompletedAt() != null
                    && (run.getStatus() == WorkflowRunStatus.COMPLETED
                            || run.getStatus() == WorkflowRunStatus.FAILED)) {
                runDurations.add(
                        Math.max(
                                0L,
                                Duration.between(run.getStartedAt(), run.getCompletedAt())
                                        .toMillis()));
            }
            BigDecimal runCost = null;
            Set<String> seenInRun = new HashSet<>();
            // Runs are newest-first, so the first visit seen for a node is its latest.
            for (NodeTraceEntry entry : readTrace(run.getTraceJson())) {
                if (entry.nodeId() == null) {
                    continue;
                }
                NodeAcc acc = nodes.computeIfAbsent(entry.nodeId(), id -> new NodeAcc());
                acc.visit(entry, seenInRun.add(entry.nodeId()));
                if (entry.costUsd() != null) {
                    runCost = runCost == null ? entry.costUsd() : runCost.add(entry.costUsd());
                }
            }
            if (runCost != null) {
                runCosts.add(runCost);
            }
        }

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("runs", runs.size());
        workflow.put(
                "runs30d",
                runs.size()); // name kept from the agreed contract; counts `days`, not always 30
        workflow.put("days", days);
        workflow.put("sampled", sampled);
        workflow.put("successRate", rate(completed, completed + failed));
        workflow.put("successRate30d", rate(completed, completed + failed));
        workflow.put("passRate", null);
        workflow.put("p50DurationMs", median(runDurations));
        workflow.put("avgCostUsd", mean(runCosts));
        workflow.put("lastRunAt", lastRunAt);
        workflow.put("lastRunStatus", lastRunStatus);

        Map<String, Map<String, Object>> evalByAgent = new HashMap<>();
        Map<String, Object> nodeStats = new LinkedHashMap<>();
        for (Map.Entry<String, NodeAcc> e : nodes.entrySet()) {
            nodeStats.put(e.getKey(), e.getValue().toMap());
        }
        // Eval scores for agent nodes of the current graph, even ones with no runs yet.
        for (WorkflowNodeDto node : graph == null ? List.<WorkflowNodeDto>of() : graph.nodes()) {
            Map<String, Object> eval = evalFor(node, evalByAgent);
            if (eval == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> stats =
                    (Map<String, Object>)
                            nodeStats.computeIfAbsent(node.id(), id -> new NodeAcc().toMap());
            stats.putAll(eval);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflow", workflow);
        result.put("nodes", nodeStats);
        result.put("generatedAt", now);
        return result;
    }

    private Map<String, Object> evalFor(
            WorkflowNodeDto node, Map<String, Map<String, Object>> byAgent) {
        if (!"agent".equalsIgnoreCase(node.type())) {
            return null;
        }
        Object raw = node.config().get("agentId");
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        String agentId = raw.toString();
        if (byAgent.containsKey(agentId)) {
            return byAgent.get(agentId);
        }
        Map<String, Object> eval = null;
        EvalRunRepository repo = evalRuns.getIfAvailable();
        if (repo != null) {
            try {
                Optional<EvalRun> latest =
                        repo.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                                UUID.fromString(agentId), EvalRunStatus.SUCCEEDED);
                if (latest.isPresent() && latest.get().getOverallScore() != null) {
                    eval = new LinkedHashMap<>();
                    eval.put("evalScore", latest.get().getOverallScore());
                    eval.put(
                            "evalVerdict",
                            latest.get().getVerdict() == null
                                    ? null
                                    : latest.get().getVerdict().name());
                    eval.put("evalRunAt", latest.get().getFinishedAt());
                }
            } catch (RuntimeException ex) {
                log.debug("No eval score for agent {}: {}", agentId, ex.getMessage());
            }
        }
        byAgent.put(agentId, eval);
        return eval;
    }

    private List<NodeTraceEntry> readTrace(String json) {
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

    /** One node's running totals across the scanned runs. */
    static final class NodeAcc {
        int visits;
        int runs;
        int ok;
        int error;
        final List<Long> durations = new ArrayList<>();
        final List<BigDecimal> costs = new ArrayList<>();
        String lastStatus;
        Instant lastAt;

        void visit(NodeTraceEntry entry, boolean firstInRun) {
            visits++;
            if (firstInRun) {
                runs++;
            }
            String status = entry.status() == null ? "" : entry.status();
            if ("OK".equals(status)) {
                ok++;
            } else if ("ERROR".equals(status)) {
                error++;
            }
            if (entry.durationMs() != null) {
                durations.add(entry.durationMs());
            }
            if (entry.costUsd() != null) {
                costs.add(entry.costUsd());
            }
            Instant at = entry.completedAt() != null ? entry.completedAt() : entry.startedAt();
            if (lastAt == null || (at != null && at.isAfter(lastAt))) {
                lastAt = at;
                lastStatus = entry.status();
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runs", runs);
            m.put("visits", visits);
            m.put("successRate", rate(ok, ok + error));
            m.put("errorRate", rate(error, ok + error));
            m.put("p50DurationMs", median(durations));
            m.put("avgCostUsd", mean(costs));
            m.put("lastStatus", lastStatus);
            return m;
        }
    }

    static Double rate(int part, int whole) {
        return whole == 0 ? null : Math.round(10000.0 * part / whole) / 10000.0;
    }

    static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }
}
