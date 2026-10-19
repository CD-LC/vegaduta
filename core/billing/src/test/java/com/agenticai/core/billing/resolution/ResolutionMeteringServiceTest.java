package com.agenticai.core.billing.resolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * V280 - the resolution meter's decision policy: which outcomes are
 * billable, that the price snapshot travels with the row, and that
 * exactly-once rides on the UNIQUE dedupe_key (an ON CONFLICT no-op, not a
 * thrown exception).
 */
class ResolutionMeteringServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResolutionEventRepository repository = new ResolutionEventRepository(jdbc);
    private final ResolutionPricingService pricing = new ResolutionPricingService(jdbc, 0L);
    private final ResolutionMeteringService metering = new ResolutionMeteringService(repository, pricing);

    /** Bind args of every successful insert, captured via the mock's Answer (varargs-safe). */
    private final List<Object[]> insertBinds = new ArrayList<>();

    private ResolutionMeteringService.RecordRequest request(ResolutionOutcome outcome, ResolutionEvidence evidence) {
        return new ResolutionMeteringService.RecordRequest(
            "cs:" + UUID.randomUUID(), UUID.randomUUID(), "WHATSAPP",
            outcome, evidence, BigDecimal.ONE, Instant.now(),
            "cs:x:123", null);
    }

    private void stubSettings(String unitPrice, String currency, String billIdle) {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(2, String.class);
            String value = switch (key) {
                case ResolutionPricingService.KEY_UNIT_PRICE_MINOR -> unitPrice;
                case ResolutionPricingService.KEY_CURRENCY -> currency;
                case ResolutionPricingService.KEY_BILL_IDLE_CLOSE -> billIdle;
                default -> null;
            };
            return value == null ? List.of() : List.of(value);
        });
    }

    private void stubInsertSuccess() {
        when(jdbc.query(contains("ON CONFLICT (dedupe_key) DO NOTHING"), any(RowMapper.class),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                insertBinds.add(Arrays.copyOfRange(inv.getArguments(), 2, inv.getArguments().length));
                return List.of(UUID.randomUUID());
            });
    }

    @Test
    void resolvedAiWithAnExplicitSignalIsBillableAndPriced() {
        stubSettings("250", "USD", "true");
        stubInsertSuccess();

        assertTrue(metering.record(request(ResolutionOutcome.RESOLVED_AI, ResolutionEvidence.USER_RESOLVED_SIGNAL)).isPresent());

        // billable=true (8th bind) and the 250-minor-unit USD snapshot travels with the row.
        Object[] binds = insertBinds.get(0);
        assertEquals(true, binds[7]);
        assertEquals(250L, binds[9]);
        assertEquals("USD", binds[10]);
    }

    @Test
    void anIdleCloseIsBillableOnlyWhileTheSettingAllows() {
        stubSettings("250", "USD", "false");
        stubInsertSuccess();

        metering.record(request(ResolutionOutcome.RESOLVED_AI, ResolutionEvidence.NO_ESCALATION_IDLE));

        Object[] binds = insertBinds.get(0);
        assertEquals(false, binds[7]);
        assertNull(binds[9]);
    }

    @Test
    void nonAiOutcomesAreNeverBillable() {
        ResolutionPricingService.PricingSnapshot price =
            new ResolutionPricingService.PricingSnapshot(100, "USD", true, 72);
        assertFalse(ResolutionMeteringService.isBillable(ResolutionOutcome.ESCALATED,
            ResolutionEvidence.ESCALATION_OPEN, price));
        assertFalse(ResolutionMeteringService.isBillable(ResolutionOutcome.RESOLVED_HUMAN,
            ResolutionEvidence.OPERATOR_CLOSE, price));
        assertFalse(ResolutionMeteringService.isBillable(ResolutionOutcome.UNRESOLVED,
            ResolutionEvidence.FEEDBACK_NEGATIVE, price));
    }

    @Test
    void aDedupeConflictReturnsEmptyRatherThanThrowing() {
        stubSettings("0", "USD", "true");
        when(jdbc.query(contains("ON CONFLICT (dedupe_key) DO NOTHING"), any(RowMapper.class),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        Optional<UUID> result = metering.record(request(ResolutionOutcome.RESOLVED_AI, ResolutionEvidence.EXPLICIT_API));
        assertTrue(result.isEmpty());
    }

    @Test
    void missingOrCorruptSettingsFallBackToDefaults() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ResolutionPricingService.KEY_UNIT_PRICE_MINOR)))
            .thenReturn(List.of("not-a-number"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ResolutionPricingService.KEY_CURRENCY)))
            .thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ResolutionPricingService.KEY_BILL_IDLE_CLOSE)))
            .thenReturn(List.of("true"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(ResolutionPricingService.KEY_IDLE_CLOSE_HOURS)))
            .thenReturn(List.of());

        ResolutionPricingService.PricingSnapshot snapshot = pricing.snapshot();
        assertEquals(ResolutionPricingService.DEFAULT_UNIT_PRICE_MINOR, snapshot.unitPriceMinor());
        assertEquals(ResolutionPricingService.DEFAULT_CURRENCY, snapshot.currency());
        assertTrue(snapshot.billIdleClose());
        assertEquals(ResolutionPricingService.DEFAULT_IDLE_CLOSE_HOURS, snapshot.idleCloseHours());
    }

    @Test
    void disputeWritesWhoAndWhyAndFlipsBillableOff() {
        when(jdbc.update(contains("SET billable = FALSE"), any(), any(), any(), any(UUID.class))).thenReturn(1);

        assertTrue(metering.dispute(UUID.randomUUID(), "admin@tenant", "the bot never answered"));
    }
}
