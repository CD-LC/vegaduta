package com.agenticai.core.billing.gateway;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.billing.commerce.PaidPaymentLookup;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Handles the two UPI-collect-follow-up confirmation events - Razorpay's {@code payment_link.paid}
 * ({@link BillingEventType#PAYMENT_LINK_PAID}) and {@code qr_code.credited} ({@link
 * BillingEventType#QR_CODE_PAYMENT_RECEIVED}) - fired when a customer completes a UPI Payment Link
 * or scans a UPI QR code. One handler bean covers both event types (registered twice in {@link
 * BillingEventDispatcher}'s constructor) since the handling is identical.
 *
 * <p><b>Agentic commerce v0 (core/billing/commerce):</b> the reference id the webhook carries
 * ({@code plink_...}/{@code qr_...}, reused in {@link BillingEvent#gatewaySubscriptionId} - see
 * that record's javadoc and {@link RazorpayGatewayClient}'s {@code extractReferenceId}) is now
 * looked up against {@code control_plane.checkout_session.gateway_ref_id}. A matching {@link
 * CheckoutSession} - one the {@code create_payment_link} agent tool created - is marked COMPLETED
 * with {@code paid_at} and, when {@link PaidPaymentLookup} could fetch it back from the gateway,
 * the captured {@code payment_id}; {@link CheckoutSessionService #recordGatewayPayment} owns the
 * idempotency (state-guarded UPDATE), the amount check, and the in-JVM completed event that
 * core/agents bridges onto the workflow eventbus.
 *
 * <p><b>Still deliberately does NOT credit any balance.</b> The reasoning this class's original
 * javadoc laid out stands for every link NOT created through the commerce package: {@code
 * RazorpayCheckoutController #createUpiCollect}/{@code #createUpiQr} still take no
 * tenant/user/modality request fields to stash a pending-order record with, {@code BillingEvent}
 * still carries no amount, and the one real credit mechanism in this app ({@code
 * PerUserBudgetService#creditTopUp}) is driven by a synchronous, client-submitted confirm call with
 * no counterpart here. So a reference id that matches no checkout session keeps the exact
 * pre-existing behaviour: one clear, structured, grep-able {@code UPI_PAYMENT_CONFIRMED} log line
 * so the payment is findable and reconcilable by a human rather than vanishing.
 *
 * <p>Same fire-and-forget, do-not-re-raise posture as every other {@link BillingEventHandler} -
 * {@code BillingEventDispatcher} already logs and swallows any exception this throws, and the
 * webhook controller has already returned 200 by the time this runs. Note this thread carries no
 * {@code TenantContext}; everything it touches is a fully-qualified control-plane table, by design.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class UpiPaymentConfirmedHandler implements BillingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(UpiPaymentConfirmedHandler.class);

    private final CheckoutSessionService checkoutSessionService;
    // ObjectProvider: RazorpayPaidPaymentLookup is @Profile("security") like
    // RazorpayGatewayClient, and the handler must still construct (and still
    // complete sessions, minus the payment id) in a context without it.
    private final ObjectProvider<PaidPaymentLookup> paidPaymentLookup;

    public UpiPaymentConfirmedHandler(
            CheckoutSessionService checkoutSessionService,
            ObjectProvider<PaidPaymentLookup> paidPaymentLookup) {
        this.checkoutSessionService = checkoutSessionService;
        this.paidPaymentLookup = paidPaymentLookup;
    }

    @Override
    public void handle(BillingEvent event) {
        // Reused field name (see BillingEvent's javadoc) - here it's the
        // Razorpay payment_link id (plink_...) or qr_code id (qr_...), not a
        // subscription id.
        String referenceId = event.gatewaySubscriptionId();
        if (referenceId == null || referenceId.isBlank()) {
            log.warn(
                    "UPI_PAYMENT_CONFIRMED type={} rawEvent={} - payment confirmed but the webhook "
                            + "carried no payment_link/qr_code id, so it is not attributable to anything",
                    event.type(),
                    event.rawEventName());
            return;
        }

        // Gateway-side fetch of the captured payment (id + amount actually
        // paid). Best-effort by PaidPaymentLookup's contract: null here just
        // means "complete without a payment id and skip the amount check".
        PaidPaymentLookup.PaidPayment payment = lookupPayment(referenceId);

        CheckoutSessionService.Reconciliation outcome =
                checkoutSessionService.recordGatewayPayment(referenceId, payment);
        CheckoutSession session = outcome.session();
        switch (outcome.result()) {
            case COMPLETED ->
                    log.info(
                            "UPI_PAYMENT_CONFIRMED type={} referenceId={} rawEvent={} - checkout_session {} (tenant {}, "
                                    + "{} paise) marked COMPLETED, paymentId={}",
                            event.type(),
                            referenceId,
                            event.rawEventName(),
                            session.id(),
                            session.tenantId(),
                            session.amountPaise(),
                            session.paymentId());
            case ALREADY_COMPLETED ->
                    log.info(
                            "UPI_PAYMENT_CONFIRMED type={} referenceId={} rawEvent={} - checkout_session {} was already "
                                    + "COMPLETED (redelivered webhook, idempotent no-op)",
                            event.type(),
                            referenceId,
                            event.rawEventName(),
                            session.id());
            case AMOUNT_MISMATCH ->
                    log.error(
                            "UPI_PAYMENT_AMOUNT_MISMATCH type={} referenceId={} rawEvent={} - checkout_session {} (tenant {}) "
                                    + "NOT completed: {}. Reconcile against this reference id in the Razorpay dashboard.",
                            event.type(),
                            referenceId,
                            event.rawEventName(),
                            session.id(),
                            session.tenantId(),
                            session.reviewNote());
            case NOT_FOUND ->
                    log.info(
                            "UPI_PAYMENT_CONFIRMED type={} referenceId={} rawEvent={} - payment confirmed paid by "
                                    + "Razorpay but no checkout_session carries this reference id (link created outside the "
                                    + "create_payment_link tool, e.g. /api/billing/checkout/create-upi-collect); NOT credited to "
                                    + "any balance - see this class's javadoc. Reconcile manually against this reference id in "
                                    + "the Razorpay dashboard if a credit is owed.",
                            event.type(),
                            referenceId,
                            event.rawEventName());
        }
    }

    private PaidPaymentLookup.PaidPayment lookupPayment(String referenceId) {
        PaidPaymentLookup lookup = paidPaymentLookup.getIfAvailable();
        if (lookup == null) {
            return null;
        }
        try {
            return lookup.lookup(referenceId).orElse(null);
        } catch (RuntimeException e) {
            // The lookup's own contract is never-throw, but a session must
            // complete even if an implementation forgets that.
            log.warn(
                    "Paid-payment lookup threw for reference id '{}' - completing without a payment id",
                    referenceId,
                    e);
            return null;
        }
    }
}
