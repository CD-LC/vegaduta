package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.ops.LangfuseApiClient.LangfuseUnavailableException;
import com.agenticai.core.platform.ops.PlatformTraceDtos.ObservationRow;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceDayMetric;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceDetail;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceDetailResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceListMeta;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceListResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceMetricsResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceModelMetric;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceRow;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Maps Langfuse's public API onto the platform console's trace contract (see {@link
 * PlatformTraceDtos}). Every failure to reach Langfuse becomes {@code available=false} plus a
 * reason - never a 5xx - because "Langfuse is not deployed here" is a normal state for some
 * environments.
 *
 * <p><b>Tenant attribution.</b> {@code LlmMetricsAdvisor} (core/chat) puts the tenant SCHEMA name
 * on every LLM span as {@code langfuse.trace.metadata.tenantId} (Langfuse lifts it into trace
 * {@code metadata.tenantId}) and also as the raw span attribute {@code vegaduta.tenant.id}.
 * Langfuse's trace-list API has no metadata filter that works across the versions this platform may
 * run, so a {@code tenantId} filter is applied HERE, over the most recent {@link
 * #TENANT_SCAN_PAGES} x {@link #TENANT_SCAN_PAGE_SIZE} traces matching the other filters, and the
 * response says so in {@code reason}. The filter accepts the tenant's UUID (what the roster links
 * with) or its schema name.
 *
 * <p><b>Fields Langfuse does not provide at trace level.</b> Token counts and a level exist only on
 * observations, so list rows carry {@code null} for them (never 0); the detail view sums/derives
 * them from the observations.
 */
