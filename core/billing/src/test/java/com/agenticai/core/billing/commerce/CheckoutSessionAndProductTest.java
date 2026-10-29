package com.agenticai.core.billing.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.billing.commerce.CommerceProduct.ProductAvailability;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two ACP-shaped control-plane records' derived behavior: money stays in minor units internally
 * and only the display helpers produce a two-decimal major value, {@code isPastExpiry} is the lazy
 * READY -> EXPIRED trigger, and {@code purchasable} is the exact condition a shopping agent can buy
 * under.
 */
class CheckoutSessionAndProductTest {

    private static CheckoutSession session(CheckoutSessionStatus status, Instant expiresAt) {
        return new CheckoutSession(
                UUID.randomUUID(),
                "tenant_acme",
                UUID.randomUUID(),
                "sess-1",
                "agent",
                "+9199999",
                "Customer",
                "INR",
                49_900,
                "desc",
                "[]",
                status,
                "razorpay",
                "plink_1",
                "https://rzp.io/x",
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                expiresAt);
    }

    @Test
    void checkoutStatusWireValuesAreAcpLowercaseAndOnlyReadyIsPayable() {
        assertEquals(
                "not_ready_for_payment", CheckoutSessionStatus.NOT_READY_FOR_PAYMENT.wireValue());
        assertEquals("ready_for_payment", CheckoutSessionStatus.READY_FOR_PAYMENT.wireValue());
        assertEquals("completed", CheckoutSessionStatus.COMPLETED.wireValue());
        assertEquals("canceled", CheckoutSessionStatus.CANCELED.wireValue());
        assertEquals("expired", CheckoutSessionStatus.EXPIRED.wireValue());
        assertTrue(CheckoutSessionStatus.READY_FOR_PAYMENT.isPayable());
        for (CheckoutSessionStatus s : CheckoutSessionStatus.values()) {
            if (s != CheckoutSessionStatus.READY_FOR_PAYMENT) {
                assertFalse(s.isPayable(), s + " must not be payable");
            }
        }
    }

    @Test
    void amountMajorIsDisplayOnlyMinorToMajorConversion() {
        assertEquals(
                new BigDecimal("499.00"),
                session(CheckoutSessionStatus.READY_FOR_PAYMENT, null).amountMajor());
        CheckoutSession odd = session(CheckoutSessionStatus.READY_FOR_PAYMENT, null);
        CheckoutSession five =
                new CheckoutSession(
                        odd.id(),
                        odd.tenantId(),
                        odd.agentId(),
                        odd.sessionId(),
                        odd.channel(),
                        odd.customerPhone(),
                        odd.customerName(),
                        odd.currency(),
                        5,
                        odd.description(),
                        odd.lineItemsJson(),
                        odd.status(),
                        odd.paymentProvider(),
                        odd.gatewayRefId(),
                        odd.shortUrl(),
                        odd.paidAt(),
                        odd.paymentId(),
                        odd.reviewNote(),
                        odd.createdAt(),
                        odd.updatedAt(),
                        odd.expiresAt());
        assertEquals(new BigDecimal("0.05"), five.amountMajor());
    }

    @Test
    void onlyAReadySessionPastItsExpiryIsReportedExpired() {
        Instant past = Instant.now().minusSeconds(60);
        Instant future = Instant.now().plusSeconds(600);
        assertTrue(
                session(CheckoutSessionStatus.READY_FOR_PAYMENT, past).isPastExpiry(Instant.now()));
        assertFalse(
                session(CheckoutSessionStatus.READY_FOR_PAYMENT, future)
                        .isPastExpiry(Instant.now()));
        assertFalse(
                session(CheckoutSessionStatus.COMPLETED, past).isPastExpiry(Instant.now()),
                "a completed session never lazily expires");
        assertFalse(
                session(CheckoutSessionStatus.READY_FOR_PAYMENT, null).isPastExpiry(Instant.now()),
                "no expiry timestamp means no lazy expiry");
    }

    @Test
    void withStatusCopiesEveryOtherField() {
        CheckoutSession ready = session(CheckoutSessionStatus.READY_FOR_PAYMENT, null);
        CheckoutSession done = ready.withStatus(CheckoutSessionStatus.COMPLETED);
        assertEquals(CheckoutSessionStatus.COMPLETED, done.status());
        assertEquals(ready.id(), done.id());
        assertEquals(ready.tenantId(), done.tenantId());
        assertEquals(ready.gatewayRefId(), done.gatewayRefId());
        assertEquals(ready.amountPaise(), done.amountPaise());
    }

    @Test
    void theCompletedEventPinsItsWorkflowEventType() {
        assertEquals(
                "commerce.payment.received", CheckoutSessionCompletedEvent.WORKFLOW_EVENT_TYPE);
        CheckoutSessionCompletedEvent event =
                new CheckoutSessionCompletedEvent(session(CheckoutSessionStatus.COMPLETED, null));
        assertEquals(CheckoutSessionStatus.COMPLETED, event.session().status());
    }

    @Test
    void productAvailabilityWireValuesAndPurchasableRule() {
        assertEquals("in_stock", ProductAvailability.IN_STOCK.wireValue());
        assertEquals("out_of_stock", ProductAvailability.OUT_OF_STOCK.wireValue());
        assertEquals("preorder", ProductAvailability.PREORDER.wireValue());
        assertTrue(product(true, ProductAvailability.IN_STOCK).purchasable());
        assertFalse(
                product(false, ProductAvailability.IN_STOCK).purchasable(),
                "unpublished is not advertised to shopping agents");
        assertFalse(product(true, ProductAvailability.OUT_OF_STOCK).purchasable());
        assertFalse(product(true, ProductAvailability.PREORDER).purchasable());
    }

    @Test
    void productPriceMajorMirrorsTheSessionMoneyRule() {
        assertEquals(
                new BigDecimal("12.34"),
                product(true, ProductAvailability.IN_STOCK, 1_234).priceMajor());
    }

    private static CommerceProduct product(boolean published, ProductAvailability availability) {
        return product(published, availability, 999);
    }

    private static CommerceProduct product(
            boolean published, ProductAvailability availability, long price) {
        return new CommerceProduct(
                UUID.randomUUID(),
                "tenant_acme",
                "SKU-1",
                "Widget",
                "desc",
                "cat",
                price,
                "INR",
                availability,
                "https://img",
                "https://prod",
                published,
                Instant.now(),
                Instant.now());
    }
}
