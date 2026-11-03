package com.agenticai.core.agents.spendcap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmUsageRollupService;
import com.agenticai.core.security.SpendControlsFailMode;
import com.agenticai.core.security.SpendControlsUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Fail-closed mode and the optional per-call pre-reservation of the (otherwise soft) monthly cap. */
class SpendCapFailModeAndReservationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final String SCHEMA = "tenant_acme";
    private static final String KEY = "spendcap:mtd:" + TENANT + ":2026-10";

    private JdbcTemplate jdbc;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> ops;
    private LlmUsageRollupService rollup;
    private SpendCapService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        rollup = mock(LlmUsageRollupService.class);
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(redisTemplate);
        ObjectProvider<SpendCapAlertSink> sinks = mock(ObjectProvider.class);
        when(sinks.orderedStream()).thenAnswer(inv -> Stream.<SpendCapAlertSink>empty());
        SpendCapService.TenantCap row = new SpendCapService.TenantCap(TENANT, SCHEMA, "Acme",
            new BigDecimal("100.00"), 0, null);
        when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenAnswer(inv -> List.of(row));
        service = new SpendCapService(jdbc, redis, rollup, sinks, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    // ------------------------------------------------------------------ fail mode

    private void redisAndDatabaseDown() {
        when(ops.get(KEY)).thenThrow(new IllegalStateException("redis down"));
        when(rollup.byProvider(any(), any())).thenThrow(new IllegalStateException("db down"));
    }

    @Test
    void openIsTheDefaultAndAllowsTheCallWhenTheCapCannotBeEvaluated() {
        redisAndDatabaseDown();

        assertDoesNotThrow(() -> service.assertWithinCap(SCHEMA));
    }

    @Test
    void closedRefusesWith503WhenTheCapCannotBeEvaluated() {
        redisAndDatabaseDown();
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        assertThrows(SpendControlsUnavailableException.class, () -> service.assertWithinCap(SCHEMA));
    }

    @Test
    void closedRefusesWhenTheCapConfigCannotBeRead() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenThrow(new IllegalStateException("db down"));
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        assertThrows(SpendControlsUnavailableException.class, () -> service.assertWithinCap("tenant_other"));
    }

    @Test
    void closedStillEvaluatesFromTheDatabaseWhileOnlyRedisIsDown() {
        when(ops.get(KEY)).thenThrow(new IllegalStateException("redis down"));
        when(rollup.byProvider(any(), any())).thenReturn(List.of());
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());

        assertDoesNotThrow(() -> service.assertWithinCap(SCHEMA));
    }

    @Test
    void closedRefusesWhenTheReservationCannotBeMade() {
        when(ops.get(KEY)).thenReturn("10.00");
        service.useReserveUsdPerCall(new BigDecimal("1.00"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> service.assertWithinCap(SCHEMA)); // open: skipped
        service.useSpendControlsFailMode(SpendControlsFailMode.closed());
        assertThrows(SpendControlsUnavailableException.class, () -> service.assertWithinCap(SCHEMA));
    }

    // ------------------------------------------------------------------ reservation

    @Test
    @SuppressWarnings("unchecked")
    void withoutAReservationSettingNothingIsReserved() {
        when(ops.get(KEY)).thenReturn("10.00");

        service.assertWithinCap(SCHEMA);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void aCallThatFitsAfterItsReservationIsAdmitted() {
        when(ops.get(KEY)).thenReturn("95.00");
        service.useReserveUsdPerCall(new BigDecimal("1.00"));
        when(redisTemplate.execute(eq(SpendCapService.RESERVE_INFLIGHT), anyList(), eq("1.00"), anyString()))
            .thenReturn("4.00"); // 95 + 4 in flight (including this call) = 99 <= 100

        assertDoesNotThrow(() -> service.assertWithinCap(SCHEMA));
    }

    @Test
    void aCallThatWouldPushSpendPlusInFlightOverTheCapIsRefusedAndGivesItsReservationBack() {
        when(ops.get(KEY)).thenReturn("95.00");
        service.useReserveUsdPerCall(new BigDecimal("1.00"));
        when(redisTemplate.execute(eq(SpendCapService.RESERVE_INFLIGHT), anyList(), eq("1.00"), anyString()))
            .thenReturn("6.00"); // 95 + 6 > 100: five other calls are already in flight

        assertThrows(SpendCapReachedException.class, () -> service.assertWithinCap(SCHEMA));

        verify(redisTemplate).execute(eq(SpendCapService.RELEASE_INFLIGHT), anyList(), eq("-1.00"));
    }

    @Test
    void recordedSpendReleasesTheReservationAndStillCountsTheCost() {
        when(ops.get(KEY)).thenReturn("10.00");
        service.useReserveUsdPerCall(new BigDecimal("1.00"));

        service.onHostedSpend(SCHEMA, new BigDecimal("0.40"));

        verify(redisTemplate).execute(eq(SpendCapService.RELEASE_INFLIGHT), anyList(), eq("-1.00"));
        verify(redisTemplate).execute(eq(SpendCapService.INCR_IF_EXISTS), anyList(), eq("0.40"));
    }
}
