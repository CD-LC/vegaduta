package com.agenticai.core.billing.gateway;

import com.razorpay.Order;
import com.razorpay.RazorpayException;
import com.razorpay.Subscription;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentGatewayClient} backed by Razorpay's Java SDK ({@code com.razorpay:razorpay-java}).
 * See that interface's javadoc for why there is no separate customer-creation step here.
 *
 * <p><b>Verified against the actual SDK/API before writing this class</b> (not guessed from general
 * payment-gateway-shaped assumptions): confirmed method signatures for {@code
 * subscriptions.create}, {@code subscriptions.createAddon}, and {@code
 * Utils.verifyWebhookSignature} directly against razorpay/razorpay-java's source and {@code
 * documents/*.md} on GitHub, and separately re-checked {@code createSubscriptionWithTrial}'s exact
 * request body against Razorpay's own Create Subscription API reference page during Stage 3 (caught
 * and fixed a real bug this way - see that method's inline comment on the {@code notify_info} field
 * it used to send). Still genuinely untested against a live Razorpay account, though - this class
 * needs a real compile pass plus Stage 3's actual signup-to-checkout walkthrough before it's
 * proven, same as every other integration in this codebase that this sandbox can't execute
 * directly.
 *
 * <p>Gated behind the {@code security} profile, same as {@code KeycloakAdminClient} - billing is
 * meaningless without authenticated, tenant-scoped requests in the first place.
 */
@Component
@Profile("security")
public class RazorpayGatewayClient implements PaymentGatewayClient {

    // Razorpay subscriptions require a finite total_count (billing cycles)
    // - there is no true "no end date" option, unlike Stripe's open-ended
    // subscriptions. 120 monthly cycles (10 years) stands in for
    // "effectively unlimited until cancelled"; cancellation (Stage 4) ends
    // it long before this would ever matter in practice.
    private static final int EFFECTIVELY_UNLIMITED_BILLING_CYCLES = 120;

    // How long a customer has to complete Razorpay's authorization step
    // (Standard Checkout) before the subscription itself expires
    // (Razorpay's own "expire_by" enforcement) - deliberately the same
    // window AbandonedSignupCleanupJob (Stage 3) uses to roll back a tenant
    // that never finishes checkout, so the two limits agree from the
    // customer's perspective: this window closing is also roughly when
    // their whole signup gets cleaned up app-side. Not shared as one config
    // value on purpose - see AbandonedSignupCleanupJob's javadoc.
    private static final long AUTHORIZATION_WINDOW_HOURS = 48;

    // UPI-collect follow-up - how long a single-use UPI QR code
    // (createUpiQrCode) stays scannable before Razorpay auto-closes it.
    // Razorpay's QR Code API documents a 2-minute-to-2-hour range for
    // close_by on single_use codes; 30 minutes mirrors a typical checkout
    // session without being so long an abandoned code lingers.
    private static final long UPI_QR_CLOSE_BY_MINUTES = 30;

    // Built lazily (see client()) rather than in the constructor: the bean
    // must construct cleanly under the security profile with NO Razorpay
    // credentials configured at all - the signup comp-grant and free-mode
    // paths have to boot without a payment gateway existing - while any
    // actual API call with blank keys fails with a clear, actionable error.
    private volatile com.razorpay.RazorpayClient client;
    private final String keyId;
    private final String keySecret;
    private final RazorpayPlanCatalog planCatalog;
    private final RazorpaySignatureVerifier signatureVerifier;

    /**
     * Without a plan catalog: plan ids fall through to the "nothing configured" error. Used by
     * tests.
     */
    public RazorpayGatewayClient(String keyId, String keySecret, String webhookSecret) {
        this(keyId, keySecret, webhookSecret, new RazorpayPlanCatalog());
    }

    @Autowired
    public RazorpayGatewayClient(
            @Value("${app.billing.razorpay.key-id}") String keyId,
            @Value("${app.billing.razorpay.key-secret}") String keySecret,
            @Value("${app.billing.razorpay.webhook-secret}") String webhookSecret,
            RazorpayPlanCatalog planCatalog) {
        this.keyId = keyId;
        // Kept (was previously only passed to the SDK client) because P2's
        // verifyPaymentSignature HMACs order_id|payment_id with the KEY SECRET
        // (not the webhook secret) - Razorpay's Utils.verifyPaymentSignature.
        this.keySecret = keySecret;
        this.planCatalog = planCatalog;
        this.signatureVerifier = new RazorpaySignatureVerifier(keySecret, webhookSecret);
    }

