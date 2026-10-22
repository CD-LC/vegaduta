package com.agenticai.core.agents.tools.x402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.trust.DelegationTokenService;
import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.agentspi.AgentWalletLedger.WalletLedgerException;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The x402 beans must still construct when the payments module is not wired (the BILLING_PAYMENTS edition
 * feature is off): the ledger arrives through an {@link ObjectProvider} and, when absent, every wallet call
 * answers one explicit "payments disabled" refusal instead of the application failing to start.
 */
class PaymentsDisabledAgentWalletLedgerTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentWalletLedger> provider(AgentWalletLedger ledger) {
        ObjectProvider<AgentWalletLedger> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ledger);
        return p;
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anAbsentLedgerResolvesToTheRefusingStandIn() {
        AgentWalletLedger ledger = PaymentsDisabledAgentWalletLedger.orDisabled(provider(null));

        assertThat(ledger).isInstanceOf(PaymentsDisabledAgentWalletLedger.class);
        assertThat(PaymentsDisabledAgentWalletLedger.orDisabled(null)).isSameAs(ledger);
    }

    @Test
    void aWiredLedgerIsUsedAsIs() {
        AgentWalletLedger real = mock(AgentWalletLedger.class);

        assertThat(PaymentsDisabledAgentWalletLedger.orDisabled(provider(real))).isSameAs(real);
    }

    @Test
    void everyCallRefusesWithThePaymentsDisabledCodeAnd404() {
        AgentWalletLedger ledger = PaymentsDisabledAgentWalletLedger.orDisabled(provider(null));
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.getOrCreate(id, "INR")).satisfies(PaymentsDisabledAgentWalletLedgerTest::assertRefusal);
        assertThatThrownBy(() -> ledger.findById(id)).satisfies(PaymentsDisabledAgentWalletLedgerTest::assertRefusal);
        assertThatThrownBy(() -> ledger.credit(id, 1, "k", "x402", "r", "n"))
                .satisfies(PaymentsDisabledAgentWalletLedgerTest::assertRefusal);
        assertThatThrownBy(() -> ledger.debit(id, 1, "k", "x402", "r", "n"))
                .satisfies(PaymentsDisabledAgentWalletLedgerTest::assertRefusal);
    }

    private static void assertRefusal(Throwable t) {
        assertThat(t).isInstanceOf(WalletLedgerException.class);
        WalletLedgerException e = (WalletLedgerException) t;
        assertThat(e.getCode()).isEqualTo("PAYMENTS_DISABLED");
        assertThat(e.httpStatus()).isEqualTo(404);
    }

    @Test
    void theSellerServiceConstructsWithoutALedger() {
        X402SellerService service =
                new X402SellerService(
                        mock(X402OfferRepository.class),
                        mock(X402PaymentRepository.class),
                        mock(DelegationTokenService.class),
                        provider(null),
                        "",
                        10_000);

        assertThat(service).isNotNull();
    }

    @Test
    void publishingAnOfferWithoutALedgerAnswersPaymentsDisabledAndSavesNothing() {
        X402OfferRepository offers = mock(X402OfferRepository.class);
        when(offers.findBySlug(any())).thenReturn(Optional.empty());
        X402OfferController controller =
                new X402OfferController(offers, provider(null), mock(AgentService.class));
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));

        assertThatThrownBy(
                        () ->
                                controller.create(
                                        UUID.randomUUID(),
                                        new X402OfferController.CreateOfferRequest(
                                                "weather",
                                                "Weather",
                                                null,
                                                100,
                                                "INR",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "{}")))
                .satisfies(PaymentsDisabledAgentWalletLedgerTest::assertRefusal);
        verify(offers, never()).save(any());
    }
}
