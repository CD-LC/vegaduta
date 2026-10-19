package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnBillingEdition;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.annotation.JsonAlias;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gateway-NEUTRAL checkout API: one-off orders, server-side payment verification and the list
 * of gateways the console can offer. It depends only on {@link PaymentGatewayRegistry} and {@link
 * PaymentGatewayClient}'s own declarations ({@link GatewayCapability}, {@link CheckoutMode}) -
 * never on a concrete gateway - so Stripe, PhonePe or any future gateway is reachable here the
 * moment it is registered and advertises the capability.
 *
 * <p>Request/response field names are gateway-neutral ({@code orderId}, {@code paymentId}, {@code
 * signature}). The original Razorpay-named fields ({@code amountInPaise}, {@code razorpayOrderId}
 * ...) are still accepted through {@link JsonAlias}, and the response still carries {@code keyId},
 * so the existing browser checkout keeps working unchanged.
 *
 * <p>Razorpay-only surfaces (UPI collect links, UPI QR codes) stay in {@link
 * RazorpayCheckoutController}: they are India-UPI specific and gated by {@link
 * GatewayCapability#UPI_COLLECT}/{@link GatewayCapability#UPI_QR}.
 */
@RestController
@ConditionalOnBillingEdition
@Profile("security")
public class PaymentCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(PaymentCheckoutController.class);

    /**
     * Smallest chargeable amount in subunits (paise/cents) - the floor the Razorpay-only endpoint
     * always enforced.
     */
    static final long MIN_AMOUNT_IN_SUBUNITS = 100;

    public record GatewayListResponse(String defaultGateway, List<GatewayInfo> gateways) {}

    public record CreateOrderRequest(
            @JsonAlias("amountInPaise") long amountInSubunits,
            String currency,
            String receipt,
            /**
             * Optional; blank = the configured default gateway ({@code
             * app.billing.default-gateway}).
             */
            String gateway) {}

    public record CreateOrderResponse(
            String gateway,
            String orderId,
            long amount,
            String currency,
            /**
             * Kept for the existing Razorpay checkout component; identical to {@code publicKeyId}.
             */
            String keyId,
            String publicKeyId,
            CheckoutMode checkoutMode,
            /**
             * Hosted-page URL for {@link CheckoutMode#REDIRECT} gateways; {@code null} for widget
             * gateways.
             */
            String checkoutUrl) {}

    public record VerifyPaymentRequest(
            @JsonAlias("razorpayOrderId") String orderId,
            @JsonAlias("razorpayPaymentId") String paymentId,
            @JsonAlias("razorpaySignature") String signature,
            String gateway) {}

    public record VerifyPaymentResponse(boolean verified, String message) {}

    private final PaymentGatewayRegistry gatewayRegistry;

    public PaymentCheckoutController(PaymentGatewayRegistry gatewayRegistry) {
        this.gatewayRegistry = gatewayRegistry;
    }

    /**
     * Which gateways exist here, which are configured, and what each can do. Public values only -
     * no secrets.
     */
    @GetMapping("/api/billing/gateways")
    public GatewayListResponse gateways() {
        getTenantIdOrThrow();
        return new GatewayListResponse(
                gatewayRegistry.defaultGatewayName(), gatewayRegistry.describe());
    }

    @PostMapping("/api/billing/checkout/create-order")
    public CreateOrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        String tenantId = getTenantIdOrThrow();
        if (request.amountInSubunits() < MIN_AMOUNT_IN_SUBUNITS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount must be at least 100 in the currency's smallest unit (e.g. 100 paise ="
                            + " ₹1)");
        }
        BillingCurrency currency =
                BillingCurrency.fromWire(request.currency())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Currency must be INR or USD"));
        PaymentGatewayClient client =
                resolveCapable(request.gateway(), GatewayCapability.ONE_TIME_ORDERS);

        try {
            Map<String, String> notes = new HashMap<>();
            notes.put("tenant_id", tenantId);
            GatewayOrder order =
                    client.createTopUpOrder(
                            tenantId,
                            request.amountInSubunits(),
                            currency.name(),
                            request.receipt(),
                            notes);
            String publicKey = client.publicKeyId() == null ? "" : client.publicKeyId();
            return new CreateOrderResponse(
                    client.gatewayName(),
                    order.orderId(),
                    request.amountInSubunits(),
                    currency.name(),
                    publicKey,
                    publicKey,
                    client.checkoutMode(),
                    order.checkoutUrl());
        } catch (PaymentGatewayException e) {
            // The gateway's own error text can carry account/config detail; it goes to the
            // log, never to the caller.
            log.warn(
                    "Top-up order creation failed on gateway {} for tenant {}",
                    client.gatewayName(),
                    tenantId,
                    e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create order. Please try again later.");
        }
    }

    @PostMapping("/api/billing/checkout/verify-payment")
    public VerifyPaymentResponse verifyPayment(@RequestBody VerifyPaymentRequest request) {
        getTenantIdOrThrow();
        requirePresent(request.orderId(), "orderId");
        requirePresent(request.paymentId(), "paymentId");
        requirePresent(request.signature(), "signature");
        PaymentGatewayClient client =
                resolveCapable(request.gateway(), GatewayCapability.PAYMENT_VERIFICATION);

        try {
            client.verifyPaymentSignature(
                    request.orderId(), request.paymentId(), request.signature());
            return new VerifyPaymentResponse(true, "Payment verified successfully");
        } catch (InvalidWebhookSignatureException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Payment verification failed: " + e.getMessage());
        } catch (PaymentGatewayException e) {
            log.warn(
                    "Payment verification failed on gateway {} for order {}",
                    client.gatewayName(),
                    request.orderId(),
                    e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to verify payment. Please try again later.");
        }
    }

    /**
     * Resolves the requested (or default) gateway and insists it is configured and advertises the
     * capability. An unknown name is a 400 - never a silent fallback to another gateway, which
     * could misroute real money.
     */
    private PaymentGatewayClient resolveCapable(String gatewayName, GatewayCapability needed) {
        PaymentGatewayClient client;
        try {
            client = gatewayRegistry.resolve(gatewayName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown payment gateway: " + gatewayName);
        }
        if (!client.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment gateway '"
                            + client.gatewayName()
                            + "' is not configured in this deployment");
        }
        Set<GatewayCapability> capabilities = client.capabilities();
        if (!capabilities.contains(needed)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "Payment gateway '" + client.gatewayName() + "' does not support " + needed);
        }
        return client;
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private String getTenantIdOrThrow() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED,
                                        "No tenant context - authentication required"));
    }
}
