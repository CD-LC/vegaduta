package com.agenticai.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.payments.adapter.UpiAgentProtocolAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction boundaries of the payment path, through Spring's real transaction proxies.
 *
 * <p>The other payment tests build the services with {@code new}, so a {@code this.method()} call
 * to a {@code @Transactional} method looks fine there; behind a proxy it runs with no transaction.
 * {@link MandateEnforcer#reserveCaps} and {@link MandateEnforcer#releaseCaps} are {@code
 * Propagation.MANDATORY}: they must see a transaction, and the gateway call must not.
 */
@SpringJUnitConfig(PaymentTransactionBoundaryTest.Config.class)
class PaymentTransactionBoundaryTest {

    private static final UUID AGENT = UUID.randomUUID();

    // Raw mocks, stubbed directly. Spring wraps the beans in transaction proxies (MandateEnforcer
    // is
    // @Transactional), so stubbing through the injected bean would itself hit those proxies;
    // production
    // calls still go through them, which is what enforces MANDATORY exactly as in a running app.
    private static final PaymentMandateRepository MANDATES = mock(PaymentMandateRepository.class);
    private static final PaymentTransactionRepository TRANSACTIONS =
            mock(PaymentTransactionRepository.class);
    private static final MandateEnforcer ENFORCER = mock(MandateEnforcer.class);
    private static final UpiAgentProtocolAdapter ADAPTER = mock(UpiAgentProtocolAdapter.class);

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        PaymentMandateRepository mandateRepository() {
            return MANDATES;
        }

        @Bean
        PaymentTransactionRepository transactionRepository() {
            return TRANSACTIONS;
        }

        @Bean
        MandateEnforcer enforcer() {
            return ENFORCER;
        }

        @Bean
        UpiAgentProtocolAdapter adapter() {
            return ADAPTER;
        }

        @Bean
        @SuppressWarnings("unchecked")
        ObjectProvider<UpiAgentProtocolAdapter> adapterProvider(UpiAgentProtocolAdapter adapter) {
            ObjectProvider<UpiAgentProtocolAdapter> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(adapter);
            return provider;
        }

        @Bean
        PaymentExecutionService executionService(
                PaymentTransactionRepository transactions,
                PaymentMandateRepository mandates,
                MandateEnforcer enforcer,
                ObjectProvider<UpiAgentProtocolAdapter> adapterProvider,
                PlatformTransactionManager transactionManager) {
            return new PaymentExecutionService(
                    transactions,
                    mandates,
                    enforcer,
                    adapterProvider,
                    mock(WorkflowEventPublisher.class),
                    mock(AuditLogService.class),
                    transactionManager);
        }

        @Bean
        PaymentToolService paymentToolService(
                PaymentMandateRepository mandates,
                PaymentTransactionRepository transactions,
                MandateEnforcer enforcer,
                PaymentExecutionService executionService,
                ObjectProvider<UpiAgentProtocolAdapter> adapterProvider,
                PlatformTransactionManager transactionManager) {
            return new PaymentToolService(
                    mandates,
                    transactions,
                    enforcer,
                    mock(HumanApprovalGateService.class),
                    executionService,
                    adapterProvider,
                    mock(AuditLogService.class),
                    transactionManager,
                    86400);
        }
    }

    /**
     * Begins and commits nothing real; only marks a transaction active on the thread and, like a
     * real manager, reports it as existing to a nested call (so REQUIRED joins and MANDATORY is
     * satisfied).
     */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    @Autowired private PaymentToolService paymentToolService;

    /** "step in-transaction?" in call order. */
    private final List<String> steps = new ArrayList<>();

    @BeforeEach
    void wire() {
        org.mockito.Mockito.reset(MANDATES, TRANSACTIONS, ENFORCER, ADAPTER);
        steps.clear();

        PaymentMandate mandate = new PaymentMandate();
        mandate.setId(UUID.randomUUID());
        mandate.setAgentId(AGENT);
        mandate.setScope(MandateScope.COLLECT);
        mandate.setApprovalPolicy(MandateApprovalPolicy.NEVER);
        mandate.setValidFrom(Instant.now().minusSeconds(60));
        mandate.setValidUntil(Instant.now().plusSeconds(3600));
        mandate.setStatus(MandateStatus.ACTIVE);
        when(MANDATES.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(AGENT, MandateStatus.ACTIVE))
                .thenReturn(Optional.of(mandate));
        when(MANDATES.findById(mandate.getId())).thenReturn(Optional.of(mandate));

        PaymentTransaction[] row = new PaymentTransaction[1];
        when(TRANSACTIONS.save(any()))
                .thenAnswer(
                        inv -> {
                            row[0] = inv.getArgument(0);
                            return row[0];
                        });
        when(TRANSACTIONS.findById(any())).thenAnswer(inv -> Optional.ofNullable(row[0]));
        when(TRANSACTIONS.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(row[0]));

        doAnswer(inv -> record("reserveCaps"))
                .when(ENFORCER)
                .reserveCaps(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        doAnswer(inv -> record("releaseCaps")).when(ENFORCER).releaseCaps(any(), any(), any());
    }

    private Object record(String step) {
        steps.add(step + " tx=" + TransactionSynchronizationManager.isActualTransactionActive());
        return null;
    }

    @Test
    void capsAreReservedInATransactionAndTheGatewayIsCalledOutsideOne() {
        when(ADAPTER.initiateCollection(any()))
                .thenAnswer(
                        inv -> {
                            record("gateway");
                            return new UpiAgentProtocolAdapter.AdapterInitiation(
                                    "plink_1", "https://pay/x", "created");
                        });

        var outcome =
                paymentToolService.createPaymentLink(
                        AGENT, "s1", 10_000, "invoice", null, null, null);

        assertThat(steps).containsExactly("reserveCaps tx=true", "gateway tx=false");
        assertThat(outcome.status()).isEqualTo(PaymentTransactionStatus.AWAITING_PAYMENT);
    }

    @Test
    void aGatewayFailureReleasesTheCapsInATransaction() {
        when(ADAPTER.initiateCollection(any()))
                .thenAnswer(
                        inv -> {
                            record("gateway");
                            throw new IllegalStateException("gateway down");
                        });

        var outcome =
                paymentToolService.createPaymentLink(
                        AGENT, "s2", 10_000, "invoice", null, null, null);

        assertThat(steps)
                .containsExactly("reserveCaps tx=true", "gateway tx=false", "releaseCaps tx=true");
        assertThat(outcome.status()).isEqualTo(PaymentTransactionStatus.FAILED);
    }
}
