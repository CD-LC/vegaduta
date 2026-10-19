package com.agenticai.core.chat;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase P4 (Spend &amp; Reports). Two responsibilities, kept together because
 * they share one grain model and one aggregation code path:
 *
 * <ol>
 *   <li><b>Pre-aggregation</b> ({@link #rollupDay}) - reads a whole UTC day of
 *       {@code llm_call_log} for the CURRENT tenant schema, grouped into
 *       (provider, model, agent, modality) grains, and idempotently upserts
 *       them into {@code llm_daily_usage_rollup} (V119). Driven once a day by
 *       {@code LlmUsageRollupScheduler} across every tenant. §8: dashboards
 *       must never scan the hot {@code llm_call_log} at volume.</li>
 *   <li><b>Reporting</b> ({@link #summary}/{@link #byDay}/{@link #byModel}/…)
 *       - serves each breakdown from the rollup for historical days and falls
 *       back to a single bounded live aggregation for TODAY only (the one day
 *       not yet rolled up). Both sources produce the same {@link Grain}, so
 *       the folding below is source-agnostic.</li>
 * </ol>
 *
 * <p><b>Latency percentiles under aggregation.</b> Each rollup grain stores an
 * exact p50/p95 (Postgres {@code percentile_cont} over that grain's raw rows).
 * A report row that merges several grains cannot recombine true percentiles
 * from stored ones, so it reports a <em>call-weighted average</em> of the
 * contributing grains' percentiles - a documented approximation. The mean
 * latency ({@code avgLatencyMs} = Σ latency / Σ calls) is always exact, which
 * is why the grain also stores {@code latency_sum_ms}.
 */
@Service
public class LlmUsageRollupService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRollupService.class);

    private final LlmCallLogRepository callLogRepository;
    private final LlmDailyUsageRollupRepository rollupRepository;

    public LlmUsageRollupService(
        LlmCallLogRepository callLogRepository,
        LlmDailyUsageRollupRepository rollupRepository
    ) {
        this.callLogRepository = callLogRepository;
        this.rollupRepository = rollupRepository;
    }

    // --- pre-aggregation ----------------------------------------------------

    /**
     * Idempotently (re)aggregate one UTC day into the rollup for the current
     * tenant schema. Recomputes the whole day from scratch and overwrites each
     * grain's aggregates, so a retry or backfill self-heals rather than
     * double-counting. Returns the number of grains written/updated.
     */
    @Transactional
    public int rollupDay(LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<LlmCallLogRepository.UsageGrainRow> rows = callLogRepository.aggregateGrains(from, to);
        int written = 0;
        for (LlmCallLogRepository.UsageGrainRow row : rows) {
            String modality = ModalityClassifier.classify(row.getModel());
            Optional<LlmDailyUsageRollup> existing = rollupRepository.findGrain(
                day, row.getProviderName(), row.getModel(), row.getAgentId(), modality
            );
            LlmDailyUsageRollup entity = existing.orElseGet(() -> new LlmDailyUsageRollup(
                day, row.getProviderName(), row.getModel(), row.getAgentId(), modality
            ));
            entity.applyAggregates(
                row.getTotalCalls(),
                row.getErrorCount(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getTotalTokens(),
                row.getEstimatedCostUsd(),
                row.getLatencySumMs(),
                row.getLatencyP50Ms(),
                row.getLatencyP95Ms(),
                row.getDistinctSessions()
            );
            rollupRepository.save(entity);
            written++;
        }
        log.debug("Rolled up {} llm-usage grain(s) for {}", written, day);
        return written;
    }

    // --- reporting ----------------------------------------------------------

    @Transactional(readOnly = true)
    public SpendReport.Summary summary(LocalDate fromDate, LocalDate toDate) {
        List<Grain> grains = loadGrains(fromDate, toDate);
        return new SpendReport.Summary(fromDate, toDate, includesToday(toDate), fold(grains));
    }

    @Transactional(readOnly = true)
    public List<SpendReport.DayUsage> byDay(LocalDate fromDate, LocalDate toDate) {
        return groupAndFold(loadGrains(fromDate, toDate), g -> g.usageDate)
            .entrySet().stream()
            .map(e -> new SpendReport.DayUsage(e.getKey(), e.getValue()))
            .sorted(Comparator.comparing(SpendReport.DayUsage::date))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SpendReport.ModelUsage> byModel(LocalDate fromDate, LocalDate toDate) {
        List<Grain> grains = loadGrains(fromDate, toDate);
        Map<String, List<Grain>> byModel = new LinkedHashMap<>();
        for (Grain g : grains) {
            byModel.computeIfAbsent(g.model, k -> new ArrayList<>()).add(g);
        }
        return byModel.entrySet().stream()
            .map(e -> new SpendReport.ModelUsage(
                e.getKey(),
                e.getValue().isEmpty() ? ModalityClassifier.OTHER : e.getValue().get(0).modality,
                fold(e.getValue())
            ))
            .sorted(costDescending(SpendReport.ModelUsage::metrics))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SpendReport.ProviderUsage> byProvider(LocalDate fromDate, LocalDate toDate) {
        return groupAndFold(loadGrains(fromDate, toDate), g -> g.providerName)
            .entrySet().stream()
            .map(e -> new SpendReport.ProviderUsage(e.getKey(), e.getValue()))
            .sorted(costDescending(SpendReport.ProviderUsage::metrics))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SpendReport.AgentUsage> byAgent(LocalDate fromDate, LocalDate toDate) {
        // agentId key may be null (system calls) - use Optional as the map key
        // so the null-agent bucket survives grouping.
        List<Grain> grains = loadGrains(fromDate, toDate);
        Map<Optional<UUID>, List<Grain>> grouped = new LinkedHashMap<>();
        for (Grain g : grains) {
            grouped.computeIfAbsent(Optional.ofNullable(g.agentId), k -> new ArrayList<>()).add(g);
        }
        return grouped.entrySet().stream()
            .map(e -> new SpendReport.AgentUsage(e.getKey().orElse(null), fold(e.getValue())))
            .sorted(costDescending(SpendReport.AgentUsage::metrics))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SpendReport.ModalityUsage> byModality(LocalDate fromDate, LocalDate toDate) {
        return groupAndFold(loadGrains(fromDate, toDate), g -> g.modality)
            .entrySet().stream()
            .map(e -> new SpendReport.ModalityUsage(e.getKey(), e.getValue()))
            .sorted(costDescending(SpendReport.ModalityUsage::metrics))
            .toList();
    }

    // --- CSV export (streamed) ---------------------------------------------

    /**
     * Streams the chosen breakdown as CSV straight to the response writer -
     * the controller wires this into a {@code StreamingResponseBody} so a
     * large range never materializes a giant string or list in memory beyond
     * the (already bounded) rollup rows themselves.
     */
    @Transactional(readOnly = true)
    public void writeCsv(Writer writer, LocalDate fromDate, LocalDate toDate, SpendReport.GroupBy groupBy)
        throws IOException {
        String dimensionHeader = switch (groupBy) {
            case DAY -> "date";
            case MODEL -> "model,modality";
            case PROVIDER -> "provider";
            case AGENT -> "agent_id";
            case MODALITY -> "modality";
        };
        writer.write(dimensionHeader
            + ",total_calls,error_count,success_rate,prompt_tokens,completion_tokens,total_tokens,"
            + "message_volume,distinct_sessions,estimated_cost_usd,avg_latency_ms,latency_p50_ms,latency_p95_ms\n");

        switch (groupBy) {
            case DAY -> {
                for (SpendReport.DayUsage r : byDay(fromDate, toDate)) {
                    writer.write(r.date() + "," + metricsCsv(r.metrics()) + "\n");
                }
            }
            case MODEL -> {
                for (SpendReport.ModelUsage r : byModel(fromDate, toDate)) {
                    writer.write(csv(r.model()) + "," + csv(r.modality()) + "," + metricsCsv(r.metrics()) + "\n");
                }
            }
            case PROVIDER -> {
                for (SpendReport.ProviderUsage r : byProvider(fromDate, toDate)) {
                    writer.write(csv(r.providerName()) + "," + metricsCsv(r.metrics()) + "\n");
                }
            }
            case AGENT -> {
                for (SpendReport.AgentUsage r : byAgent(fromDate, toDate)) {
                    writer.write((r.agentId() == null ? "system" : r.agentId().toString())
                        + "," + metricsCsv(r.metrics()) + "\n");
                }
            }
            case MODALITY -> {
                for (SpendReport.ModalityUsage r : byModality(fromDate, toDate)) {
                    writer.write(csv(r.modality()) + "," + metricsCsv(r.metrics()) + "\n");
                }
            }
        }
        writer.flush();
    }

    static String metricsCsv(SpendReport.UsageMetrics m) {
        return m.totalCalls() + ","
            + m.errorCount() + ","
            + round4(m.successRate()) + ","
            + m.promptTokens() + ","
            + m.completionTokens() + ","
            + m.totalTokens() + ","
            + m.messageVolume() + ","
            + m.distinctSessions() + ","
            + (m.estimatedCostUsd() == null ? "0" : m.estimatedCostUsd().toPlainString()) + ","
            + orEmpty(m.avgLatencyMs()) + ","
            + orEmpty(m.latencyP50Ms()) + ","
            + orEmpty(m.latencyP95Ms());
    }

    private static String orEmpty(Double v) {
        return v == null ? "" : String.valueOf(round4(v));
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * CSV escaping - quote and double-up embedded quotes if a value has
     * comma/quote/newline, and (SEC-046) prefix {@code '} to a cell starting
     * with {@code = + - @}, tab or CR so a spreadsheet does not evaluate it.
     * Model / provider names can be tenant-configured strings.
     */
    static String csv(String value) {
        return com.agenticai.core.security.CsvCells.escape(value);
    }

    // --- internals ----------------------------------------------------------

    private boolean includesToday(LocalDate toDate) {
        return !toDate.isBefore(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * Every grain in the range: persisted rollup rows for historical days
     * plus, if the range includes today, a single bounded live aggregation of
     * today's raw {@code llm_call_log}. Persisted rows for "today" (if a job
     * already wrote a partial one earlier) are skipped in favor of the fresh
     * live figure so the same day is never counted twice.
     */
    private List<Grain> loadGrains(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            return List.of();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Grain> grains = new ArrayList<>();

        for (LlmDailyUsageRollup r : rollupRepository.findByRange(fromDate, toDate)) {
            if (r.getUsageDate().isEqual(today)) {
                continue; // superseded by the live path below
            }
            grains.add(Grain.of(r));
        }

        if (!today.isBefore(fromDate) && !today.isAfter(toDate)) {
            Instant from = today.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (LlmCallLogRepository.UsageGrainRow row : callLogRepository.aggregateGrains(from, to)) {
                grains.add(Grain.ofLive(today, row));
            }
        }
        return grains;
    }

    private <K> Map<K, SpendReport.UsageMetrics> groupAndFold(List<Grain> grains, Function<Grain, K> keyFn) {
        Map<K, List<Grain>> grouped = new LinkedHashMap<>();
        for (Grain g : grains) {
            grouped.computeIfAbsent(keyFn.apply(g), k -> new ArrayList<>()).add(g);
        }
        Map<K, SpendReport.UsageMetrics> out = new LinkedHashMap<>();
        grouped.forEach((k, list) -> out.put(k, fold(list)));
        return out;
    }

    /** Folds a set of grains into one metric block - see the class javadoc on percentile handling. */
    static SpendReport.UsageMetrics fold(List<Grain> grains) {
        long calls = 0;
        long errors = 0;
        long prompt = 0;
        long completion = 0;
        long total = 0;
        long sessions = 0;
        long latencySum = 0;
        BigDecimal cost = BigDecimal.ZERO;
        double p50Weighted = 0;
        long p50Weight = 0;
        double p95Weighted = 0;
        long p95Weight = 0;

        for (Grain g : grains) {
            calls += g.totalCalls;
            errors += g.errorCount;
            prompt += g.promptTokens;
            completion += g.completionTokens;
            total += g.totalTokens;
            sessions += g.distinctSessions;
            latencySum += g.latencySumMs;
            if (g.estimatedCostUsd != null) {
                cost = cost.add(g.estimatedCostUsd);
            }
            if (g.latencyP50Ms != null && g.totalCalls > 0) {
                p50Weighted += g.latencyP50Ms * g.totalCalls;
                p50Weight += g.totalCalls;
            }
            if (g.latencyP95Ms != null && g.totalCalls > 0) {
                p95Weighted += g.latencyP95Ms * g.totalCalls;
                p95Weight += g.totalCalls;
            }
        }

        double successRate = calls > 0 ? (double) (calls - errors) / calls : 0.0;
        Double avgLatency = calls > 0 ? (double) latencySum / calls : null;
        Double p50 = p50Weight > 0 ? p50Weighted / p50Weight : null;
        Double p95 = p95Weight > 0 ? p95Weighted / p95Weight : null;

        return new SpendReport.UsageMetrics(
            calls, errors, successRate, prompt, completion, total,
            calls, sessions, cost, avgLatency, p50, p95,
            null // cacheHitRate - not tracked at call-log level
        );
    }

    /** Source-agnostic grain: a persisted rollup row or a live-aggregated today row. */
    record Grain(
        LocalDate usageDate,
        String providerName,
        String model,
        UUID agentId,
        String modality,
        long totalCalls,
        long errorCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        long latencySumMs,
        Double latencyP50Ms,
        Double latencyP95Ms,
        long distinctSessions
    ) {
        static Grain of(LlmDailyUsageRollup r) {
            return new Grain(
                r.getUsageDate(), r.getProviderName(), r.getModel(), r.getAgentId(), r.getModality(),
                r.getTotalCalls(), r.getErrorCount(), r.getPromptTokens(), r.getCompletionTokens(),
                r.getTotalTokens(), r.getEstimatedCostUsd(), r.getLatencySumMs(),
                r.getLatencyP50Ms(), r.getLatencyP95Ms(), r.getDistinctSessions()
            );
        }

        static Grain ofLive(LocalDate day, LlmCallLogRepository.UsageGrainRow row) {
            return new Grain(
                day, row.getProviderName(), row.getModel(), row.getAgentId(),
                ModalityClassifier.classify(row.getModel()),
                row.getTotalCalls(), row.getErrorCount(), row.getPromptTokens(), row.getCompletionTokens(),
                row.getTotalTokens(), row.getEstimatedCostUsd(), row.getLatencySumMs(),
                row.getLatencyP50Ms(), row.getLatencyP95Ms(), row.getDistinctSessions()
            );
        }
    }

    private static <T> Comparator<T> costDescending(Function<T, SpendReport.UsageMetrics> metrics) {
        return Comparator.comparing(
            (T t) -> {
                BigDecimal c = metrics.apply(t).estimatedCostUsd();
                return c == null ? BigDecimal.ZERO : c;
            }
        ).reversed();
    }
}
