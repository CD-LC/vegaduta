package com.agenticai.core.payments;

import com.agenticai.core.billing.gateway.BillingEvent;
import com.agenticai.core.billing.gateway.BillingEventType;
import com.agenticai.core.billing.gateway.RoutedBillingEventHandler;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Settles {@code payment_transaction} rows off the two confirmation events
 * {@code payment_link.paid} / {@code qr_code.credited} - registered through
 * billing's {@link RoutedBillingEventHandler} fan-out seam (the primary
 * {@code UpiPaymentConfirmedHandler} still owns the control-plane
 * checkout-session path; this is a second, independent consumer of the same
 * already-signature-verified event).
 *
 * <p>The webhook thread carries no {@code TenantContext} and the
 * transaction rows are tenant-schema-scoped, so the gateway reference id
 * ({@code plink_...}/{@code qr_...} on {@link BillingEvent#gatewaySubscriptionId})
 * is resolved by iterating active tenant schemas until a row matches -
 * the same pattern every cross-tenant lookup in this codebase uses. Stops
 * early once a tenant claims the reference.
 */
@Component
public class MandateUpiPaymentHandler implements RoutedBillingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MandateUpiPaymentHandler.class);

    private final TenantSchemaIterator schemaIterator;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentExecutionService executionService;

    public MandateUpiPaymentHandler(
        TenantSchemaIterator schemaIterator,
        PaymentTransactionRepository transactionRepository,
        PaymentExecutionService executionService
    ) {
        this.schemaIterator = schemaIterator;
        this.transactionRepository = transactionRepository;
        this.executionService = executionService;
    }

    @Override
    public boolean routes(BillingEventType type) {
        return type == BillingEventType.PAYMENT_LINK_PAID
            || type == BillingEventType.QR_CODE_PAYMENT_RECEIVED;
    }

    @Override
    public void handle(BillingEvent event) {
        String referenceId = event.gatewaySubscriptionId();
        if (referenceId == null || referenceId.isBlank()) {
            return;
        }
        AtomicBoolean claimed = new AtomicBoolean(false);
        schemaIterator.forEachActiveTenantSchema(schema -> {
            if (claimed.get()) {
                return;
            }
            PaymentTransaction txn = transactionRepository.findByRazorpayEntityId(referenceId).orElse(null);
            if (txn == null) {
                return;
            }
            // A signature-verified gateway "paid" event is the authoritative
            // capture signal; amount is re-checked against the row inside.
            boolean handled = executionService.settleCaptured(txn.getId(), null, null);
            if (handled) {
                claimed.set(true);
                log.info("Mandate payment transaction {} (schema {}) captured via {} (reference {})",
                    txn.getId(), schema, event.rawEventName(), referenceId);
            }
        });
    }
}
