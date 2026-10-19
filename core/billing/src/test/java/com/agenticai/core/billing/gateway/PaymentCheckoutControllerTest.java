package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-unit convention (see RazorpayCheckoutControllerTest): the controller is built directly. No
 * Mockito - the gateways are tiny hand-written stubs, which is also the point of the design: the
 * controller knows nothing about Razorpay, so a stub "stripe" and a stub "razorpay" are
 * interchangeable behind the registry.
 */
class PaymentCheckoutControllerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    /** Gateway-side error text that must never be echoed to the browser. */
    private static final String GATEWAY_SECRET_DETAIL =
            "HTTP 401 from https://api.widgetpay.example/v1/orders (key_id rzp_live_abc)";

    /** A widget gateway (Razorpay-shaped): orders + verification. */
    static class WidgetGateway implements PaymentGatewayClient {
        String lastVerified;
        boolean signatureOk = true;
        boolean gatewayDown = false;

        @Override
        public String gatewayName() {
            return "widgetpay";
        }

        @Override
        public String publicKeyId() {
            return "pk_widget";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public CheckoutMode checkoutMode() {
            return CheckoutMode.WIDGET;
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            return EnumSet.of(
                    GatewayCapability.ONE_TIME_ORDERS, GatewayCapability.PAYMENT_VERIFICATION);
        }

        @Override
        public GatewaySubscription createSubscriptionWithTrial(
                String t, String p, int d, String e, String n) {
            return null;
        }

        @Override
        public void verifyWebhookSignature(String rawBody, String signatureHeader) {}

        @Override
        public BillingEvent parseWebhookEvent(String rawBody) {
            return null;
        }

        @Override
        public void createOverageAddon(String s, String d, long a, String c) {}

        @Override
        public GatewayOrder createTopUpOrder(
                String tenantId,
                long amount,
                String currency,
                String receipt,
                Map<String, String> notes) {
            assertEquals(TENANT_ID, notes.get("tenant_id"));
            if (gatewayDown) {
                throw new PaymentGatewayException(GATEWAY_SECRET_DETAIL, null);
            }
            return new GatewayOrder("order_1", "created", amount, currency);
        }

        @Override
        public void verifyPaymentSignature(
                String orderId, String paymentId, String signatureHeader) {
            if (gatewayDown) {
                throw new PaymentGatewayException(GATEWAY_SECRET_DETAIL, null);
            }
            if (!signatureOk) {
                throw new InvalidWebhookSignatureException("bad signature");
            }
            lastVerified = orderId + "|" + paymentId + "|" + signatureHeader;
        }
    }

    /** A redirect gateway (Stripe-shaped): hosted page, subscriptions only here. */
    static class RedirectGateway implements PaymentGatewayClient {
        boolean configured = true;

        @Override
        public String gatewayName() {
            return "redirectpay";
        }

        @Override
        public String publicKeyId() {
            return "";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            return EnumSet.of(GatewayCapability.SUBSCRIPTIONS);
        }

        @Override
        public GatewaySubscription createSubscriptionWithTrial(
                String t, String p, int d, String e, String n) {
            return null;
        }

        @Override
        public void verifyWebhookSignature(String rawBody, String signatureHeader) {}

        @Override
        public BillingEvent parseWebhookEvent(String rawBody) {
            return null;
        }

        @Override
        public void createOverageAddon(String s, String d, long a, String c) {}
    }

    private WidgetGateway widget;
    private RedirectGateway redirect;
    private PaymentCheckoutController controller;

    @BeforeEach
    void setUp() {
        widget = new WidgetGateway();
        redirect = new RedirectGateway();
        controller =
                new PaymentCheckoutController(
                        new PaymentGatewayRegistry(List.of(widget, redirect), "widgetpay"));
        TenantContext.set(TenantContext.of(TENANT_ID, "user@acme.example", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static PaymentCheckoutController.CreateOrderRequest order(
            long amount, String currency, String gateway) {
        return new PaymentCheckoutController.CreateOrderRequest(
                amount, currency, "rcpt_1", gateway);
    }

    private static HttpStatus statusOf(ResponseStatusException e) {
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    // --- gateway list ------------------------------------------------------------

    @Test
    void gatewaysListsEveryRegisteredGatewayWithItsCapabilitiesAndTheDefault() {
        PaymentCheckoutController.GatewayListResponse response = controller.gateways();
        assertEquals("widgetpay", response.defaultGateway());
        assertEquals(2, response.gateways().size());
        GatewayInfo w = response.gateways().get(0);
        assertEquals("widgetpay", w.name());
        assertTrue(w.isDefault());
        assertTrue(w.configured());
        assertEquals("pk_widget", w.publicKeyId());
        assertEquals(CheckoutMode.WIDGET, w.checkoutMode());
        assertTrue(w.capabilities().contains(GatewayCapability.ONE_TIME_ORDERS));
        GatewayInfo r = response.gateways().get(1);
        assertFalse(r.isDefault());
        assertEquals(CheckoutMode.REDIRECT, r.checkoutMode());
    }

    @Test
    void gatewaysRequiresAuthentication() {
        TenantContext.clear();
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                statusOf(assertThrows(ResponseStatusException.class, controller::gateways)));
    }

    // --- create-order ------------------------------------------------------------

    @Test
    void createOrderUsesTheDefaultGatewayAndKeepsTheLegacyKeyIdField() {
        PaymentCheckoutController.CreateOrderResponse response =
                controller.createOrder(order(5000, "INR", null));
        assertEquals("widgetpay", response.gateway());
        assertEquals("order_1", response.orderId());
        assertEquals(5000, response.amount());
        assertEquals("INR", response.currency());
        assertEquals("pk_widget", response.keyId());
        assertEquals("pk_widget", response.publicKeyId());
        assertEquals(CheckoutMode.WIDGET, response.checkoutMode());
        assertNull(response.checkoutUrl());
    }

    @Test
    void createOrderRejectsAnAmountBelowTheMinimum() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class,
                                () -> controller.createOrder(order(99, "INR", null)))));
    }

    @Test
    void createOrderRejectsAnUnsupportedCurrency() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class,
                                () -> controller.createOrder(order(500, "EUR", null)))));
    }

    @Test
    void createOrderRejectsAnUnknownGatewayInsteadOfFallingBack() {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createOrder(order(500, "INR", "nope")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(e));
    }

    @Test
    void createOrderOnAGatewayWithoutThatCapabilityIs501() {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createOrder(order(500, "INR", "redirectpay")));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, statusOf(e));
    }

    @Test
    void createOrderOnAnUnconfiguredGatewayIs503() {
        redirect.configured = false;
        // 503 wins over 501: an unconfigured gateway is reported as such before its capabilities
        // are consulted.
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createOrder(order(500, "INR", "redirectpay")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf(e));
    }

    @Test
    void createOrderRequiresAuthentication() {
        TenantContext.clear();
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                statusOf(
                        assertThrows(
                                ResponseStatusException.class,
                                () -> controller.createOrder(order(500, "INR", null)))));
    }

    @Test
    void createOrderGatewayFailureIs500WithAGenericReasonNotTheGatewaysText() {
        widget.gatewayDown = true;
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createOrder(order(500, "INR", null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusOf(e));
        assertEquals("Failed to create order. Please try again later.", e.getReason());
        assertFalse(String.valueOf(e.getReason()).contains("rzp_live_abc"));
        assertFalse(String.valueOf(e.getReason()).contains("widgetpay.example"));
    }

    // --- verify-payment ----------------------------------------------------------

    @Test
    void verifyPaymentGatewayFailureIs500WithAGenericReasonNotTheGatewaysText() {
        widget.gatewayDown = true;
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.verifyPayment(
                                        new PaymentCheckoutController.VerifyPaymentRequest(
                                                "order_1", "pay_1", "sig", null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusOf(e));
        assertEquals("Failed to verify payment. Please try again later.", e.getReason());
        assertFalse(String.valueOf(e.getReason()).contains("rzp_live_abc"));
    }

    @Test
    void verifyPaymentDelegatesToTheResolvedGateway() {
        PaymentCheckoutController.VerifyPaymentResponse response =
                controller.verifyPayment(
                        new PaymentCheckoutController.VerifyPaymentRequest(
                                "order_1", "pay_1", "sig", null));
        assertTrue(response.verified());
        assertEquals("order_1|pay_1|sig", widget.lastVerified);
    }

    @Test
    void verifyPaymentRejectsMissingFields() {
        for (var bad :
                List.of(
                        new PaymentCheckoutController.VerifyPaymentRequest(
                                null, "pay_1", "sig", null),
                        new PaymentCheckoutController.VerifyPaymentRequest(
                                "order_1", " ", "sig", null),
                        new PaymentCheckoutController.VerifyPaymentRequest(
                                "order_1", "pay_1", "", null))) {
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    statusOf(
                            assertThrows(
                                    ResponseStatusException.class,
                                    () -> controller.verifyPayment(bad))));
        }
    }

    @Test
    void verifyPaymentWithABadSignatureIs400() {
        widget.signatureOk = false;
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.verifyPayment(
                                        new PaymentCheckoutController.VerifyPaymentRequest(
                                                "o", "p", "s", null)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(e));
    }

    @Test
    void verifyPaymentOnAGatewayWithoutVerificationIs501() {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.verifyPayment(
                                        new PaymentCheckoutController.VerifyPaymentRequest(
                                                "o", "p", "s", "redirectpay")));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, statusOf(e));
    }
}
