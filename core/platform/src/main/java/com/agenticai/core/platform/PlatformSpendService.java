package com.agenticai.core.platform;

import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.chat.SpendReport;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Platform-wide spend reports for the operator console - the cross-tenant counterpart of the
 * tenant-scoped {@code SpendReportController}.
 *
 * <p><b>What "spend" means here - two figures, never blended:</b>
 *
 * <ul>
 *   <li><b>All-provider usage cost</b> - the {@code estimated_cost_usd} recorded on every LLM call,
 *       for every provider, INCLUDING tenants' own BYOK keys. This is an estimate of the work done,
 *       not money the platform paid.
 *   <li><b>Hosted-pool cost</b> - the same estimate restricted to {@link
 *       ProviderName#PLATFORM_HOSTED}: the calls the platform itself pays for (FREE-tier traffic).
 *       This is the platform's own outlay.
 * </ul>
 *
 * Both are estimates from stored per-call figures, not provider invoices.
 *
 * <p><b>How it aggregates (reuse, no new SQL for the main reports).</b> Each active tenant's data
 * lives in its own schema, and the operator's token is tenant-less (JPA resolves to the public
 * schema, which has no {@code llm_call_log}). So, exactly like {@link
 * PlatformObservabilityService}, this service iterates registered ACTIVE tenants and binds {@link
 * TenantContext} to each tenant's schema name in turn, then calls the existing per-tenant {@link
 * LlmUsageRollupService} (rollup table for finished days plus one bounded live aggregation of
 * today) and merges the results. One tenant failing is logged and skipped (reported as {@code
 * tenantsFailed}), never fatal. The caller's own context is restored afterwards. The only
 * repository call used directly is the existing {@code dailyCostForProvider} for the
 * hosted-pool-by-day series.
 *
 * <p><b>Which tenants (2026-10-09).</b> Registered tenants only: ACTIVE and NOT {@code is_demo}
 * ({@link TenantRepository#findActiveExcludingDemo}), in {@code created_at} order. Anonymous {@code
 * /try} sandboxes used to be swept too, which put landing-page traffic into "platform spend"; and
 * the scan list was unordered, so WHICH tenants fell past {@link #MAX_TENANTS} changed between
 * calls. Every report also takes an optional {@code tenantId} that restricts the sweep to that one
 * tenant ({@code meta.scope = "tenant"}, {@code meta.tenantId} set).
 *
 * <p><b>Hard caps:</b> date range at most {@link #MAX_RANGE_DAYS} days, at most {@link
 * #MAX_TENANTS} tenants scanned (oldest first; response flags {@code truncated}), model rows at
 * most {@link #MAX_MODEL_ROWS}.
 *
 * <p><b>Not provided:</b> latency percentiles (cannot be merged across tenants truthfully; only the
 * exact call-weighted mean is returned) and a hosted-only model breakdown (the rollup's model view
 * is not provider-filterable).
 */
@Service
@Profile("security")
public class PlatformSpendService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSpendService.class);
    private static final String SYSTEM_ACTOR = "system:platform-spend";
    private static final String HOSTED = ProviderName.PLATFORM_HOSTED.name();

    static final int MAX_RANGE_DAYS = 92;
    static final int DEFAULT_RANGE_DAYS = 30;
    static final int MAX_TENANTS = 500;
    static final int MAX_MODEL_ROWS = 200;

    public static final String SCOPE_NOTE =
            "allProviders = estimated cost of every recorded LLM call incl. tenants' own BYOK keys"
                + " (usage, not platform outlay). hostedPool = PLATFORM_HOSTED provider only = what"
                + " the platform itself pays for. Estimates from per-call stored figures, not"
                + " invoices.";

    private final TenantRepository tenantRepository;
    private final LlmUsageRollupService rollupService;
    private final LlmCallLogRepository callLogRepository;

    public PlatformSpendService(
            TenantRepository tenantRepository,
            LlmUsageRollupService rollupService,
            LlmCallLogRepository callLogRepository) {
        this.tenantRepository = tenantRepository;
        this.rollupService = rollupService;
        this.callLogRepository = callLogRepository;
    }

    // ------------------------------------------------------------------ range

    public record Range(LocalDate from, LocalDate to) {}

    /**
     * Defaults to the trailing 30 UTC days; rejects inverted or over-long ranges (never silently
     * clamps).
     */
    public static Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must not be after 'to'.");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new IllegalArgumentException(
                    "Date range is limited to " + MAX_RANGE_DAYS + " days.");
        }
        return new Range(start, end);
    }

    // ----------------------------------------------------------------- shapes

    /**
     * Mergeable metrics. Latency is the exact call-weighted mean; percentiles are intentionally
     * absent.
     */
    public record Metrics(
            long totalCalls,
            long errorCount,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            BigDecimal estimatedCostUsd,
            Double avgLatencyMs) {}

    /**
     * {@code scope} is {@code "platform-wide"} or {@code "tenant"}; {@code tenantId} is set only
     * for the latter.
     */
    public record Meta(
            String scope,
            LocalDate from,
            LocalDate to,
            int tenantsTotal,
            int tenantsScanned,
            int tenantsFailed,
            boolean truncated,
            boolean includesLiveToday,
            UUID tenantId) {}

    /**
     * One tenant's spend over a range - the platform roster's spend columns. Same rollup source as
     * {@link #byTenant}; built for "many tenants, three numbers each".
     */
    public record TenantSpendTotals(
            BigDecimal allProvidersUsd, BigDecimal hostedPoolUsd, long totalCalls) {}

    public record SummaryReport(Meta meta, Metrics allProviders, Metrics hostedPool) {}

    public record DayRow(
            LocalDate date, Metrics allProviders, long hostedCalls, BigDecimal hostedCostUsd) {}

    public record DayReport(Meta meta, List<DayRow> rows) {}

    public record TenantRow(
            UUID tenantId, String tenantName, Metrics allProviders, Metrics hostedPool) {}

    public record TenantReport(Meta meta, List<TenantRow> rows) {}

    public record ModelRow(String model, String modality, Metrics allProviders) {}

    /** {@code totalRows} is the model count before the {@link #MAX_MODEL_ROWS} cap. */
    public record ModelReport(Meta meta, int totalRows, List<ModelRow> rows) {}

    public record ProviderRow(String provider, boolean hostedPool, Metrics metrics) {}

    public record ProviderReport(Meta meta, List<ProviderRow> rows) {}

    // ---------------------------------------------------------------- reports

    public SummaryReport summary(Range range) {
        return summary(range, null);
    }

    public SummaryReport summary(Range range, UUID tenantId) {
        Sweep<List<SpendReport.ProviderUsage>> s =
                sweep(tenantId, t -> rollupService.byProvider(range.from(), range.to()));
        Acc all = new Acc();
        Acc hosted = new Acc();
        for (Result<List<SpendReport.ProviderUsage>> r : s.results()) {
            for (SpendReport.ProviderUsage p : r.value()) {
                all.add(p.metrics());
                if (HOSTED.equals(p.providerName())) {
                    hosted.add(p.metrics());
                }
            }
        }
        return new SummaryReport(meta(s, range), all.toMetrics(), hosted.toMetrics());
    }

    public TenantReport byTenant(Range range) {
        return byTenant(range, null);
    }

    public TenantReport byTenant(Range range, UUID tenantId) {
        Sweep<List<SpendReport.ProviderUsage>> s =
                sweep(tenantId, t -> rollupService.byProvider(range.from(), range.to()));
        List<TenantRow> rows = new ArrayList<>();
        for (Result<List<SpendReport.ProviderUsage>> r : s.results()) {
            Acc all = new Acc();
            Acc hosted = new Acc();
            for (SpendReport.ProviderUsage p : r.value()) {
                all.add(p.metrics());
                if (HOSTED.equals(p.providerName())) {
                    hosted.add(p.metrics());
                }
            }
            if (all.calls == 0) {
                continue;
            }
            rows.add(
                    new TenantRow(
                            r.tenant().getId(),
                            r.tenant().getName(),
                            all.toMetrics(),
                            hosted.toMetrics()));
        }
        rows.sort(
                Comparator.comparing((TenantRow r) -> r.allProviders().estimatedCostUsd())
                        .reversed());
        return new TenantReport(meta(s, range), rows);
    }

    public ProviderReport byProvider(Range range) {
        return byProvider(range, null);
    }

    public ProviderReport byProvider(Range range, UUID tenantId) {
        Sweep<List<SpendReport.ProviderUsage>> s =
                sweep(tenantId, t -> rollupService.byProvider(range.from(), range.to()));
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (Result<List<SpendReport.ProviderUsage>> r : s.results()) {
            for (SpendReport.ProviderUsage p : r.value()) {
                merged.computeIfAbsent(p.providerName(), k -> new Acc()).add(p.metrics());
            }
        }
        List<ProviderRow> rows = new ArrayList<>();
        merged.forEach(
                (name, acc) ->
                        rows.add(new ProviderRow(name, HOSTED.equals(name), acc.toMetrics())));
        rows.sort(
                Comparator.comparing((ProviderRow r) -> r.metrics().estimatedCostUsd()).reversed());
        return new ProviderReport(meta(s, range), rows);
    }

    public ModelReport byModel(Range range) {
        return byModel(range, null);
    }

    public ModelReport byModel(Range range, UUID tenantId) {
        Sweep<List<SpendReport.ModelUsage>> s =
                sweep(tenantId, t -> rollupService.byModel(range.from(), range.to()));
        Map<String, Acc> merged = new HashMap<>();
        Map<String, String> modality = new HashMap<>();
        for (Result<List<SpendReport.ModelUsage>> r : s.results()) {
            for (SpendReport.ModelUsage m : r.value()) {
                merged.computeIfAbsent(m.model(), k -> new Acc()).add(m.metrics());
                modality.putIfAbsent(m.model(), m.modality());
            }
        }
        List<ModelRow> rows = new ArrayList<>();
        merged.forEach(
                (model, acc) ->
                        rows.add(new ModelRow(model, modality.get(model), acc.toMetrics())));
        rows.sort(
                Comparator.comparing((ModelRow r) -> r.allProviders().estimatedCostUsd())
                        .reversed());
        int total = rows.size();
        return new ModelReport(
                meta(s, range),
                total,
                rows.size() > MAX_MODEL_ROWS ? rows.subList(0, MAX_MODEL_ROWS) : rows);
    }

    public DayReport byDay(Range range) {
        return byDay(range, null);
    }

    public DayReport byDay(Range range, UUID tenantId) {
        java.time.Instant hostedFrom = range.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        record DayWork(
                List<SpendReport.DayUsage> days,
                List<LlmCallLogRepository.DailyProviderCostRow> hosted) {}
        Sweep<DayWork> s =
                sweep(
                        tenantId,
                        t ->
                                new DayWork(
                                        rollupService.byDay(range.from(), range.to()),
                                        callLogRepository.dailyCostForProvider(
                                                HOSTED, hostedFrom)));
        Map<LocalDate, Acc> all = new TreeMap<>();
        Map<LocalDate, long[]> hostedCalls = new HashMap<>();
        Map<LocalDate, BigDecimal> hostedCost = new HashMap<>();
        for (Result<DayWork> r : s.results()) {
            for (SpendReport.DayUsage d : r.value().days()) {
                all.computeIfAbsent(d.date(), k -> new Acc()).add(d.metrics());
            }
            for (LlmCallLogRepository.DailyProviderCostRow h : r.value().hosted()) {
                LocalDate day = h.getUsageDate();
                if (day.isAfter(range.to())) {
                    continue; // repository query has no upper bound
                }
                hostedCalls.computeIfAbsent(day, k -> new long[1])[0] += h.getTotalCalls();
                hostedCost.merge(
                        day,
                        h.getEstimatedCostUsd() == null ? BigDecimal.ZERO : h.getEstimatedCostUsd(),
                        BigDecimal::add);
            }
        }
        List<DayRow> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Acc> e : all.entrySet()) {
            LocalDate day = e.getKey();
            rows.add(
                    new DayRow(
                            day,
                            e.getValue().toMetrics(),
                            hostedCalls.getOrDefault(day, new long[1])[0],
                            hostedCost.getOrDefault(day, BigDecimal.ZERO)));
        }
        return new DayReport(meta(s, range), rows);
    }

    // -------------------------------------------------------------------- csv

    public static final Set<String> GROUPS = Set.of("tenant", "day", "model", "provider");

    /**
     * One CSV for the chosen breakdown; scope columns are explicit so the two cost figures cannot
     * be confused.
     */
    public String exportCsv(String groupBy, Range range) {
        return exportCsv(groupBy, range, null);
    }

    public String exportCsv(String groupBy, Range range, UUID tenantId) {
        String metricHeader =
                "total_calls,error_count,prompt_tokens,completion_tokens,total_tokens,"
                        + "all_providers_estimated_cost_usd,avg_latency_ms";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(SCOPE_NOTE).append('\n');
        sb.append("# range ")
                .append(range.from())
                .append(" to ")
                .append(range.to())
                .append(" (UTC days)\n");
        if (tenantId != null) {
            sb.append("# tenant ").append(tenantId).append('\n');
        }
        switch (groupBy) {
            case "tenant" -> {
                sb.append("tenant_id,tenant_name,")
                        .append(metricHeader)
                        .append(",hosted_pool_calls,hosted_pool_estimated_cost_usd\n");
                for (TenantRow r : byTenant(range, tenantId).rows()) {
                    sb.append(cell(String.valueOf(r.tenantId())))
                            .append(',')
                            .append(cell(r.tenantName()))
                            .append(',')
                            .append(metricsCsv(r.allProviders()))
                            .append(',')
                            .append(r.hostedPool().totalCalls())
                            .append(',')
                            .append(plain(r.hostedPool().estimatedCostUsd()))
                            .append('\n');
                }
            }
            case "day" -> {
                sb.append("date,")
                        .append(metricHeader)
                        .append(",hosted_pool_calls,hosted_pool_estimated_cost_usd\n");
                for (DayRow r : byDay(range, tenantId).rows()) {
                    sb.append(r.date())
                            .append(',')
                            .append(metricsCsv(r.allProviders()))
                            .append(',')
                            .append(r.hostedCalls())
                            .append(',')
                            .append(plain(r.hostedCostUsd()))
                            .append('\n');
                }
            }
            case "model" -> {
                sb.append("model,modality,").append(metricHeader).append('\n');
                for (ModelRow r : byModel(range, tenantId).rows()) {
                    sb.append(cell(r.model()))
                            .append(',')
                            .append(cell(r.modality()))
                            .append(',')
                            .append(metricsCsv(r.allProviders()))
                            .append('\n');
                }
            }
            case "provider" -> {
                sb.append("provider,is_hosted_pool,").append(metricHeader).append('\n');
                for (ProviderRow r : byProvider(range, tenantId).rows()) {
                    sb.append(cell(r.provider()))
                            .append(',')
                            .append(r.hostedPool())
                            .append(',')
                            .append(metricsCsv(r.metrics()))
                            .append('\n');
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            "groupBy must be one of: tenant, day, model, provider");
        }
        return sb.toString();
    }

    private static String metricsCsv(Metrics m) {
        return m.totalCalls()
                + ","
                + m.errorCount()
                + ","
                + m.promptTokens()
                + ","
                + m.completionTokens()
                + ","
                + m.totalTokens()
                + ","
                + plain(m.estimatedCostUsd())
                + ","
                + (m.avgLatencyMs() == null
                        ? ""
                        : String.valueOf(Math.round(m.avgLatencyMs() * 100.0) / 100.0));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }

    /**
     * CSV-safe cell for free-text values (tenant names and model ids are user-influenced): quotes
     * when needed and neutralises spreadsheet formula injection by prefixing a leading {@code = +
     * - @ tab CR} with a single quote.
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // -------------------------------------------------------------- internals

    private record Result<T>(Tenant tenant, T value) {}

    private record Sweep<T>(
            List<Result<T>> results,
            int total,
            int scanned,
            int failed,
            boolean truncated,
            UUID tenantId) {}

    private Meta meta(Sweep<?> s, Range range) {
        return new Meta(
                s.tenantId() == null ? "platform-wide" : "tenant",
                range.from(),
                range.to(),
                s.total(),
                s.scanned(),
                s.failed(),
                s.truncated(),
                !range.to().isBefore(LocalDate.now(ZoneOffset.UTC)),
                s.tenantId());
    }

    /**
     * Per-tenant spend over {@code range} for exactly the given tenants (the platform roster's
     * ACTIVE rows), from the same rollup-plus-live-today source as the reports. A tenant whose
     * lookup failed is ABSENT from the map - the caller renders that as "unknown", never as zero.
     */
    public Map<UUID, TenantSpendTotals> spendForTenants(List<Tenant> tenants, Range range) {
        Sweep<List<SpendReport.ProviderUsage>> s =
                sweepTenants(
                        tenants,
                        null,
                        Integer.MAX_VALUE,
                        t -> rollupService.byProvider(range.from(), range.to()));
        Map<UUID, TenantSpendTotals> out = new HashMap<>();
        for (Result<List<SpendReport.ProviderUsage>> r : s.results()) {
            Acc all = new Acc();
            Acc hosted = new Acc();
            for (SpendReport.ProviderUsage p : r.value()) {
                all.add(p.metrics());
                if (HOSTED.equals(p.providerName())) {
                    hosted.add(p.metrics());
                }
            }
            out.put(r.tenant().getId(), new TenantSpendTotals(all.cost, hosted.cost, all.calls));
        }
        return out;
    }

    /**
     * The tenants a report covers: one named tenant (any status - the operator asked for it
     * explicitly), or every registered ACTIVE tenant oldest-first.
     */
    private <T> Sweep<T> sweep(UUID tenantId, Function<Tenant, T> work) {
        if (tenantId != null) {
            Tenant tenant =
                    tenantRepository
                            .findById(tenantId)
                            .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
            return sweepTenants(List.of(tenant), tenantId, MAX_TENANTS, work);
        }
        return sweepTenants(tenantRepository.findActiveExcludingDemo(), null, MAX_TENANTS, work);
    }

    /**
     * Runs {@code work} once per tenant with that tenant's schema bound, capped at {@code cap} (in
     * list order - callers pass an ordered list, so the cut is stable). A failing tenant is counted
     * and skipped. Restores the caller's context (the operator's) afterwards rather than clearing
     * it.
     */
    private <T> Sweep<T> sweepTenants(
            List<Tenant> tenants, UUID tenantId, int cap, Function<Tenant, T> work) {
        boolean truncated = tenants.size() > cap;
        List<Tenant> scope = truncated ? tenants.subList(0, cap) : tenants;
        Optional<TenantContext> previous = TenantContext.current();
        List<Result<T>> results = new ArrayList<>();
        int failed = 0;
        try {
            for (Tenant tenant : scope) {
                try {
                    TenantContext.set(
                            TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                    results.add(new Result<>(tenant, work.apply(tenant)));
                } catch (RuntimeException e) {
                    failed++;
                    log.warn(
                            "Spend report failed for tenant schema '{}'",
                            tenant.getSchemaName(),
                            e);
                }
            }
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
        return new Sweep<>(results, tenants.size(), scope.size(), failed, truncated, tenantId);
    }

    /**
     * Mutable accumulator; avg latency merged as Σ(avg × calls) / Σ calls (exact, since each avg is
     * exact).
     */
    private static final class Acc {
        long calls;
        long errors;
        long prompt;
        long completion;
        long total;
        BigDecimal cost = BigDecimal.ZERO;
        double latencyWeighted;
        long latencyWeight;

        void add(SpendReport.UsageMetrics m) {
            calls += m.totalCalls();
            errors += m.errorCount();
            prompt += m.promptTokens();
            completion += m.completionTokens();
            total += m.totalTokens();
            if (m.estimatedCostUsd() != null) {
                cost = cost.add(m.estimatedCostUsd());
            }
            if (m.avgLatencyMs() != null && m.totalCalls() > 0) {
                latencyWeighted += m.avgLatencyMs() * m.totalCalls();
                latencyWeight += m.totalCalls();
            }
        }

        Metrics toMetrics() {
            return new Metrics(
                    calls,
                    errors,
                    prompt,
                    completion,
                    total,
                    cost,
                    latencyWeight == 0 ? null : latencyWeighted / latencyWeight);
        }
    }
}