@Service
public class PlatformTraceService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTraceService.class);

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int TENANT_SCAN_PAGES = 5;
    static final int TENANT_SCAN_PAGE_SIZE = 100;
    static final int IO_MAX_CHARS = 4000;
    static final Duration DEFAULT_METRICS_RANGE = Duration.ofDays(14);
    static final Duration MAX_METRICS_RANGE = Duration.ofDays(92);
    private static final Pattern TRACE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final LangfuseApiClient langfuse;
    private final ObjectProvider<TenantRepository> tenantRepository;
    private final Clock clock;

    @Autowired
    public PlatformTraceService(
            LangfuseApiClient langfuse, ObjectProvider<TenantRepository> tenantRepository) {
        this(langfuse, tenantRepository, Clock.systemUTC());
    }

    PlatformTraceService(
            LangfuseApiClient langfuse,
            ObjectProvider<TenantRepository> tenantRepository,
            Clock clock) {
        this.langfuse = langfuse;
        this.tenantRepository = tenantRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ list

    public TraceListResponse list(
            String from,
            String to,
            String tenantId,
            String userId,
            String name,
            Integer page,
            Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        if (!langfuse.isConfigured()) {
            return TraceListResponse.unavailable(
                    langfuse.notConfiguredReason(), safePage, safeLimit);
        }
        Instant fromTs = parseInstant(from, "from", false);
        Instant toTs = parseInstant(to, "to", true);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("fromTimestamp", fromTs == null ? null : fromTs.toString());
        query.put("toTimestamp", toTs == null ? null : toTs.toString());
        query.put("userId", blankToNull(userId));
        query.put("name", blankToNull(name));
        query.put("orderBy", "timestamp.desc");
        query.put("fields", "core,io,metrics");
        try {
            if (tenantId == null || tenantId.isBlank()) {
                query.put("page", String.valueOf(safePage));
                query.put("limit", String.valueOf(safeLimit));
                JsonNode body = langfuse.get("/api/public/traces", query);
                List<TraceRow> rows = new ArrayList<>();
                for (JsonNode t : body.path("data")) {
                    rows.add(row(t));
                }
                JsonNode meta = body.path("meta");
                return new TraceListResponse(
                        true,
                        null,
                        rows,
                        new TraceListMeta(
                                meta.path("page").asInt(safePage),
                                        meta.path("limit").asInt(safeLimit),
                                meta.path("totalItems").asLong(rows.size()),
                                        meta.path("totalPages").asInt(rows.isEmpty() ? 0 : 1)));
            }
            return listForTenant(query, tenantId.trim(), safePage, safeLimit);
        } catch (LangfuseUnavailableException e) {
            return TraceListResponse.unavailable(e.getMessage(), safePage, safeLimit);
        }
    }

    /**
     * Client-side tenant filter over a bounded window of the newest matching traces (see class
     * javadoc).
     */
    private TraceListResponse listForTenant(
            Map<String, String> query, String tenantId, int page, int limit) {
        Set<String> wanted = tenantKeys(tenantId);
        List<TraceRow> matched = new ArrayList<>();
        int scanned = 0;
        boolean more = false;
        for (int p = 1; p <= TENANT_SCAN_PAGES; p++) {
            Map<String, String> q = new LinkedHashMap<>(query);
            q.put("page", String.valueOf(p));
            q.put("limit", String.valueOf(TENANT_SCAN_PAGE_SIZE));
            JsonNode body = langfuse.get("/api/public/traces", q);
            int count = 0;
            for (JsonNode t : body.path("data")) {
                count++;
                TraceRow r = row(t);
                if (r.tenantId() != null && wanted.contains(r.tenantId())) {
                    matched.add(r);
                }
            }
            scanned += count;
            int totalPages = body.path("meta").path("totalPages").asInt(0);
            more = p < totalPages;
            if (!more || count < TENANT_SCAN_PAGE_SIZE) {
                more = more && count >= TENANT_SCAN_PAGE_SIZE;
                break;
            }
        }
        int fromIndex = Math.min((page - 1) * limit, matched.size());
        int toIndex = Math.min(fromIndex + limit, matched.size());
        int totalPages = matched.isEmpty() ? 0 : (matched.size() + limit - 1) / limit;
        String reason =
                "Langfuse cannot filter traces by tenant server-side; this tenant was matched against the "
                        + scanned
                        + " most recent trace(s) for the other filters"
                        + (more
                                ? " (older traces were not scanned - narrow the time range to see them)."
                                : ".");
        return new TraceListResponse(
                true,
                reason,
                List.copyOf(matched.subList(fromIndex, toIndex)),
                new TraceListMeta(page, limit, matched.size(), totalPages));
    }

    /**
     * The schema name a trace carries, plus the raw filter value (a UUID is resolved to its
     * schema).
     */
    Set<String> tenantKeys(String tenantId) {
        Set<String> keys = new HashSet<>();
        keys.add(tenantId);
        try {
            UUID id = UUID.fromString(tenantId);
            TenantRepository repo = tenantRepository.getIfAvailable();
            if (repo != null) {
                repo.findById(id).map(Tenant::getSchemaName).ifPresent(keys::add);
            }
        } catch (IllegalArgumentException notAUuid) {
            // already a schema name
        } catch (RuntimeException e) {
            log.warn(
                    "Trace tenant filter: tenant lookup failed ({})", e.getClass().getSimpleName());
        }
        return keys;
    }

    // ---------------------------------------------------------------- detail

    public TraceDetailResponse detail(String id) {
        if (id == null || !TRACE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a valid trace id.");
        }
        if (!langfuse.isConfigured()) {
            return TraceDetailResponse.unavailable(langfuse.notConfiguredReason());
        }
        JsonNode t;
        try {
            t = langfuse.get("/api/public/traces/" + LangfuseApiClient.pathSegment(id), Map.of());
        } catch (LangfuseUnavailableException e) {
            if (e.status() == 404) {
                return new TraceDetailResponse(
                        true, "Trace not found in Langfuse.", null, List.of());
            }
            return TraceDetailResponse.unavailable(e.getMessage());
        }
        List<ObservationRow> observations = new ArrayList<>();
        JsonNode obs = t.path("observations");
        boolean objects = obs.isArray() && !obs.isEmpty() && obs.get(0).isObject();
        if (objects) {
            for (JsonNode o : obs) {
                observations.add(observation(o));
            }
        } else if (obs.isArray() && !obs.isEmpty()) {
            // Older Langfuse versions return observation ids only - fetch them by trace.
            try {
                Map<String, String> q = new LinkedHashMap<>();
                q.put("traceId", id);
                q.put("limit", "100");
                for (JsonNode o : langfuse.get("/api/public/observations", q).path("data")) {
                    observations.add(observation(o));
                }
            } catch (LangfuseUnavailableException e) {
                log.debug("Trace detail: observations lookup failed: {}", e.getMessage());
            }
        }
        observations.sort((a, b) -> compareNullable(a.startTime(), b.startTime()));
        TraceRow base = row(t);
        Long in = sum(observations.stream().map(ObservationRow::inputTokens).toList());
        Long out = sum(observations.stream().map(ObservationRow::outputTokens).toList());
        String level = worstLevel(observations.stream().map(ObservationRow::level).toList());
        TraceDetail detail =
                new TraceDetail(
                        base.id(),
                        base.name(),
                        base.timestamp(),
                        base.userId(),
                        base.sessionId(),
                        base.tenantId(),
                        base.latencyMs(),
                        base.totalCostUsd(),
                        in,
                        out,
                        level,
                        base.tags(),
                        truncate(text(t.path("input"))),
                        truncate(text(t.path("output"))));
        return new TraceDetailResponse(true, null, detail, List.copyOf(observations));
    }

    // --------------------------------------------------------------- metrics

    public TraceMetricsResponse metrics(String from, String to) {
        if (!langfuse.isConfigured()) {
            return TraceMetricsResponse.unavailable(langfuse.notConfiguredReason());
        }
        Instant toTs = parseInstant(to, "to", true);
        Instant fromTs = parseInstant(from, "from", false);
        Instant end = toTs == null ? clock.instant() : toTs;
        Instant start = fromTs == null ? end.minus(DEFAULT_METRICS_RANGE) : fromTs;
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        if (Duration.between(start, end).compareTo(MAX_METRICS_RANGE) > 0) {
            throw new IllegalArgumentException(
                    "The metrics range can be at most " + MAX_METRICS_RANGE.toDays() + " days.");
        }
        JsonNode daily;
        try {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("fromTimestamp", start.toString());
            q.put("toTimestamp", end.toString());
            q.put("limit", "100");
            daily = langfuse.get("/api/public/metrics/daily", q);
        } catch (LangfuseUnavailableException e) {
            return TraceMetricsResponse.unavailable(e.getMessage());
        }
        Map<String, Long[]> latency = latencyPercentiles(start, end);
        List<TraceDayMetric> byDay = new ArrayList<>();
        Map<String, double[]> models = new LinkedHashMap<>();
        Map<String, boolean[]> modelHasCost = new LinkedHashMap<>();
        for (JsonNode d : daily.path("data")) {
            String date = d.path("date").asString("");
            String day = date.length() >= 10 ? date.substring(0, 10) : date;
            Long[] lat = latency.get(day);
            byDay.add(
                    new TraceDayMetric(
                            day,
                            longOrNull(d.path("countTraces")),
                            doubleOrNull(d.path("totalCost")),
                            lat == null ? null : lat[0],
                            lat == null ? null : lat[1]));
            for (JsonNode u : d.path("usage")) {
                String model = u.path("model").asString(null);
                if (model == null || model.isBlank()) {
                    model = "(unknown)";
                }
                double[] acc = models.computeIfAbsent(model, k -> new double[4]);
                acc[0] += u.path("countObservations").asDouble(0);
                acc[1] += u.path("totalCost").asDouble(0);
                acc[2] += u.path("inputUsage").asDouble(0);
                acc[3] += u.path("outputUsage").asDouble(0);
                if (u.hasNonNull("totalCost")) {
                    modelHasCost.computeIfAbsent(model, k -> new boolean[1])[0] = true;
                }
            }
        }
        byDay.sort((a, b) -> a.date().compareTo(b.date()));
        List<TraceModelMetric> byModel = new ArrayList<>();
        models.forEach(
                (model, acc) ->
                        byModel.add(
                                new TraceModelMetric(
                                        model,
                                        (long) acc[0],
                                        modelHasCost.containsKey(model) ? acc[1] : null,
                                        (long) acc[2],
                                        (long) acc[3])));
        byModel.sort((a, b) -> Double.compare(nz(b.costUsd()), nz(a.costUsd())));
        return new TraceMetricsResponse(true, null, byDay, byModel);
    }

    /**
     * Daily p50/p95 trace latency (ms) from Langfuse's v2 metrics query API, keyed by yyyy-MM-dd.
     * Best effort: older Langfuse versions do not have this API, in which case the latency columns
     * are simply null and the rest of the metrics still render.
     */
    Map<String, Long[]> latencyPercentiles(Instant start, Instant end) {
        Map<String, Long[]> out = new LinkedHashMap<>();
        String query =
                "{\"view\":\"traces\",\"dimensions\":[],\"filters\":[],"
                        + "\"metrics\":[{\"measure\":\"latency\",\"aggregation\":\"p50\"},{\"measure\":\"latency\",\"aggregation\":\"p95\"}],"
                        + "\"timeDimension\":{\"granularity\":\"day\"},"
                        + "\"fromTimestamp\":\""
                        + start
                        + "\",\"toTimestamp\":\""
                        + end
                        + "\"}";
        try {
            JsonNode body = langfuse.get("/api/public/metrics", Map.of("query", query));
            for (JsonNode r : body.path("data")) {
                String t = r.path("time_dimension").asString("");
                if (t.length() < 10) {
                    continue;
                }
                out.put(
                        t.substring(0, 10),
                        new Long[] {
                            roundOrNull(r.path("p50_latency")), roundOrNull(r.path("p95_latency"))
                        });
            }
        } catch (LangfuseUnavailableException e) {
            log.debug("Trace metrics: latency percentiles unavailable: {}", e.getMessage());
        }
        return out;
    }

    // --------------------------------------------------------------- mapping

    static TraceRow row(JsonNode t) {
        JsonNode metadata = t.path("metadata");
        String tenant = textOrNull(metadata.path("tenantId"));
        if (tenant == null) {
            tenant = textOrNull(metadata.path("attributes").path("vegaduta.tenant.id"));
        }
        List<String> tags = null;
        if (t.path("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode tag : t.path("tags")) {
                tags.add(tag.asString());
            }
        }
        JsonNode latency = t.path("latency");
        Long latencyMs = latency.isNumber() ? Math.round(latency.asDouble() * 1000d) : null;
        return new TraceRow(
                t.path("id").asString(),
                textOrNull(t.path("name")),
                textOrNull(t.path("timestamp")),
                textOrNull(t.path("userId")),
                textOrNull(t.path("sessionId")),
                tenant,
                latencyMs,
                doubleOrNull(t.path("totalCost")),
                null,
                null,
                null,
                tags);
    }

    static ObservationRow observation(JsonNode o) {
        Long in = longOrNull(o.path("usageDetails").path("input"));
        if (in == null) {
            in = longOrNull(o.path("usage").path("input"));
        }
        if (in == null) {
            in = longOrNull(o.path("promptTokens"));
        }
        Long out = longOrNull(o.path("usageDetails").path("output"));
        if (out == null) {
            out = longOrNull(o.path("usage").path("output"));
        }
        if (out == null) {
            out = longOrNull(o.path("completionTokens"));
        }
        Double cost = doubleOrNull(o.path("calculatedTotalCost"));
        if (cost == null) {
            cost = doubleOrNull(o.path("costDetails").path("total"));
        }
        if (cost == null) {
            cost = doubleOrNull(o.path("totalPrice"));
        }
        return new ObservationRow(
                o.path("id").asString(),
                textOrNull(o.path("type")),
                textOrNull(o.path("name")),
                textOrNull(o.path("startTime")),
                textOrNull(o.path("endTime")),
                textOrNull(o.path("model")),
                in,
                out,
                cost,
                textOrNull(o.path("level")),
                truncate(textOrNull(o.path("statusMessage"))));
    }

    /** ERROR > WARNING > DEFAULT > DEBUG; null when no observation has a level. */
    static String worstLevel(List<String> levels) {
        List<String> order = List.of("DEBUG", "DEFAULT", "WARNING", "ERROR");
        String worst = null;
        for (String l : levels) {
            if (l == null) {
                continue;
            }
            if (worst == null || order.indexOf(l) > order.indexOf(worst)) {
                worst = l;
            }
        }
        return worst;
    }

    static String truncate(String s) {
        if (s == null || s.length() <= IO_MAX_CHARS) {
            return s;
        }
        return s.substring(0, IO_MAX_CHARS);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isString() ? node.asString() : node.toString();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.isString() ? node.asString() : node.toString();
        return s.isBlank() ? null : s;
    }

    private static Long longOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static Long roundOrNull(JsonNode node) {
        return node != null && node.isNumber() ? Math.round(node.asDouble()) : null;
    }

    private static Double doubleOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static Long sum(List<Long> values) {
        Long total = null;
        for (Long v : values) {
            if (v != null) {
                total = (total == null ? 0L : total) + v;
            }
        }
        return total;
    }

    private static double nz(Double d) {
        return d == null ? -1d : d;
    }

    private static int compareNullable(String a, String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareTo(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * ISO instant, or a yyyy-MM-dd date (start of day UTC; with {@code endOfDay}, the start of the
     * NEXT day).
     */
    static Instant parseInstant(String raw, String field, boolean endOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notInstant) {
            try {
                LocalDate date = LocalDate.parse(value);
                return (endOfDay ? date.plusDays(1) : date)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            } catch (DateTimeParseException notDate) {
                throw new IllegalArgumentException(
                        field + " must be an ISO-8601 instant or a yyyy-MM-dd date.");
            }
        }
    }
}
