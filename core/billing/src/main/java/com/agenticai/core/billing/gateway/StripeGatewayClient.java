package com.agenticai.core.billing.gateway;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link PaymentGatewayClient} backed by Stripe's REST API directly (no stripe-java dependency -
 * the calls this platform needs are three small form-encoded POSTs plus webhook HMAC verification,
 * and keeping the client dependency-free mirrors {@link PhonePeGatewayClient}'s posture and avoids
 * pinning a new SDK to this module).
 *
 * <p><b>Configuration posture (credentials pending):</b> every property is blank-by-default in
 * {@code application.yml} and {@code deploy/.env.example} because no Stripe account exists yet.
 * Consequences are deliberate:
 *
 * <ul>
 *   <li>{@link #isConfigured} reports {@code false} on an empty {@code secret-key}, so {@code
 *       TenantUpgradeService} rejects a {@code gateway=stripe} upgrade with an actionable billing
 *       error instead of an opaque Stripe 401.
 *   <li>{@link #verifyWebhookSignature} fail-closes on an empty {@code webhook-secret}: Stripe's
 *       signed payload is {@code "{t}.{rawBody}"} HMACed with the secret, and an empty secret makes
 *       that signature publicly computable - a forged {@code invoice.paid} would otherwise flip a
 *       tenant to a paid plan for free. Missing secret or missing {@code Stripe-Signature} header
 *       both reject.
 *   <li>{@link #createSubscriptionWithTrial} resolves the bare plan name it receives ({@code
 *       "PREMIUM"}/{@code "UNLIMITED"} - the non-Razorpay contract {@code
 *       TenantUpgradeService#resolvePlanId} defines) through {@code
 *       app.billing.stripe.price-id-premium}/{@code -unlimited} and fails loudly naming the missing
 *       key - same posture as {@link RazorpayPlanCatalog}'s unconfigured-combination error.
 * </ul>
 *
 * <p><b>Checkout shape.</b> Stripe subscriptions with {@code payment_behavior=default_incomplete}
 * stay {@code incomplete} until the first invoice is paid; the invoice's {@code hosted_invoice_url}
 * is a Stripe-hosted page that takes the card, so it is returned as {@link
 * GatewaySubscription#checkoutUrl} - the redirect-style checkout PhonePe established for this
 * interface, with no client-side widget requirement (Elements integration can come later if the
 * product wants embedded checkout).
 *
 * <p><b>Webhook mapping</b> onto this app's existing {@link BillingEventType} vocabulary (the
 * lifecycle handler is gateway-agnostic):
 *
 * <ul>
 *   <li>{@code invoice.paid} with a subscription -> SUBSCRIPTION_CHARGED (carries the
 *       captured-payment details for invoicing)
 *   <li>{@code invoice.payment_failed} -> SUBSCRIPTION_PENDING (dunning)
 *   <li>{@code customer.subscription.created/updated} -> AUTHENTICATED / UPDATED
 *   <li>{@code customer.subscription.deleted} -> SUBSCRIPTION_CANCELLED
 *   <li>{@code customer.subscription.paused/resumed} -> PAUSED / RESUMED
 *   <li>anything else -> UNKNOWN (never guessed - same safe-default posture as {@code
 *       PhonePeGatewayClient#mapEventType})
 * </ul>
 *
 * <p>Not yet exercised against a real Stripe account - same honest-status note
 * RazorpayGatewayClient's header carries. The signature scheme and API shapes below follow Stripe's
 * published webhook-signature and Subscriptions documentation, but live verification is part of the
 * pending Stripe account setup, not assumed here.
 */
@Component
@Profile("security")
public class StripeGatewayClient implements PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(StripeGatewayClient.class);

    /** Stripe's own tolerance for the {@code t=} timestamp in Stripe-Signature. */
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    private final RestClient apiClient;
    private final ObjectMapper objectMapper = JsonMappers.plain();
    private final String secretKey;
    private final String publishableKey;
    private final String webhookSecret;
    private final String priceIdPremium;
    private final String priceIdUnlimited;

    public StripeGatewayClient(
            RestClient.Builder restClientBuilder,
            // Injected builder for the same reason PhonePeGatewayClient's
            // constructor documents: a hand-built RestClient bypasses Spring's
            // timeout configuration, which parks a checkout request forever on a
            // gateway outage.
            @Value("${app.billing.stripe.api-base-url:https://api.stripe.com}") String apiBaseUrl,
            @Value("${app.billing.stripe.secret-key:}") String secretKey,
            @Value("${app.billing.stripe.publishable-key:}") String publishableKey,
            @Value("${app.billing.stripe.webhook-secret:}") String webhookSecret,
            @Value("${app.billing.stripe.price-id-premium:}") String priceIdPremium,
            @Value("${app.billing.stripe.price-id-unlimited:}") String priceIdUnlimited) {
        this.apiClient = restClientBuilder.clone().baseUrl(apiBaseUrl).build();
        this.secretKey = secretKey;
        this.publishableKey = publishableKey;
        this.webhookSecret = webhookSecret;
        this.priceIdPremium = priceIdPremium;
        this.priceIdUnlimited = priceIdUnlimited;
    }

    /**
     * Truthful, not aspirational: subscriptions and usage add-ons are implemented end to end
     * (webhook-confirmed). One-off orders are NOT advertised until a Checkout Session payment flow
     * and its webhook crediting exist.
     */
    @Override
    public java.util.Set<GatewayCapability> capabilities() {
        return java.util.EnumSet.of(
                GatewayCapability.SUBSCRIPTIONS, GatewayCapability.USAGE_ADDONS);
    }

    @Override
    public CheckoutMode checkoutMode() {
        return CheckoutMode.REDIRECT;
    }

    @Override
    public String webhookSignatureHeader() {
        return "Stripe-Signature";
    }

    @Override
    public String gatewayName() {
        return "stripe";
    }

    @Override
    public String publicKeyId() {
        return publishableKey;
    }

    @Override
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    @Override
    public GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName) {
        if (!isConfigured()) {
            throw new PaymentGatewayException(
                    "Stripe is not configured in this deployment - set STRIPE_SECRET_KEY "
                            + "(app.billing.stripe.secret-key) to enable Stripe upgrades; until then pay via "
                            + "the default gateway or stay on the free tier.",
                    null);
        }
        String priceId = resolvePriceId(planId);
        try {
            // Customer first: Stripe's subscription needs a customer object,
            // and creating it explicitly lets us stamp tenant/name/email
            // metadata in one shot rather than relying on the subscription's
            // own customer auto-creation fields.
            MultiValueMap<String, String> customerForm = new LinkedMultiValueMap<>();
            if (customerEmail != null && !customerEmail.isBlank()) {
                customerForm.add("email", customerEmail);
            }
            if (customerName != null && !customerName.isBlank()) {
                customerForm.add("name", customerName);
            }
            customerForm.add("metadata[agentic_ai_tenant_id]", tenantId);
            JsonNode customer = postForm("/v1/customers", customerForm);
            String customerId = customer.path("id").asText(null);
            if (customerId == null || customerId.isBlank()) {
                throw new PaymentGatewayException("Stripe customer creation returned no id", null);
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("customer", customerId);
            form.add("items[0][price]", priceId);
            // default_incomplete: the subscription exists immediately but only
            // activates once its first invoice is paid via the hosted invoice
            // page we hand back as checkoutUrl - the "authorize payment method"
            // step the interface's contract expects.
            form.add("payment_behavior", "default_incomplete");
            form.add("collection_method", "charge_automatically");
            form.add("expand[]", "latest_invoice");
            form.add("metadata[agentic_ai_tenant_id]", tenantId);
            if (trialDays > 0) {
                form.add("trial_period_days", Integer.toString(trialDays));
            }
            JsonNode subscription = postForm("/v1/subscriptions", form);
            String subscriptionId = subscription.path("id").asText(null);
            String status = subscription.path("status").asText("incomplete");
            String checkoutUrl =
                    subscription.path("latest_invoice").path("hosted_invoice_url").asText(null);
            if (subscriptionId == null || subscriptionId.isBlank()) {
                throw new PaymentGatewayException(
                        "Stripe subscription creation returned no id", null);
            }
            return new GatewaySubscription(subscriptionId, checkoutUrl, status);
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Could not create Stripe subscription: " + e.getMessage(), e);
        }
    }

    /**
     * The bare plan name {@code TenantUpgradeService} passes to non-Razorpay gateways resolved to a
     * Stripe Price id. Fails loudly naming the missing configuration property - never sends an
     * empty {@code price} to Stripe the way the pre-B1 Razorpay path once sent an empty plan id.
     */
    private String resolvePriceId(String planId) {
        String configured =
                switch (planId == null ? "" : planId.trim().toUpperCase()) {
                    case "PREMIUM" -> priceIdPremium;
                    case "UNLIMITED" -> priceIdUnlimited;
                    default -> null;
                };
        if (configured == null || configured.isBlank()) {
            throw new PaymentGatewayException(
                    "No Stripe Price id configured for plan '"
                            + planId
                            + "' - set "
                            + ("PREMIUM".equalsIgnoreCase(planId)
                                    ? "STRIPE_PRICE_ID_PREMIUM (app.billing.stripe.price-id-premium)"
                                    : "STRIPE_PRICE_ID_UNLIMITED (app.billing.stripe.price-id-unlimited)")
                            + " to a Price created in the Stripe dashboard.",
                    null);
        }
        return configured;
    }

    /**
     * Stripe's {@code Stripe-Signature} scheme: the header is {@code
     * "t=<unixSeconds>,v1=<hexHmac>[,v1=...]"} and the signed payload is the literal string {@code
     * "<t>.<rawBody>"}. Verified with a constant-time compare plus a timestamp tolerance so a
     * replayed-but-once-valid signature outside the window still rejects.
     */
    @Override
    public void verifyWebhookSignature(String rawBody, String signatureHeader) {
        // Fail closed, same posture as RazorpayGatewayClient: an empty
        // webhook secret makes the v1 HMAC publicly computable.
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Stripe webhook secret is not configured - set STRIPE_WEBHOOK_SECRET "
                            + "(app.billing.stripe.webhook-secret); refusing every delivery until then");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Stripe webhook request carried no Stripe-Signature header");
        }
        Long timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // Malformed timestamp - left null, rejected below.
                }
            } else if ("v1".equals(key)) {
                signature = value;
            }
        }
        if (timestamp == null || signature == null || signature.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Stripe-Signature header carried no t=/v1= pair");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > SIGNATURE_TOLERANCE_SECONDS) {
            throw new InvalidWebhookSignatureException(
                    "Stripe webhook timestamp outside the "
                            + SIGNATURE_TOLERANCE_SECONDS
                            + "s tolerance - likely a replay");
        }
        String expected = hmacSha256Hex(webhookSecret, timestamp + "." + rawBody);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSignatureException("Stripe webhook signature did not match");
        }
    }

    @Override
    public BillingEvent parseWebhookEvent(String rawBody) {
        JsonNode json;
        try {
            json = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            // Same posture as PhonePeGatewayClient's parse failure: signature
            // verification already passed, so Stripe really sent this and the
            // charge it describes is being dropped - logged loud, never silent.
            log.error("Could not parse Stripe webhook body - dropping the event as UNKNOWN", e);
            return new BillingEvent(BillingEventType.UNKNOWN, null, "", gatewayName());
        }
        String rawEventName = json.path("type").asText("");
        JsonNode object = json.path("data").path("object");
        // Subscription id: customer.subscription.* events ARE the object;
        // invoice.* events reference it via the invoice's parent (API
        // 2025-era shape) with the legacy `subscription` field as fallback.
        String subscriptionId = null;
        if (rawEventName.startsWith("customer.subscription.")) {
            subscriptionId = object.path("id").asText(null);
        } else if (rawEventName.startsWith("invoice.")) {
            subscriptionId =
                    object.path("parent")
                            .path("subscription_details")
                            .path("subscription")
                            .asText(null);
            if (subscriptionId == null) {
                subscriptionId = object.path("subscription").asText(null);
            }
        }
        if (subscriptionId != null && subscriptionId.isBlank()) {
            subscriptionId = null;
        }
        CapturedPaymentDetails capturedPayment = null;
        if ("invoice.paid".equals(rawEventName) && subscriptionId != null) {
            String paymentRef = object.path("id").asText(null);
            long amountMinor = object.path("amount_paid").asLong(-1L);
            String currency = object.path("currency").asText(null);
            if (paymentRef != null && !paymentRef.isBlank() && amountMinor >= 0) {
                capturedPayment =
                        new CapturedPaymentDetails(
                                paymentRef,
                                amountMinor,
                                currency == null ? null : currency.toUpperCase());
            }
        }
        return new BillingEvent(
                mapEventType(rawEventName),
                subscriptionId,
                rawEventName,
                gatewayName(),
                capturedPayment);
    }

    private static BillingEventType mapEventType(String rawEventName) {
        return switch (rawEventName) {
            case "customer.subscription.created" -> BillingEventType.SUBSCRIPTION_AUTHENTICATED;
            case "customer.subscription.updated" -> BillingEventType.SUBSCRIPTION_UPDATED;
            case "customer.subscription.deleted" -> BillingEventType.SUBSCRIPTION_CANCELLED;
            case "customer.subscription.paused" -> BillingEventType.SUBSCRIPTION_PAUSED;
            case "customer.subscription.resumed" -> BillingEventType.SUBSCRIPTION_RESUMED;
            case "invoice.paid" -> BillingEventType.SUBSCRIPTION_CHARGED;
            case "invoice.payment_failed" -> BillingEventType.SUBSCRIPTION_PENDING;
            default -> BillingEventType.UNKNOWN;
        };
    }

    /**
     * The Stripe counterpart of Razorpay's Addon: a pending InvoiceItem scoped to the subscription,
     * which Stripe folds into the next recurring invoice. {@code unit_amount} is an integer in the
     * currency's smallest unit - the interface's {@code amountInSubunits} contract maps onto it
     * exactly, no conversion.
     */
    @Override
    public void createOverageAddon(
            String gatewaySubscriptionId,
            String description,
            long amountInSubunits,
            String currency) {
        if (!isConfigured()) {
            throw new PaymentGatewayException(
                    "Stripe is not configured in this deployment - cannot report an overage charge.",
                    null);
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("subscription", gatewaySubscriptionId);
            form.add("description", description);
            form.add("quantity", "1");
            form.add("unit_amount", Long.toString(amountInSubunits));
            form.add("currency", currency == null ? "inr" : currency.toLowerCase());
            postForm("/v1/invoiceitems", form);
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Could not create Stripe overage invoice item: " + e.getMessage(), e);
        }
    }

    @Override
    public String cancelSubscription(String gatewaySubscriptionId, boolean cancelAtCycleEnd) {
        if (!isConfigured()) {
            throw new PaymentGatewayException(
                    "Stripe is not configured in this deployment - cannot cancel a Stripe subscription.",
                    null);
        }
        try {
            if (cancelAtCycleEnd) {
                // POST cancel_at_period_end=true - the subscription stays
                // active until cycle end and customer.subscription.deleted
                // fires then (same deferred-downgrade contract Razorpay's
                // cancel_at_cycle_end=1 already establishes for the tenancy
                // side).
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("cancel_at_period_end", "true");
                JsonNode subscription =
                        postForm("/v1/subscriptions/" + gatewaySubscriptionId, form);
                return subscription.path("status").asText("active");
            }
            JsonNode deleted =
                    apiClient
                            .delete()
                            .uri("/v1/subscriptions/" + gatewaySubscriptionId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                            .retrieve()
                            .body(JsonNode.class);
            return deleted != null ? deleted.path("status").asText("canceled") : "canceled";
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Could not cancel Stripe subscription: " + e.getMessage(), e);
        }
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> form) {
        JsonNode response =
                apiClient
                        .post()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null) {
            throw new PaymentGatewayException(
                    "Stripe returned an empty response for " + path, null);
        }
        return response;
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new InvalidWebhookSignatureException(
                    "Could not compute Stripe webhook signature: " + e.getMessage());
        }
    }
}
