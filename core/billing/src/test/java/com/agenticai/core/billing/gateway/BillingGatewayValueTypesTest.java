package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gateway-agnostic vocabulary and DTO records every {@code PaymentGatewayClient} speaks: the
 * lenient {@code fromWire} parsers the upgrade endpoint uses (null/blank defaults,
 * case-insensitive, empty on garbage), the event enum's full subscription lifecycle, and the record
 * shapes whose extra constructors default their added fields.
 */
class BillingGatewayValueTypesTest {

    @Test
    void currencyDefaultsToInrAndParsesLeniently() {
        assertEquals(Optional.of(BillingCurrency.INR), BillingCurrency.fromWire(null));
        assertEquals(Optional.of(BillingCurrency.INR), BillingCurrency.fromWire("  "));
        assertEquals(Optional.of(BillingCurrency.USD), BillingCurrency.fromWire("usd"));
        assertEquals(Optional.of(BillingCurrency.INR), BillingCurrency.fromWire(" Inr "));
        assertEquals(
                Optional.empty(),
                BillingCurrency.fromWire("EUR"),
                "an unrecognized value is the caller's 400, not a silent default");
    }

    @Test
    void periodDefaultsToMonthlyAndParsesLeniently() {
        assertEquals(Optional.of(BillingPeriod.MONTHLY), BillingPeriod.fromWire(null));
        assertEquals(Optional.of(BillingPeriod.MONTHLY), BillingPeriod.fromWire(" "));
        assertEquals(Optional.of(BillingPeriod.ANNUAL), BillingPeriod.fromWire("Annual"));
        assertEquals(Optional.empty(), BillingPeriod.fromWire("weekly"));
    }

    @Test
    void theEventEnumCoversTheFullSubscriptionLifecycle() {
        assertEquals(13, BillingEventType.values().length);
        assertEquals(
                BillingEventType.SUBSCRIPTION_CHARGED,
                BillingEventType.valueOf("SUBSCRIPTION_CHARGED"));
        assertEquals(
                BillingEventType.PAYMENT_LINK_PAID, BillingEventType.valueOf("PAYMENT_LINK_PAID"));
        assertEquals(
                BillingEventType.QR_CODE_PAYMENT_RECEIVED,
                BillingEventType.valueOf("QR_CODE_PAYMENT_RECEIVED"));
        assertEquals(BillingEventType.UNKNOWN, BillingEventType.valueOf("UNKNOWN"));
    }

    @Test
    void checkoutModesAndCapabilitiesAreTheAdvertisedVocabulary() {
        assertEquals(CheckoutMode.WIDGET, CheckoutMode.valueOf("WIDGET"));
        assertEquals(CheckoutMode.REDIRECT, CheckoutMode.valueOf("REDIRECT"));
        assertEquals(7, GatewayCapability.values().length);
        assertEquals(GatewayCapability.UPI_COLLECT, GatewayCapability.valueOf("UPI_COLLECT"));
        assertEquals(PaidTier.PREMIUM, PaidTier.valueOf("PREMIUM"));
        assertEquals(PaidTier.UNLIMITED, PaidTier.valueOf("UNLIMITED"));
    }

    @Test
    void billingEventConstructorsDefaultGatewayContextAndPayment() {
        BillingEvent minimal =
                new BillingEvent(
                        BillingEventType.SUBSCRIPTION_CHARGED, "sub_1", "subscription.charged");
        assertNull(minimal.gatewayName());
        assertNull(minimal.capturedPayment());
        BillingEvent named =
                new BillingEvent(
                        BillingEventType.SUBSCRIPTION_CHARGED,
                        "sub_1",
                        "subscription.charged",
                        "razorpay");
        assertEquals("razorpay", named.gatewayName());
        assertNull(named.capturedPayment());
        CapturedPaymentDetails payment = new CapturedPaymentDetails("pay_9", 49_900, "INR");
        BillingEvent full =
                new BillingEvent(
                        BillingEventType.SUBSCRIPTION_CHARGED,
                        "sub_1",
                        "subscription.charged",
                        "razorpay",
                        payment);
        assertEquals(payment, full.capturedPayment());
        assertEquals("pay_9", full.capturedPayment().paymentId());
        assertEquals(49_900, full.capturedPayment().amountMinor());
        assertEquals("INR", full.capturedPayment().currency());
    }

    @Test
    void gatewayOrderWidgetConstructorLeavesCheckoutUrlNull() {
        GatewayOrder widget = new GatewayOrder("order_1", "created", 10_000, "INR");
        assertNull(widget.checkoutUrl());
        GatewayOrder redirect =
                new GatewayOrder("order_2", "created", 10_000, "USD", "https://pay.example");
        assertEquals("https://pay.example", redirect.checkoutUrl());
        assertEquals("order_1", widget.orderId());
        assertEquals(10_000, widget.amountInSubunits());
    }

    @Test
    void theRemainingGatewayRecordsArePlainCarriers() {
        GatewayUpiQrCode qr = new GatewayUpiQrCode("qr_1", "https://img", "active", 5_000);
        assertEquals("qr_1", qr.id());
        assertEquals("https://img", qr.imageUrl());
        assertEquals("active", qr.status());
        assertEquals(5_000, qr.amountInSubunits());

        GatewayUpiCollectRequest collect =
                new GatewayUpiCollectRequest("plink_1", "https://rzp.io/x", "created");
        assertEquals("plink_1", collect.id());
        assertEquals("https://rzp.io/x", collect.shortUrl());
        assertEquals("created", collect.status());

        GatewaySubscription sub = new GatewaySubscription("sub_1", "https://rzp.io/sub", "created");
        assertEquals("sub_1", sub.id());
        assertEquals("https://rzp.io/sub", sub.checkoutUrl());

        GatewayQrCodeDetails details =
                new GatewayQrCodeDetails("qr_1", "closed", Map.of("tenant_id", "t-1"));
        assertEquals("closed", details.status());
        assertEquals("t-1", details.notes().get("tenant_id"));

        GatewayInfo info =
                new GatewayInfo(
                        "razorpay",
                        true,
                        true,
                        "rzp_test_key",
                        CheckoutMode.WIDGET,
                        Set.of(GatewayCapability.SUBSCRIPTIONS, GatewayCapability.UPI_QR));
        assertTrue(info.configured());
        assertTrue(info.isDefault());
        assertEquals("rzp_test_key", info.publicKeyId());
        assertEquals(
                Set.of(GatewayCapability.SUBSCRIPTIONS, GatewayCapability.UPI_QR),
                info.capabilities());
    }

    @Test
    void theGatewayExceptionsCarryMessageAndCause() {
        assertEquals("bad sig", new InvalidWebhookSignatureException("bad sig").getMessage());
        PaymentGatewayException e =
                new PaymentGatewayException("gateway down", new RuntimeException("io"));
        assertEquals("gateway down", e.getMessage());
        assertTrue(e.getCause() instanceof RuntimeException);
    }

    @Test
    void theDunningEventCarriesTenantAndAttemptCount() {
        UUID tenant = UUID.randomUUID();
        SubscriptionPaymentExhaustedEvent e =
                new SubscriptionPaymentExhaustedEvent(tenant, "acme", "sub_1", 3);
        assertEquals(tenant, e.tenantId());
        assertEquals("acme", e.tenantName());
        assertEquals("sub_1", e.gatewaySubscriptionId());
        assertEquals(3, e.failedAttempts());
    }
}