    /**
     * The lazily-initialized Razorpay SDK client - throws a clear {@link PaymentGatewayException}
     * rather than constructing a client with blank credentials (which would fail as an opaque
     * gateway 401 on the first real call, looking like a Razorpay problem instead of a
     * missing-configuration problem).
     */
    private com.razorpay.RazorpayClient client() {
        com.razorpay.RazorpayClient current = client;
        if (current != null) {
            return current;
        }
        if (!isConfigured()) {
            throw new PaymentGatewayException(
                    "Razorpay is not configured in this deployment - set RAZORPAY_KEY_ID and "
                            + "RAZORPAY_KEY_SECRET (app.billing.razorpay.key-id/key-secret) to enable "
                            + "paid upgrades; until then every tenant stays on the free tier.",
                    null);
        }
        synchronized (this) {
            if (client == null) {
                try {
                    client = new com.razorpay.RazorpayClient(keyId, keySecret);
                } catch (RazorpayException e) {
                    throw new PaymentGatewayException(
                            "Could not initialize the Razorpay client - check RAZORPAY_KEY_ID/RAZORPAY_KEY_SECRET",
                            e);
                }
            }
            return client;
        }
    }

    @Override
    public boolean isConfigured() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    @Override
    public java.util.Set<GatewayCapability> capabilities() {
        return java.util.EnumSet.of(
                GatewayCapability.SUBSCRIPTIONS,
                GatewayCapability.ONE_TIME_ORDERS,
                GatewayCapability.PAYMENT_VERIFICATION,
                GatewayCapability.DISCOUNT_OFFERS,
                GatewayCapability.USAGE_ADDONS,
                GatewayCapability.UPI_COLLECT,
                GatewayCapability.UPI_QR);
    }

    /**
     * checkout.js opens against an order/subscription id + the Key ID; the browser callback is
     * signature-verified.
     */
    @Override
    public CheckoutMode checkoutMode() {
        return CheckoutMode.WIDGET;
    }

    @Override
    public String webhookSignatureHeader() {
        return "X-Razorpay-Signature";
    }

    @Override
    public String webhookEventIdHeader() {
        return "X-Razorpay-Event-Id";
    }

    @Override
    public String gatewayName() {
        return "razorpay";
    }

    @Override
    public String publicKeyId() {
        return keyId;
    }

    @Override
    public java.util.Set<BillingCurrency> supportedCurrencies() {
        return java.util.EnumSet.allOf(BillingCurrency.class);
    }

    @Override
    public java.util.Set<BillingPeriod> supportedPeriods() {
        return java.util.EnumSet.allOf(BillingPeriod.class);
    }

    /**
     * Currencies whose monthly plans (or the legacy INR keys) are configured, see {@link
     * RazorpayPlanCatalog#availableCurrencies}.
     */
    @Override
    public java.util.List<BillingCurrency> offeredCurrencies() {
        return planCatalog.availableCurrencies();
    }

    @Override
    public java.util.List<BillingCurrency> annualCurrencies() {
        return planCatalog.currenciesWithDedicatedPlans(BillingPeriod.ANNUAL);
    }

    /**
     * A dashboard Plan id per (tier, currency, period), see {@link
     * RazorpayPlanCatalog#resolvePlanId}.
     */
    @Override
    public String resolvePlanId(PaidTier tier, BillingCurrency currency, BillingPeriod period) {
        return planCatalog.resolvePlanId(tier, currency, period);
    }

    @Override
    public boolean supportsCoupons() {
        return true;
    }

    /** {@code app.billing.razorpay.offers.*}, see {@link RazorpayPlanCatalog#offerIdFor}. */
    @Override
    public Optional<String> offerIdFor(String couponCode) {
        return planCatalog.offerIdFor(couponCode);
    }

