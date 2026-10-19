package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.payments.PaymentMandateService.MandateSpend;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** spendForAgent: one meter per mandate, zero spend when a mandate has no ledger row yet. */
class PaymentMandateServiceBatchSpendTest {

    @Test
    void oneMeterPerMandateWithZeroSpendWhenNoLedgerRow() {
        PaymentMandateRepository mandates = mock(PaymentMandateRepository.class);
        MandateLedgerRepository ledger = mock(MandateLedgerRepository.class);
        PaymentMandateService service = new PaymentMandateService(
                mandates, mock(MandateSigningService.class), mock(MandateCanonicalizer.class),
                mock(MandateRevocationCache.class), ledger, mock(AuditLogService.class));
        UUID agent = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        PaymentMandate m = mock(PaymentMandate.class);
        when(m.getId()).thenReturn(id);
        when(m.getMaxPerTxnPaise()).thenReturn(500L);
        when(m.getMaxDailyPaise()).thenReturn(1000L);
        when(m.getMaxMonthlyPaise()).thenReturn(5000L);
        when(mandates.findByAgentIdOrderByCreatedAtDesc(agent)).thenReturn(List.of(m));
        when(ledger.findFirstByMandateIdOrderByIdDesc(id)).thenReturn(Optional.empty());

        List<MandateSpend> out = service.spendForAgent(agent);

        assertEquals(1, out.size());
        assertEquals(id, out.get(0).mandateId());
        assertEquals(1000L, out.get(0).view().maxDailyPaise());
        assertEquals(0L, out.get(0).view().dailySpendPaise());
        assertEquals(0L, out.get(0).view().monthlySpendPaise());
    }
}
