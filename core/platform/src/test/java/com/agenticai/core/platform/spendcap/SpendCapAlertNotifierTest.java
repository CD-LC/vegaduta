package com.agenticai.core.platform.spendcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.spendcap.SpendCapAlertSink;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpendCapAlertNotifierTest {

    private final BillingEmailService email = mock(BillingEmailService.class);
    private final PlatformIssueService issues = mock(PlatformIssueService.class);
    private final SpendCapAlertNotifier notifier = new SpendCapAlertNotifier(email, issues);
    private final UUID tenantId = UUID.randomUUID();

    private SpendCapAlertSink.Alert alert(int stage) {
        return new SpendCapAlertSink.Alert(tenantId, "tenant_acme", "Acme <b>", stage, new BigDecimal("100.00"),
            new BigDecimal("101.2500"), LocalDate.of(2026, 10, 1));
    }

    @Test
    void hundredPercentEmailsAdminsAndRaisesAnErrorIssueInTheTenantSchema() {
        AtomicReference<String> schemaDuringRecord = new AtomicReference<>();
        when(issues.record(any(), anyString(), any(), anyString(), anyString())).thenAnswer(inv -> {
            schemaDuringRecord.set(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            return null;
        });

        notifier.onThresholdCrossed(alert(100));

        verify(email).sendSpendCapAlertEmail(eq(tenantId), eq("Acme <b>"), eq(100), eq(new BigDecimal("101.2500")),
            eq(new BigDecimal("100.00")), eq("1 November 2026"));
        verify(issues).record(eq(IssueSource.SPEND_CAP), eq(tenantId + ":2026-10-01"), eq(IssueSeverity.ERROR),
            anyString(), anyString());
        assertEquals("tenant_acme", schemaDuringRecord.get());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void emailFailureStillRaisesTheIssue() {
        when(email.sendSpendCapAlertEmail(any(), any(), anyInt(), any(), any(), any()))
            .thenThrow(new IllegalStateException("smtp down"));
        notifier.onThresholdCrossed(alert(80));
        verify(issues).record(eq(IssueSource.SPEND_CAP), anyString(), eq(IssueSeverity.WARNING), anyString(), anyString());
    }
}
