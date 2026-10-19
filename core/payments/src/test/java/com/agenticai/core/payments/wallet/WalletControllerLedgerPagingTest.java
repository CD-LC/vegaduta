package com.agenticai.core.payments.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.payments.wallet.WalletDtos.LedgerEntryResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** offset + X-Total-Count / X-Has-More on GET /api/wallets/{id}/ledger. */
class WalletControllerLedgerPagingTest {

    private final AgentWalletService service = mock(AgentWalletService.class);
    private final WalletController controller = new WalletController(service);

    @Test
    void passesOffsetAndReportsTotals() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.of(mock(AgentWallet.class)));
        when(service.ledger(id, 50, 50L)).thenReturn(List.of());
        when(service.ledgerCount(id)).thenReturn(130L);

        ResponseEntity<List<LedgerEntryResponse>> r = controller.ledger(id, 50, 50L);

        verify(service).ledger(id, 50, 50L);
        assertEquals("130", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void noOffsetClampsLimitAndShortPageIsItsOwnTotal() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.of(mock(AgentWallet.class)));
        when(service.ledger(id, 200, 0L)).thenReturn(List.of());

        ResponseEntity<List<LedgerEntryResponse>> r = controller.ledger(id, 9999, null);

        verify(service).ledger(id, 200, 0L);
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }
}
