package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.payments.PaymentDtos.MandateSpendResponse;
import com.agenticai.core.payments.PaymentDtos.TransactionResponse;
import com.agenticai.core.payments.PaymentMandateService.MandateSpend;
import com.agenticai.core.payments.PaymentMandateService.MandateSpendView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

/** offset + X-Total-Count on GET /api/payments/transactions and the batch GET /mandates/spend. */
class PaymentControllerPagingTest {

    private final PaymentMandateService mandates = mock(PaymentMandateService.class);
    private final PaymentTransactionRepository txns = mock(PaymentTransactionRepository.class);
    private final PaymentController controller =
            new PaymentController(mandates, txns, mock(MandateCanonicalizer.class));

    @Test
    void agentTransactionsPassTheOffsetAndReportTotals() {
        UUID agent = UUID.randomUUID();
        when(txns.findByAgentIdOrderByCreatedAtDescIdDesc(eq(agent), any(Pageable.class))).thenReturn(List.of());
        when(txns.countByAgentId(agent)).thenReturn(120L);

        ResponseEntity<List<TransactionResponse>> r = controller.transactions(agent, null, 50, 50L);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(txns).findByAgentIdOrderByCreatedAtDescIdDesc(eq(agent), page.capture());
        assertEquals(50L, page.getValue().getOffset());
        assertEquals(50, page.getValue().getPageSize());
        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void mandateFilterCountsThatMandateAndNoParamsKeepsTheOldFirstPage() {
        UUID agent = UUID.randomUUID();
        UUID mandate = UUID.randomUUID();
        when(txns.findByMandateIdOrderByCreatedAtDescIdDesc(eq(mandate), any(Pageable.class))).thenReturn(List.of());

        ResponseEntity<List<TransactionResponse>> r = controller.transactions(agent, mandate, 5000, null);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(txns).findByMandateIdOrderByCreatedAtDescIdDesc(eq(mandate), page.capture());
        assertEquals(0L, page.getValue().getOffset());
        assertEquals(200, page.getValue().getPageSize()); // clamped as before
        // short first page is its own total: no count query
        verify(txns, never()).countByMandateId(any());
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void batchSpendReturnsOneMeterPerMandate() {
        UUID agent = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        when(mandates.spendForAgent(agent)).thenReturn(List.of(
                new MandateSpend(m1, new MandateSpendView(100, 200, 300, 10, 20)),
                new MandateSpend(m2, new MandateSpendView(1, 2, 3, 0, 0))));

        List<MandateSpendResponse> out = controller.spendForAgent(agent);

        assertEquals(2, out.size());
        assertEquals(m1, out.get(0).mandateId());
        assertEquals(200L, out.get(0).maxDailyPaise());
        assertEquals(20L, out.get(0).monthlySpendPaise());
        assertEquals(m2, out.get(1).mandateId());
    }
}