    @Override
    public GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName) {
        return createSubscriptionWithTrial(
                tenantId, planId, trialDays, customerEmail, customerName, null);
    }

    // PRICING-MONETIZATION-AUDIT-2026-07-31 D4 - the offer-aware overload.
    // offer_id is one of the Create Subscription endpoint's documented
    // request parameters (see the parameter-list note further down, recorded
    // when notify_info was caught and removed) - it was just never sent
    // before this. A null/blank offerId omits the field entirely, byte-for-
    // byte identical to the pre-D4 request body.
    @Override
    public GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName,
            String offerId) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", planId);
            request.put("total_count", EFFECTIVELY_UNLIMITED_BILLING_CYCLES);
            request.put("quantity", 1);
            request.put("customer_notify", true);
            long trialEndEpochSeconds =
                    Instant.now().plus(trialDays, ChronoUnit.DAYS).getEpochSecond();
            request.put("start_at", trialEndEpochSeconds);
            request.put(
                    "expire_by",
                    Instant.now()
                            .plus(AUTHORIZATION_WINDOW_HOURS, ChronoUnit.HOURS)
                            .getEpochSecond());
            if (offerId != null && !offerId.isBlank()) {
                request.put("offer_id", offerId.trim());
            }

            // NOTE: an earlier draft of this method also sent a top-level
            // "notify_info": { "notify_email": ... } field, modeled on
            // Payment Links' API. Confirmed against Razorpay's actual
            // Create Subscription API reference before this shipped: that
            // endpoint's documented request parameters are only plan_id,
            // total_count, quantity, start_at, expire_by, customer_notify,
            // addons, offer_id, notes - and its own documented error list
            // includes "{any extra field} is/are not required and should
            // not be sent" (a 400 that would have broken every signup).
            // There is no functional customer-email parameter on this
            // endpoint at all (unlike Stripe Checkout Sessions'
            // customer_email) - customerEmail is recorded in notes below
            // purely for support/debugging reference, not passed anywhere
            // Razorpay treats as functional.
            JSONObject notes = new JSONObject();
            notes.put("agentic_ai_tenant_id", tenantId);
            notes.put("agentic_ai_customer_name", customerName);
            notes.put("agentic_ai_customer_email", customerEmail);
            request.put("notes", notes);

            Subscription subscription = client().subscriptions.create(request);
            String id = subscription.get("id");
            String checkoutUrl = subscription.get("short_url");
            String status = subscription.get("status");
            return new GatewaySubscription(id, checkoutUrl, status);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not create Razorpay subscription: " + e.getMessage(), e);
        }
    }

    // UPI-collect follow-up (see GatewayUpiCollectRequest's javadoc for the
    // full "why not the deprecated manual-VPA Collect flow" reasoning) -
    // creates a UPI Payment Link the customer completes via their own UPI
    // app's Intent flow. Confirmed against Razorpay's actual Create a UPI
    // Payment Link API reference before this shipped: POST /v1/payment_links
    // with upi_link: true, currency INR-only, customer/notify/notes/
    // description/expire_by all optional. Uses the SDK's PaymentLinkClient
    // (client.paymentLink), a Razorpay-java 1.4.9 class distinct from
    // OrderClient/SubscriptionClient above but the same Entity#get(String)
    // shape.
    @Override
    public GatewayUpiCollectRequest createUpiCollectRequest(
            String tenantId,
            long amountInSubunits,
            String description,
            String customerName,
            String customerContact,
            String customerEmail,
            java.util.Map<String, String> notes) {
        return createUpiCollectRequest(
                tenantId,
                amountInSubunits,
                description,
                customerName,
                customerContact,
                customerEmail,
                notes,
                null);
    }

    // expire_by variant - the checkout-session tool's session expiry is now
    // passed through so the hosted link stops being payable when the app-side
    // session expires (closes the "expire_by" half of the carried-forward
    // Razorpay follow-up; package-info's known-limits list updated to match).
    @Override
    public GatewayUpiCollectRequest createUpiCollectRequest(
            String tenantId,
            long amountInSubunits,
            String description,
            String customerName,
            String customerContact,
            String customerEmail,
            java.util.Map<String, String> notes,
            Instant expireBy) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountInSubunits);
            request.put("currency", "INR"); // UPI is INR-only
            request.put("upi_link", true);
            if (description != null) {
                request.put("description", description);
            }
            // expire_by is a documented optional Create Payment Link parameter
            // (epoch seconds); null omits the field entirely, byte-for-byte
            // identical to the pre-expiry request body.
            if (expireBy != null) {
                request.put("expire_by", expireBy.getEpochSecond());
            }
            if (customerName != null || customerContact != null || customerEmail != null) {
                JSONObject customer = new JSONObject();
                if (customerName != null) {
                    customer.put("name", customerName);
                }
                if (customerContact != null) {
                    customer.put("contact", customerContact);
                }
                if (customerEmail != null) {
                    customer.put("email", customerEmail);
                }
                request.put("customer", customer);
            }
            JSONObject noteJson = new JSONObject();
            noteJson.put("agentic_ai_tenant_id", tenantId);
            if (notes != null) {
                notes.forEach(noteJson::put);
            }
            request.put("notes", noteJson);

            com.razorpay.PaymentLink paymentLink = client().paymentLink.create(request);
            String id = paymentLink.get("id");
            String shortUrl = paymentLink.get("short_url");
            String status = paymentLink.get("status");
            return new GatewayUpiCollectRequest(id, shortUrl, status);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not create Razorpay UPI payment link: " + e.getMessage(), e);
        }
    }

    // UPI-collect follow-up - Razorpay QR Code API
    // (razorpay.com/docs/api/qr-codes/), confirmed against the actual Create
    // a QR Code reference: POST /v1/payments/qr_codes, type must be
    // "upi_qr" for a UPI-scannable code. usage "single_use" + fixed_amount
    // mirrors createTopUpOrder's "one order, one charge" semantics (as
    // opposed to "multiple_use", a standing reusable code that accepts any
    // number of payments) - close_by bounds how long an abandoned code
    // stays scannable, same reasoning AUTHORIZATION_WINDOW_HOURS documents
    // above for subscriptions. Uses the SDK's QrCodeClient (client.qrCode).
    @Override
    public GatewayUpiQrCode createUpiQrCode(
            String tenantId,
            long amountInSubunits,
            String name,
            String description,
            java.util.Map<String, String> notes) {
        try {
            JSONObject request = new JSONObject();
            request.put("type", "upi_qr");
            request.put("usage", "single_use");
            request.put("fixed_amount", true);
            request.put("payment_amount", amountInSubunits);
            if (name != null) {
                request.put("name", name);
            }
            if (description != null) {
                request.put("description", description);
            }
            request.put(
                    "close_by",
                    Instant.now()
                            .plus(UPI_QR_CLOSE_BY_MINUTES, ChronoUnit.MINUTES)
                            .getEpochSecond());
            JSONObject noteJson = new JSONObject();
            noteJson.put("agentic_ai_tenant_id", tenantId);
            if (notes != null) {
                notes.forEach(noteJson::put);
            }
            request.put("notes", noteJson);

            com.razorpay.QrCode qrCode = client().qrCode.create(request);
            String id = qrCode.get("id");
            String imageUrl = qrCode.get("image_url");
            String status = qrCode.get("status");
            return new GatewayUpiQrCode(id, imageUrl, status, amountInSubunits);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not create Razorpay UPI QR code: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeUpiQrCode(String qrCodeId) {
        try {
            client().qrCode.close(qrCodeId);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not close Razorpay UPI QR code: " + e.getMessage(), e);
        }
    }

    // UPI-collect follow-up - the ownership-check lookup ahead of
    // closeUpiQrCode. Confirmed against Razorpay's actual Fetch a QR Code
    // by Id reference before this shipped: GET /v1/payments/qr_codes/:id,
    // response includes the same notes object set at creation time
    // (razorpay.com/docs/api/qr-codes/fetch-with-id/). Uses the SDK's
    // QrCodeClient#fetch(String), distinct from #create/#close above but
    // the same Entity#get(String) shape - reads the raw response JSON via
    // Entity#toJson() rather than Entity#get("notes") so this stays on the
    // same org.json.JSONObject-based parsing already used by
    // RazorpayWebhookEventMapper#extractReferenceId, rather than relying on
    // Entity's generic <T> get(String) to hand back a JSONObject unchecked.
    @Override
    public GatewayQrCodeDetails fetchUpiQrCode(String qrCodeId) {
        try {
            com.razorpay.QrCode qrCode = client().qrCode.fetch(qrCodeId);
            String id = qrCode.get("id");
            String status = qrCode.get("status");
            JSONObject notesJson = qrCode.toJson().optJSONObject("notes");
            java.util.Map<String, String> notes = new java.util.HashMap<>();
            if (notesJson != null) {
                for (String key : notesJson.keySet()) {
                    notes.put(key, notesJson.optString(key, null));
                }
            }
            return new GatewayQrCodeDetails(id, status, notes);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not fetch Razorpay UPI QR code: " + e.getMessage(), e);
        }
    }

    @Override
    public void verifyWebhookSignature(String rawBody, String signatureHeader) {
        signatureVerifier.verifyWebhookSignature(rawBody, signatureHeader);
    }

    @Override
    public BillingEvent parseWebhookEvent(String rawBody) {
        JSONObject json = new JSONObject(rawBody);
        String rawEventName = json.optString("event", "");
        String referenceId = RazorpayWebhookEventMapper.extractReferenceId(json, rawEventName);
        // capturedPayment rides on the event so the generic webhook path's
        // lifecycle handler can invoice without the raw body - empty for
        // every non-charge event, same contract as extractCapturedPayment.
        CapturedPaymentDetails capturedPayment =
                extractCapturedPayment(rawBody)
                        .map(
                                p ->
                                        new CapturedPaymentDetails(
                                                p.paymentId(), p.amountMinor(), p.currency()))
                        .orElse(null);
        return new BillingEvent(
                RazorpayWebhookEventMapper.mapEventType(rawEventName),
                referenceId,
                rawEventName,
                gatewayName(),
                capturedPayment);
    }

    /**
     * GST invoicing (audit D6) - the {@code payload.payment.entity} block Razorpay attaches to
     * charge-carrying webhooks ({@code subscription.charged}; purely-lifecycle events like a
     * chargeless {@code subscription.activated} don't have one). Callers use this to decide whether
     * a verified event has anything to invoice, so an empty result is the normal no-payment case,
     * not an error. Deliberately a separate method rather than extra {@link BillingEvent} fields -
     * BillingEvent's three-field contract is shared with PhonePe and a pile of existing constructor
     * call sites, and only the invoice wiring needs payment details.
     */
    public Optional<CapturedWebhookPayment> extractCapturedPayment(String rawBody) {
        JSONObject payload = new JSONObject(rawBody).optJSONObject("payload");
        JSONObject paymentWrapper = payload == null ? null : payload.optJSONObject("payment");
        JSONObject entity = paymentWrapper == null ? null : paymentWrapper.optJSONObject("entity");
        if (entity == null) {
            return Optional.empty();
        }
        String paymentId = entity.optString("id", null);
        long amountMinor = entity.optLong("amount", -1L);
        String currency = entity.optString("currency", null);
        if (paymentId == null || paymentId.isBlank() || amountMinor < 0) {
            return Optional.empty();
        }
        return Optional.of(new CapturedWebhookPayment(paymentId, amountMinor, currency));
    }

    /**
     * One captured payment as carried in a webhook's {@code payload.payment.entity} - {@code
     * amountMinor} in Razorpay's own minor units (paise/cents), which is exactly what {@code
     * InvoiceService#recordPaymentInvoice} expects.
     */
    public record CapturedWebhookPayment(String paymentId, long amountMinor, String currency) {}

    @Override
    public void createOverageAddon(
            String gatewaySubscriptionId,
            String description,
            long amountInSubunits,
            String currency) {
        try {
            JSONObject item = new JSONObject();
            item.put("name", description);
            item.put("amount", amountInSubunits);
            item.put("currency", currency);

            JSONObject request = new JSONObject();
            request.put("item", item);
            request.put("quantity", 1);

            client().subscriptions.createAddon(gatewaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not create Razorpay overage addon: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayOrder createTopUpOrder(
            String tenantId,
            long amountInSubunits,
            String currency,
            String receipt,
            java.util.Map<String, String> notes) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amountInSubunits);
            request.put("currency", currency);
            if (receipt != null) {
                request.put("receipt", receipt);
            }
            // Auto-capture: a budget top-up is a single immediate charge, so
            // there is no separate authorize/capture step to manage app-side.
            request.put("payment_capture", 1);
            JSONObject noteJson = new JSONObject();
            noteJson.put("agentic_ai_tenant_id", tenantId);
            if (notes != null) {
                notes.forEach(noteJson::put);
            }
            request.put("notes", noteJson);

            Order order = client().orders.create(request);
            String id = order.get("id");
            String status = order.get("status");
            return new GatewayOrder(id, status, amountInSubunits, currency);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not create Razorpay top-up order: " + e.getMessage(), e);
        }
    }

    // PRICING-MONETIZATION-AUDIT-2026-07-31 D5 - tenant-initiated cancel.
    // Verified against the actual 1.4.9 SDK (same posture as this class's
    // header): SubscriptionClient exposes cancel(String id, JSONObject
    // request); the Cancel Subscription API's one documented body parameter
    // is cancel_at_cycle_end (0|1 - Razorpay's docs send it as an integer,
    // not a JSON boolean). With 1, the subscription stays "active" until the
    // current cycle ends and only then emits subscription.cancelled -
    // RazorpayWebhookService's downgrade path picks it up from there.
    @Override
    public String cancelSubscription(String gatewaySubscriptionId, boolean cancelAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("cancel_at_cycle_end", cancelAtCycleEnd ? 1 : 0);
            Subscription subscription =
                    client().subscriptions.cancel(gatewaySubscriptionId, request);
            return subscription.get("status");
        } catch (RazorpayException e) {
            throw new PaymentGatewayException(
                    "Could not cancel Razorpay subscription: " + e.getMessage(), e);
        }
    }

    @Override
    public void verifyPaymentSignature(String orderId, String paymentId, String signatureHeader) {
        signatureVerifier.verifyPaymentSignature(orderId, paymentId, signatureHeader);
    }
}
