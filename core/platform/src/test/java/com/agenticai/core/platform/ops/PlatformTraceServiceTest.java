package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceDetailResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceListResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceMetricsResponse;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PlatformTraceServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    private final List<URI> calls = new ArrayList<>();

    private PlatformTraceService service(Function<URI, LangfuseApiClient.Response> answer, TenantRepository repo) {
        LangfuseApiClient client = new LangfuseApiClient("http://lf:3000", "Basic x", (uri, auth) -> {
            calls.add(uri);
            return answer.apply(uri);
        }, CLOCK);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (repo != null) {
            factory.addBean("tenantRepository", repo);
        }
        return new PlatformTraceService(client, factory.getBeanProvider(TenantRepository.class), CLOCK);
    }

    private static LangfuseApiClient.Response ok(String body) {
        return new LangfuseApiClient.Response(200, body);
    }

    private static String trace(String id, String tenant) {
        return "{\"id\":\"" + id + "\",\"name\":\"chat\",\"timestamp\":\"2026-10-09T11:00:00.000Z\",\"userId\":\"u1\","
            + "\"sessionId\":\"s1\",\"latency\":1.234,\"totalCost\":0.0021,\"tags\":[\"provider:OPENAI\"],"
            + "\"metadata\":{\"tenantId\":\"" + tenant + "\"},\"input\":\"hi\",\"output\":\"hello\"}";
    }

    @Test
    void notConfiguredIsAStateNotAnError() {
        PlatformTraceService svc = new PlatformTraceService(
            new LangfuseApiClient(null, "", (u, a) -> null, CLOCK),
            new StaticListableBeanFactory().getBeanProvider(TenantRepository.class), CLOCK);
        TraceListResponse list = svc.list(null, null, null, null, null, null, null);
        assertFalse(list.available());
        assertNotNull(list.reason());
        assertEquals(50, list.meta().limit());
        assertFalse(svc.metrics(null, null).available());
        assertFalse(svc.detail("abc").available());
    }

    @Test
    void listMapsLangfuseRowsAndPassesFilters() {
        PlatformTraceService svc = service(u -> ok("{\"data\":[" + trace("t1", "acme") + "],"
            + "\"meta\":{\"page\":2,\"limit\":10,\"totalItems\":11,\"totalPages\":2}}"), null);

        TraceListResponse r = svc.list("2026-10-01", "2026-10-08", null, "u1", "chat", 2, 10);

        assertTrue(r.available());
        assertNull(r.reason());
        assertEquals(1, r.data().size());
        var row = r.data().get(0);
        assertEquals("t1", row.id());
        assertEquals("acme", row.tenantId());
        assertEquals(1234L, row.latencyMs());
        assertEquals(0.0021, row.totalCostUsd());
        assertNull(row.inputTokens()); // not provided at trace level - never 0
        assertEquals(List.of("provider:OPENAI"), row.tags());
        assertEquals(11, r.meta().totalItems());
        String q = calls.get(0).getRawQuery();
        assertTrue(q.contains("fromTimestamp=2026-10-01T00%3A00%3A00Z"), q);
        assertTrue(q.contains("toTimestamp=2026-10-09T00%3A00%3A00Z"), q); // date "to" is inclusive
        assertTrue(q.contains("userId=u1") && q.contains("name=chat") && q.contains("page=2") && q.contains("limit=10"));
    }

    @Test
    void limitIsCappedAndBadDatesAreRejected() {
        PlatformTraceService svc = service(u -> ok("{\"data\":[],\"meta\":{}}"), null);
        assertEquals(100, svc.list(null, null, null, null, null, 1, 5000).meta().limit());
        assertThrows(IllegalArgumentException.class, () -> svc.list("yesterday", null, null, null, null, 1, 10));
    }

    @Test
    void tenantFilterIsAppliedHereAndSaysSo() {
        UUID id = UUID.randomUUID();
        Tenant tenant = mock(Tenant.class);
        when(tenant.getSchemaName()).thenReturn("acme");
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(tenant));
        PlatformTraceService svc = service(u -> ok("{\"data\":[" + trace("t1", "acme") + "," + trace("t2", "other")
            + "],\"meta\":{\"page\":1,\"limit\":100,\"totalItems\":2,\"totalPages\":1}}"), repo);

        TraceListResponse r = svc.list(null, null, id.toString(), null, null, 1, 10);

        assertTrue(r.available());
        assertEquals(List.of("t1"), r.data().stream().map(PlatformTraceDtos.TraceRow::id).toList());
        assertEquals(1, r.meta().totalItems());
        assertTrue(r.reason().contains("cannot filter traces by tenant"));
        assertEquals(1, calls.size()); // only one page existed
    }

    @Test
    void upstreamFailureIsUnavailableWithReason() {
        PlatformTraceService svc = service(u -> new LangfuseApiClient.Response(503, ""), null);
        TraceListResponse r = svc.list(null, null, null, null, null, null, null);
        assertFalse(r.available());
        assertTrue(r.reason().contains("503"));
    }

    @Test
    void detailSumsObservationsTruncatesIoAndPicksWorstLevel() {
        String longInput = "x".repeat(5000);
        String body = "{\"id\":\"t1\",\"name\":\"chat\",\"latency\":0.5,\"metadata\":{\"tenantId\":\"acme\"},"
            + "\"input\":\"" + longInput + "\",\"output\":{\"text\":\"hello\"},\"observations\":["
            + "{\"id\":\"o2\",\"type\":\"GENERATION\",\"startTime\":\"2026-10-09T11:00:02Z\",\"model\":\"gpt-4o\","
            + "\"usageDetails\":{\"input\":100,\"output\":20},\"calculatedTotalCost\":0.001,\"level\":\"WARNING\"},"
            + "{\"id\":\"o1\",\"type\":\"SPAN\",\"startTime\":\"2026-10-09T11:00:01Z\",\"usage\":{\"input\":5,\"output\":1},"
            + "\"level\":\"DEFAULT\"}]}";
        PlatformTraceService svc = service(u -> ok(body), null);

        TraceDetailResponse r = svc.detail("t1");

        assertTrue(r.available());
        assertEquals(4000, r.trace().input().length());
        assertEquals("{\"text\":\"hello\"}", r.trace().output());
        assertEquals(105L, r.trace().inputTokens());
        assertEquals(21L, r.trace().outputTokens());
        assertEquals("WARNING", r.trace().level());
        assertEquals(List.of("o1", "o2"), r.observations().stream().map(PlatformTraceDtos.ObservationRow::id).toList());
        assertEquals(0.001, r.observations().get(1).costUsd());
        assertEquals("http://lf:3000/api/public/traces/t1", calls.get(0).toString());
    }

    @Test
    void detailNotFoundAndInvalidId() {
        PlatformTraceService svc = service(u -> new LangfuseApiClient.Response(404, "{}"), null);
        TraceDetailResponse r = svc.detail("missing");
        assertTrue(r.available());
        assertNull(r.trace());
        assertThrows(IllegalArgumentException.class, () -> svc.detail("../admin"));
    }

    @Test
    void metricsAggregateDailyUsageAndAttachLatencyWhenAvailable() {
        String daily = "{\"data\":[{\"date\":\"2026-10-08\",\"countTraces\":10,\"totalCost\":0.5,\"usage\":["
            + "{\"model\":\"gpt-4o\",\"inputUsage\":1000,\"outputUsage\":200,\"countObservations\":12,\"totalCost\":0.4},"
            + "{\"model\":\"claude\",\"inputUsage\":10,\"outputUsage\":2,\"countObservations\":1,\"totalCost\":0.1}]},"
            + "{\"date\":\"2026-10-07\",\"countTraces\":3,\"totalCost\":0.1,\"usage\":["
            + "{\"model\":\"gpt-4o\",\"inputUsage\":100,\"outputUsage\":20,\"countObservations\":3,\"totalCost\":0.1}]}]}";
        String latency = "{\"data\":[{\"time_dimension\":\"2026-10-08T00:00:00.000Z\",\"p50_latency\":812.4,"
            + "\"p95_latency\":2400}]}";
        PlatformTraceService svc = service(u -> ok(u.getPath().endsWith("/daily") ? daily : latency), null);

        TraceMetricsResponse r = svc.metrics(null, null);

        assertTrue(r.available());
        assertEquals(List.of("2026-10-07", "2026-10-08"), r.byDay().stream().map(PlatformTraceDtos.TraceDayMetric::date).toList());
        assertNull(r.byDay().get(0).p50LatencyMs());
        assertEquals(812L, r.byDay().get(1).p50LatencyMs());
        assertEquals(2400L, r.byDay().get(1).p95LatencyMs());
        var top = r.byModel().get(0);
        assertEquals("gpt-4o", top.model());
        assertEquals(15L, top.calls());
        assertEquals(1100L, top.inputTokens());
        assertEquals(0.5, top.costUsd(), 1e-9);
    }

    @Test
    void metricsLatencyFailureLeavesNullsAndRangeIsBounded() {
        PlatformTraceService svc = service(u -> u.getPath().endsWith("/daily")
            ? ok("{\"data\":[{\"date\":\"2026-10-08\",\"countTraces\":1,\"totalCost\":0.1,\"usage\":[]}]}")
            : new LangfuseApiClient.Response(404, "{}"), null);
        TraceMetricsResponse r = svc.metrics("2026-10-01", "2026-10-08");
        assertTrue(r.available());
        assertNull(r.byDay().get(0).p95LatencyMs());
        assertThrows(IllegalArgumentException.class, () -> svc.metrics("2026-01-01", "2026-10-08"));
        assertThrows(IllegalArgumentException.class, () -> svc.metrics("2026-10-08", "2026-10-01"));
    }
}
