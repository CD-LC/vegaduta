package com.agenticai.core.agents.spendcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.chat.SpendReport;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SpendCapServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final String SCHEMA = "tenant_acme";
    private static final String KEY = "spendcap:mtd:" + TENANT + ":2026-10";

    private JdbcTemplate jdbc;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> ops;
    private LlmUsageRollupService rollup;
    private final List<SpendCapAlertSink.Alert> alerts = new ArrayList<>();
    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));
    private SpendCapService service;
    private SpendCapService.TenantCap row;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        rollup = mock(LlmUsageRollupService.class);
        ObjectProvider<StringRedisTemplate> redis = provider(redisTemplate);
        ObjectProvider<SpendCapAlertSink> sinks = mock(ObjectProvider.class);
        when(sinks.orderedStream()).thenAnswer(inv -> Stream.of((SpendCapAlertSink) alerts::add));
        row = cap(new BigDecimal("100.00"), 0, null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenAnswer(inv -> row == null ? List.of() : List.of(row));
        when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenAnswer(inv -> row == null ? List.of() : List.of(row));
        when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenAnswer(inv -> row == null ? List.of() : List.of(row));
        service = new SpendCapService(jdbc, redis, rollup, sinks, new DelegatingClock(clock), Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static SpendCapService.TenantCap cap(BigDecimal capUsd, int stage, LocalDate month) {
        return new SpendCapService.TenantCap(TENANT, SCHEMA, "Acme", capUsd, stage, month);
    }

    private void hostedMonthToDate(String usd) {
        SpendReport.ProviderUsage hosted = new SpendReport.ProviderUsage("PLATFORM_HOSTED", metrics(usd));
        SpendReport.ProviderUsage byok = new SpendReport.ProviderUsage("OPENAI", metrics("999"));
        when(rollup.byProvider(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))).thenReturn(List.of(hosted, byok));
    }

    private static SpendReport.UsageMetrics metrics(String usd) {
        return new SpendReport.UsageMetrics(1, 0, 1.0, 0, 0, 0, 0, 0, new BigDecimal(usd), null, null, null, null);
    }

    // ------------------------------------------------------------------ gate

    @Test
    void tenantWithoutCapNeverTouchesRedisOrTheRollup() {
        row = cap(null, 0, null);
        service.assertWithinCap(SCHEMA);
        verify(redisTemplate, never()).opsForValue();
        verify(rollup, never()).byProvider(any(), any());
    }

    @Test
    void belowCapPasses() {
        when(ops.get(KEY)).thenReturn("42.5");
        service.assertWithinCap(SCHEMA);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void atCapRejectsWithContractFieldsAndRaisesTheHundredPercentAlertOnce() {
        when(ops.get(KEY)).thenReturn("100.00");
        when(jdbc.update(eq(SpendCapService.SQL_CLAIM_STAGE), any(Object[].class))).thenReturn(1, 0);

        SpendCapReachedException e = assertThrows(SpendCapReachedException.class, () -> service.assertWithinCap(SCHEMA));
        assertEquals(new BigDecimal("100.00"), e.getCapUsd());
        assertEquals(0, new BigDecimal("100").compareTo(e.getMonthToDateUsd()));
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), e.getResetsAt());
        assertEquals(1, alerts.size());
        assertEquals(100, alerts.get(0).stage());

        assertThrows(SpendCapReachedException.class, () -> service.assertWithinCap(SCHEMA));
        assertEquals(1, alerts.size(), "the second crossing claim loses - no duplicate alert");
    }

    @Test
    void missingKeySeedsFromHostedOnlyDatabaseFigureWithTtl() {
        when(ops.get(KEY)).thenReturn(null);
        when(ops.setIfAbsent(eq(KEY), anyString(), eq(SpendCapService.KEY_TTL))).thenReturn(true);
        hostedMonthToDate("12.34");

        service.assertWithinCap(SCHEMA);

        verify(ops).setIfAbsent(KEY, "12.34", Duration.ofDays(40));
        assertTrue(TenantContext.current().isEmpty(), "tenant context restored after the seed query");
    }

    @Test
    void redisDownFallsBackToCachedDatabaseFigure() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        hostedMonthToDate("150");
        assertThrows(SpendCapReachedException.class, () -> service.assertWithinCap(SCHEMA));
        assertThrows(SpendCapReachedException.class, () -> service.assertWithinCap(SCHEMA));
        verify(rollup, times(1)).byProvider(any(), any());
    }

    @Test
    void redisAndDatabaseDownFailsOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        when(rollup.byProvider(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.assertWithinCap(SCHEMA); // no exception
        assertFalse(service.isBlocked(SCHEMA));
    }

    @Test
    void unknownTenantIsNotCapped() {
        row = null;
        service.assertWithinCap("nope");
        assertFalse(service.isBlocked(null));
    }

    // ------------------------------------------------------------------ counter feed

    @Test
    @SuppressWarnings("unchecked")
    void hostedSpendIncrementsOnlyAnExistingKeyAndAlertsAtEightyPercent() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("85.0");
        when(jdbc.update(eq(SpendCapService.SQL_CLAIM_STAGE), any(Object[].class))).thenReturn(1);

        service.onHostedSpend(SCHEMA, new BigDecimal("0.25"));

        verify(redisTemplate).execute(eq(SpendCapService.INCR_IF_EXISTS), eq(List.of(KEY)), eq("0.25"));
        assertEquals(1, alerts.size());
        assertEquals(80, alerts.get(0).stage());
        verify(jdbc).update(SpendCapService.SQL_CLAIM_STAGE, 80, Date.valueOf("2026-10-01"), TENANT,
            Date.valueOf("2026-10-01"), 80);
    }

    @Test
    @SuppressWarnings("unchecked")
    void alreadyAlertedStageThisMonthIsNotReclaimed() {
        row = cap(new BigDecimal("100.00"), 80, LocalDate.of(2026, 10, 1));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("90");
        service.onHostedSpend(SCHEMA, new BigDecimal("1"));
        verify(jdbc, never()).update(eq(SpendCapService.SQL_CLAIM_STAGE), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastMonthsStageReArmsOnANewMonth() {
        row = cap(new BigDecimal("100.00"), 100, LocalDate.of(2026, 9, 1));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("81");
        when(jdbc.update(eq(SpendCapService.SQL_CLAIM_STAGE), any(Object[].class))).thenReturn(1);
        service.onHostedSpend(SCHEMA, new BigDecimal("1"));
        assertEquals(80, alerts.get(0).stage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncappedTenantSpendIsIgnoredAndZeroCostIsIgnored() {
        service.onHostedSpend(SCHEMA, BigDecimal.ZERO);
        row = cap(null, 0, null);
        service.onHostedSpend(SCHEMA, new BigDecimal("3"));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    // ------------------------------------------------------------------ admin

    @Test
    void capValidation() {
        assertNull(SpendCapService.validateCap(null));
        assertEquals(new BigDecimal("10.50"), SpendCapService.validateCap(new BigDecimal("10.5")));
        assertEquals(new BigDecimal("1000000.00"), SpendCapService.validateCap(new BigDecimal("1000000")));
        assertThrows(IllegalArgumentException.class, () -> SpendCapService.validateCap(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SpendCapService.validateCap(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> SpendCapService.validateCap(new BigDecimal("1000000.01")));
    }

    @Test
    void stages() {
        BigDecimal cap = new BigDecimal("50");
        assertEquals(0, SpendCapService.stageFor(cap, new BigDecimal("39.99")));
        assertEquals(80, SpendCapService.stageFor(cap, new BigDecimal("40")));
        assertEquals(100, SpendCapService.stageFor(cap, new BigDecimal("50")));
        assertEquals(0, SpendCapService.stageFor(null, new BigDecimal("50")));
    }

    @Test
    void statusReportsContractShape() {
        row = cap(new BigDecimal("200.00"), 80, LocalDate.of(2026, 10, 1));
        when(ops.get(KEY)).thenReturn("170");
        SpendCapService.CapStatus s = service.status(TENANT).orElseThrow();
        assertEquals(TENANT, s.tenantId());
        assertEquals(new BigDecimal("200.00"), s.capUsd());
        assertEquals(new BigDecimal("85.0"), s.percentUsed());
        assertEquals(80, s.alertStage());
        assertFalse(s.blocked());
        assertEquals(LocalDate.of(2026, 10, 1), s.monthStart());
    }

    @Test
    void statusWithoutCapHasNullPercentAndIsNeverBlocked() {
        row = cap(null, 0, null);
        when(ops.get(KEY)).thenReturn("1000");
        SpendCapService.CapStatus s = service.status(TENANT).orElseThrow();
        assertNull(s.capUsd());
        assertNull(s.percentUsed());
        assertFalse(s.blocked());
        assertEquals(0, s.alertStage());
    }

    @Test
    void updateCapResetsStageAndReEvaluatesAgainstTheNewCap() {
        when(ops.get(KEY)).thenReturn("60");
        when(jdbc.update(eq(SpendCapService.SQL_CLAIM_STAGE), any(Object[].class))).thenReturn(1);
        row = cap(new BigDecimal("50.00"), 0, null);

        SpendCapService.CapStatus s = service.updateCap(TENANT, new BigDecimal("50")).orElseThrow();

        verify(jdbc).update(SpendCapService.SQL_SET_CAP, new BigDecimal("50.00"), TENANT);
        assertTrue(s.blocked());
        assertEquals(100, s.alertStage());
        assertEquals(1, alerts.size());
    }

    @Test
    void updateCapToNullClearsIt() {
        row = cap(null, 0, null);
        when(ops.get(KEY)).thenReturn("60");
        SpendCapService.CapStatus s = service.updateCap(TENANT, null).orElseThrow();
        verify(jdbc).update(SpendCapService.SQL_SET_CAP, null, TENANT);
        assertFalse(s.blocked());
        assertTrue(alerts.isEmpty());
    }

    /** Clock whose instant the test can move. */
    private static final class DelegatingClock extends Clock {
        private final AtomicReference<Clock> delegate;

        DelegatingClock(AtomicReference<Clock> delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return delegate.get().instant();
        }
    }
}
