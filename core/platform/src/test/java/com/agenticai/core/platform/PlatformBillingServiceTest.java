package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformBillingServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformBillingService service = new PlatformBillingService(jdbc);

    private static Map<String, Object> tenant(
            String id,
            String name,
            String plan,
            String gwSub,
            Instant compExpires,
            Instant pastDue,
            boolean cancel) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("plan", plan);
        m.put("gateway_subscription_id", gwSub);
        m.put("subscription_status", gwSub == null ? null : "active");
        m.put("comp_plan_expires_at", compExpires == null ? null : Timestamp.from(compExpires));
        m.put("past_due_since", pastDue == null ? null : Timestamp.from(pastDue));
        m.put("cancel_scheduled", cancel);
        m.put("created_at", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return m;
    }

    @Test
    void stateClassificationOrder() {
        assertEquals(
                "COMP", PlatformBillingService.stateOf("UNLIMITED", "sub_1", true, true, true));
        assertEquals("FREE", PlatformBillingService.stateOf("FREE", null, false, false, false));
        assertEquals(
                "PAST_DUE", PlatformBillingService.stateOf("PREMIUM", "sub_1", false, true, true));
        assertEquals(
                "CANCELING",
                PlatformBillingService.stateOf("PREMIUM", "sub_1", false, false, true));
        assertEquals(
                "PAID", PlatformBillingService.stateOf("PREMIUM", "sub_1", false, false, false));
        assertEquals(
                "ADMIN_OVERRIDE",
                PlatformBillingService.stateOf("PREMIUM", null, false, false, false));
    }

    @Test
    void subscriptionsFilterByDerivedStateAndReportTotalBeforeCap() {
        Instant soon = Instant.now().plus(3, ChronoUnit.DAYS);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(
                                tenant("a", "Acme", "PREMIUM", "sub_1", null, null, false),
                                tenant("b", "Beta", "UNLIMITED", null, soon, null, false),
                                tenant("c", "Corp", "FREE", null, null, null, false),
                                tenant(
                                        "d",
                                        "Delta",
                                        "PREMIUM",
                                        "sub_2",
                                        null,
                                        Instant.now(),
                                        false)));

        var all = service.subscriptions(null, null, null, 2);
        assertEquals(4, all.total());
        assertEquals(2, all.subscriptions().size());
        assertFalse(all.scanTruncated());

        var comp = service.subscriptions(null, "comp", null, 100);
        assertEquals(1, comp.total());
        assertEquals("Beta", comp.subscriptions().get(0).name());
        assertEquals(
                soon.truncatedTo(ChronoUnit.MILLIS),
                comp.subscriptions().get(0).compExpiresAt().truncatedTo(ChronoUnit.MILLIS));

        assertEquals(
                "PAST_DUE",
                service.subscriptions(null, "PAST_DUE", null, 100).subscriptions().get(0).state());
    }

    @Test
    void subscriptionsBindsPlanAndEscapedNameAsParametersNotSql() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        service.subscriptions("premium", null, "50%_off", 10);
        verify(jdbc)
                .queryForList(
                        contains("t.plan = ?"),
                        org.mockito.ArgumentMatchers.<Object>any(),
                        org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void compGrantsFlagOverdueAndNeverExposeNothingElse() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(5, ChronoUnit.DAYS);
        Map<String, Object> a = tenant("a", "Acme", "UNLIMITED", null, past, null, false);
        Map<String, Object> b = tenant("b", "Beta", "PREMIUM", null, future, null, false);
        a.put("comp_plan_reason", "promo");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(a, b));

        var grants = service.compGrants(50);
        assertEquals(2, grants.total());
        assertTrue(grants.grants().get(0).overdue());
        assertFalse(grants.grants().get(1).overdue());
        assertEquals("promo", grants.grants().get(0).reason());
    }

    @Test
    void invoicesRejectNonUuidTenantAndNeverQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.invoices("not-a-uuid", 10));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void invoicesMapRows() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "i1");
        row.put("tenant_id", "t1");
        row.put("tenant_name", "Acme");
        row.put("invoice_number", "VD-202609-00001");
        row.put("currency", "INR");
        row.put("amount_minor", 118000L);
        row.put("gst_amount_minor", 18000L);
        row.put("issued_at", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

        var out = service.invoices(null, 999);
        assertEquals(1, out.count());
        assertEquals(118000L, out.invoices().get(0).amountMinor());
        assertEquals("Acme", out.invoices().get(0).tenantName());
        assertNull(out.invoices().get(0).gatewayPaymentId());
    }

    @Test
    void summaryAggregatesCountsAndKeepsCurrenciesSeparate() {
        when(jdbc.queryForList(contains("GROUP BY t.plan")))
                .thenReturn(List.of(Map.of("k", "FREE", "n", 8L), Map.of("k", "PREMIUM", "n", 2L)));
        when(jdbc.queryForList(contains("subscription_status, 'none'")))
                .thenReturn(List.of(Map.of("k", "none", "n", 8L), Map.of("k", "active", "n", 2L)));
        when(jdbc.queryForList(contains("comp_active")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "total",
                                        10L,
                                        "comp_active",
                                        3L,
                                        "comp_expiring_7d",
                                        1L,
                                        "comp_overdue_revert",
                                        0L,
                                        "past_due",
                                        1L,
                                        "cancel_scheduled",
                                        2L,
                                        "pending_upgrade",
                                        0L)));
        when(jdbc.queryForList(contains("billing_invoice")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "currency",
                                        "INR",
                                        "n",
                                        4L,
                                        "amount",
                                        400000L,
                                        "gst",
                                        61000L,
                                        "n30",
                                        2L,
                                        "amount30",
                                        200000L),
                                Map.of(
                                        "currency",
                                        "USD",
                                        "n",
                                        1L,
                                        "amount",
                                        2900L,
                                        "gst",
                                        0L,
                                        "n30",
                                        1L,
                                        "amount30",
                                        2900L)));

        var s = service.summary();
        assertEquals(10, s.liveTenants());
        assertEquals(2L, s.tenantsByPlan().get("PREMIUM"));
        assertEquals(3, s.activeCompGrants());
        assertEquals(1, s.compGrantsExpiringWithin7d());
        assertEquals(1, s.pastDue());
        assertEquals(2, s.cancelScheduled());
        assertEquals(2, s.invoiceTotals().size());
        assertEquals("USD", s.invoiceTotals().get(1).currency());
        assertEquals(2900L, s.invoiceTotals().get(1).amountMinor());
    }

    @Test
    void anExpiredCompGrantIsNoLongerReportedAsComp() {
        Instant expired = Instant.now().minus(1, ChronoUnit.DAYS);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(tenant("a", "Lapsed", "UNLIMITED", null, expired, null, false)));

        var row = service.subscriptions(null, null, null, 10).subscriptions().get(0);

        // Awaiting CompGrantExpiryReverter: the plan column still says UNLIMITED with no
        // gateway subscription, which is honestly an admin-side override until reverted.
        assertEquals("ADMIN_OVERRIDE", row.state());
        assertEquals(0, service.subscriptions(null, "COMP", null, 10).total());
    }

    @Test
    void isLiveCompRequiresAnExpiryStrictlyInTheFuture() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        assertTrue(PlatformBillingService.isLiveComp(now.plusSeconds(1), now));
        assertFalse(PlatformBillingService.isLiveComp(now, now));
        assertFalse(PlatformBillingService.isLiveComp(now.minusSeconds(1), now));
        assertFalse(PlatformBillingService.isLiveComp(null, now));
    }
}
