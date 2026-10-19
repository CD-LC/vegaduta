package com.agenticai.core.billing.gateway;

/**
 * The seam between this app's billing logic and whichever
 * payment gateway is actually wired up. {@link RazorpayGatewayClient} is the only implementation
 * for now (Razorpay: self-serve signup, India-native; Stripe requires a sales invite for
 * India-incorporated businesses with no committed timeline).
 * If that invite is ever approved, a {@code StripeGatewayClient} implementing this same interface
 * is how it gets added - Stages 3-6 depend only on this interface's vocabulary, never on
 * gateway-specific types, so adding a second gateway is additive, not a rewrite.
 *
 * <p>Deliberately does <b>not</b> have a separate "create customer" method, even though that might
 * look like the obvious first step (it's how Stripe's own Customer + Subscription objects work).
 * Razorpay's actual Subscriptions flow creates the subscription directly from a plan; its own
 * customer record is auto-created and linked only once the customer completes checkout - there is
 * nothing to abstract at customer-creation time because Razorpay doesn't have that step. This
 * interface's single {@link #createSubscriptionWithTrial} method reflects that, and turns out to be
 * the more honest common shape across gateways generally anyway (Stripe Checkout Sessions also
 * accept a bare {@code customer_email} and auto-attach a Customer the same way, rather than
 * requiring one be created first).
 *
 * <p><b>Stage 3 correction, found researching Razorpay's actual recommended integration path before
 * wiring the web console (worth recording so it isn't re-litigated):</b> the subscription's {@code
 * short_url} (returned as {@link GatewaySubscription#checkoutUrl}) is a shareable hosted page -
 * useful as an emailed fallback link (Razorpay emails it automatically when {@code customer_notify}
 * is on) - but there is no documented way to make it redirect back into this app on completion.
 * Razorpay's own integration guide (razorpay.com/docs/payments/subscriptions/ integration-guide)
 * instead recommends embedding "Standard Checkout" (their {@code checkout.js} widget) client-side
 * with the subscription id, using its {@code handler} callback to control the post-payment
 * redirect. That needs two more gateway-specific-but-safe-to-expose values on the client side -
 * {@link #publicKeyId()} (Razorpay: the Key ID, not the secret) and {@link #gatewayName()} - hence
 * both being added to this interface alongside {@link #createSubscriptionWithTrial}.
 */
public interface PaymentGatewayClient {

    /**
     * Whether this gateway's outbound-API credentials are actually configured in this deployment
     * (Razorpay: key-id + key-secret; Stripe: secret-key). Called by the upgrade and cancel paths
     * BEFORE asking the gateway to do anything, so a deployment that hasn't wired a gateway yet
     * fails loudly with a "not configured" billing error instead of an opaque HTTP 401 from the
     * gateway - and, just as importantly, a {@code security}-profile boot with blank keys still
     * constructs the bean (the signup comp-grant path must not depend on a payment gateway existing
     * at all).
     *
     * <p>Defaults {@code false} deliberately - a new implementation that forgets to override stays
     * unusable rather than accidentally callable (fail-closed, same posture as {@code
     * RazorpayPlanCatalog}'s missing- plan-id error and {@code CompGrantController}'s blank-token
     * reject). Webhook verification does NOT consult this - a gateway can have a webhook secret
     * configured independently of its API credentials, and {@link #verifyWebhookSignature}
     * fail-closes on a blank secret anyway.
     */
    default boolean isConfigured() {
        return false;
    }

    /**
     * What this gateway implements. Callers (the neutral checkout API and the web console) consult
     * this rather than the gateway's name, so adding a gateway never means editing them. Defaults
     * to nothing - fail-closed, same posture as {@link #isConfigured()}: a new implementation
     * advertises exactly what it has wired up, including how each payment is confirmed.
     */
    default java.util.Set<GatewayCapability> capabilities() {
        return java.util.Set.of();
    }

    /**
     * Widget (browser SDK) vs hosted-page redirect. Defaults to redirect, the safer generic shape.
     */
    default CheckoutMode checkoutMode() {
        return CheckoutMode.REDIRECT;
    }

    /**
     * The HTTP request header carrying this gateway's webhook signature (Razorpay: {@code
     * X-Razorpay-Signature}, Stripe: {@code Stripe-Signature}), or {@code null} when none exists.
     * The webhook endpoint reads whichever header the gateway declares here instead of switching on
     * the gateway name, so a new gateway's webhooks work without touching the controller. A {@code
     * null}/absent value is handed to {@link #verifyWebhookSignature}, which fail-closes.
     */
    default String webhookSignatureHeader() {
        return null;
    }

    /**
     * The header carrying the gateway's per-delivery event id (Razorpay: {@code
     * X-Razorpay-Event-Id}), or {@code null} when the gateway sends none - the event ledger then
     * dedupes on a body hash.
     */
    default String webhookEventIdHeader() {
        return null;
    }

    /**
     * A short, stable identifier for which gateway implementation this is (e.g. {@code "razorpay"})
     * - stored on {@code Tenant.paymentGateway} so {@code TenantProvisioningService} never has to
     * import a gateway-specific type just to record which one was used.
     */
    String gatewayName();

    /**
     * The gateway's public/publishable identifier (Razorpay: Key ID) - safe to send to the browser,
     * unlike {@link #verifyWebhookSignature}'s secret. The web console needs this to open the
     * gateway's own client-side checkout widget for {@link #createSubscriptionWithTrial}'s returned
     * subscription id.
     */
    String publicKeyId();

    /**
     * Creates a subscription on the given plan with a trial period, and returns a hosted checkout
     * URL for the customer to authorize a payment method. The gateway's own customer id is
     * <b>not</b> returned here - see this interface's javadoc - it's only known once {@link
     * #parseWebhookEvent} reports the subscription has authenticated.
     *
     * @param tenantId this app's own tenant id, passed through as gateway metadata/notes where
     *     supported, purely for cross-referencing a gateway-side record back to a tenant during
     *     support/debugging - never relied upon as the source of truth for that link (the {@code
     *     Tenant} row's {@code gateway_subscription_id} column is)
     * @param planId one of the configured gateway Plan ids (config-driven,
     *     never hardcoded - see {@code app.billing.razorpay.plan-id-*})
     * @param trialDays how many days before the first real charge
     * @param customerEmail the tenant admin's email - passed to the gateway so its hosted checkout
     *     page can pre-fill/notify
     * @param customerName the tenant admin's full name (first + last)
     */
    GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName);

    /**
     * PRICING-MONETIZATION-AUDIT-2026-07-31 D4 (coupons) - the {@link #createSubscriptionWithTrial}
     * overload that also applies a gateway-side discount offer to the subscription being created
     * (Razorpay: the Create Subscription API's documented-but-previously- unused {@code offer_id}
     * parameter, an Offer created in the dashboard).
     *
     * <p>A {@code default} so existing implementations stay untouched and compiling - same additive
     * posture as {@link #createTopUpOrder}. The default delegates straight to the five-argument
     * method when no offer is requested, and <b>throws</b> (rather than silently dropping the
     * discount) when one is: a customer who applied a coupon and then got charged full price is
     * strictly worse than a clear failure, the same "never silently ignore" rule the caller applies
     * to unknown coupon codes. Only a gateway that actually implements offers (Razorpay) overrides
     * this.
     *
     * @param offerId the gateway-side offer/discount id to apply, or {@code null}/blank for none
     *     (identical to the five-argument method in that case)
     */
    default GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName,
            String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return createSubscriptionWithTrial(
                    tenantId, planId, trialDays, customerEmail, customerName);
        }
        throw new PaymentGatewayException(
                "Gateway '" + gatewayName() + "' does not support discount offers/coupons", null);
    }

    /**
     * Currencies a self-serve upgrade can be billed in through this gateway. Default INR only; the
     * upgrade flow rejects any other currency up front rather than billing something other than
     * what was asked for.
     */
    default java.util.Set<BillingCurrency> supportedCurrencies() {
        return java.util.EnumSet.of(BillingCurrency.INR);
    }

    /** Billing periods a self-serve upgrade can use through this gateway. Default MONTHLY only. */
    default java.util.Set<BillingPeriod> supportedPeriods() {
        return java.util.EnumSet.of(BillingPeriod.MONTHLY);
    }

    /**
     * The currencies the console should OFFER right now, in display order - stricter than {@link
     * #supportedCurrencies()}: only currencies whose plans are actually set up, so a customer is
     * never sent into a checkout that cannot start. Default INR (a gateway without per-currency
     * plans bills INR).
     */
    default java.util.List<BillingCurrency> offeredCurrencies() {
        return java.util.List.of(BillingCurrency.INR);
    }

    /** Currencies with a dedicated ANNUAL plan, for the console's annual toggle. Default none. */
    default java.util.List<BillingCurrency> annualCurrencies() {
        return java.util.List.of();
    }

    /**
     * The plan id to pass to {@link #createSubscriptionWithTrial} for a paid tier. Callers only ask
     * for combinations in {@link #supportedCurrencies()} x {@link #supportedPeriods()}. The default
     * is the bare tier name, which a gateway such as PhonePe resolves against its own plan
     * configuration; Razorpay overrides it with a dashboard Plan id lookup.
     */
    default String resolvePlanId(PaidTier tier, BillingCurrency currency, BillingPeriod period) {
        return tier.name();
    }

    /** Whether {@link #offerIdFor} can ever resolve a coupon. Default false. */
    default boolean supportsCoupons() {
        return false;
    }

    /**
     * Coupon code to the gateway-side offer id for the six-argument {@link
     * #createSubscriptionWithTrial}. Empty when the code is unknown; callers reject it rather than
     * silently charging full price.
     */
    default java.util.Optional<String> offerIdFor(String couponCode) {
        return java.util.Optional.empty();
    }

    /**
     * Verifies an inbound webhook request's signature. Throws {@link
     * InvalidWebhookSignatureException} if it doesn't check out - never returns a boolean for the
     * caller to (potentially) forget to check, same reasoning {@code
     * TwilioWebhookSignatureException} follows for the inbound WhatsApp webhook.
     *
     * @param rawBody the exact, unparsed request body bytes as a string - signature verification is
     *     over the raw bytes, not a re-serialized version of a parsed object
     * @param signatureHeader the gateway's signature header value (Razorpay: {@code
     *     X-Razorpay-Signature})
     */
    void verifyWebhookSignature(String rawBody, String signatureHeader);

    /**
     * The request header this gateway authenticates its webhooks with. Razorpay signs with {@code
     * X-Razorpay-Signature}; PhonePe sends a credential hash in {@code Authorization}. The generic
     * webhook controller used to bind only the Razorpay header, so every PhonePe callback failed
     * verification (JAVA-004); it now hands the whole header set to {@link
     * #verifyWebhookHeaders(String, org.springframework.http.HttpHeaders)} and each gateway picks
     * its own.
     */
    default String webhookSignatureHeaderName() {
        return "X-Razorpay-Signature";
    }

    /** Header-set overload: resolves {@link #webhookSignatureHeaderName()} and verifies. */
    default void verifyWebhookHeaders(
            String rawBody, org.springframework.http.HttpHeaders headers) {
        verifyWebhookSignature(
                rawBody, headers == null ? null : headers.getFirst(webhookSignatureHeaderName()));
    }

    /**
     * Parses an already-signature-verified webhook body into this app's gateway-agnostic {@link
     * BillingEvent}. Callers must call {@link #verifyWebhookSignature} first - this method does not
     * re-verify.
     */
    BillingEvent parseWebhookEvent(String rawBody);

    /**
     * Reports a one-off overage charge against a subscription's next invoice (Razorpay: an Addon).
     * Used by Stage 5's usage metering for Business-tier message-volume overage - Starter tier
     * never calls this, it hard-caps instead.
     *
     * @param amountInSubunits smallest currency unit (e.g. paise for INR) - never a floating-point
     *     major-unit amount, same reasoning this codebase already applies to any monetary value
     */
    void createOverageAddon(
            String gatewaySubscriptionId,
            String description,
            long amountInSubunits,
            String currency);

    /**
     * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 P2 (paid top-up / overage) - creates a ONE-OFF
     * payment order (Razorpay: an Order) for a per-user budget top-up, returning the order id the
     * browser-side checkout widget opens against (together with {@link #publicKeyId()}). Distinct
     * from {@link #createSubscriptionWithTrial}: a single charge, not a recurring mandate - the
     * top-up crediting logic lives entirely app-side (a Redis credit counter), so nothing recurring
     * is registered with the gateway.
     *
     * <p>A {@code default} that throws {@link UnsupportedOperationException}: this is additive to
     * the interface, and only the gateway a deployment actually routes top-ups through needs to
     * implement it (Razorpay does). A gateway that hasn't - e.g. {@code PhonePeGatewayClient} -
     * stays valid and unchanged; routing a top-up to it simply surfaces as a clear "not supported
     * by this gateway" failure rather than a silent wrong charge.
     *
     * @param amountInSubunits smallest currency unit (paise/cents), never a floating-point major
     *     amount - same money-handling rule as {@link #createOverageAddon}.
     * @param notes free-form key/value metadata recorded on the gateway order for
     *     support/reconciliation (e.g. the app tenant id / user key / modality this top-up is for)
     *     - never relied on as the source of truth for crediting (the app's own pending-order
     *     record is).
     */
    default GatewayOrder createTopUpOrder(
            String tenantId,
            long amountInSubunits,
            String currency,
            String receipt,
            java.util.Map<String, String> notes) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support one-off top-up orders");
    }

    /**
     * UPI-collect follow-up - creates a UPI payment request the customer completes via their own
     * UPI app's Intent flow (Razorpay: a Payment Link with {@code upi_link: true}). See {@link
     * GatewayUpiCollectRequest}'s javadoc for why this is Intent-based via Payment Links, never
     * Razorpay's manual-VPA-entry "UPI Collect" flow - NPCI deprecated that exact flow for
     * merchants in this platform's category effective 2026-02-28, already past as of this writing.
     *
     * <p>A {@code default} that throws {@link UnsupportedOperationException}, same additive posture
     * as {@link #createTopUpOrder} - only a gateway that actually implements a UPI-specific surface
     * (Razorpay) overrides it.
     *
     * @param amountInSubunits smallest currency unit (paise - UPI is INR-only), never a
     *     floating-point major amount, same money-handling rule as {@link #createOverageAddon}
     * @param description shown to the customer on the payment page
     * @param customerName optional, pre-fills the payment page
     * @param customerContact optional phone number, pre-fills the payment page
     * @param customerEmail optional, pre-fills the payment page
     * @param notes free-form key/value metadata recorded on the gateway payment link for
     *     support/reconciliation - never the source of truth for crediting, same posture as {@link
     *     #createTopUpOrder}
     */
    default GatewayUpiCollectRequest createUpiCollectRequest(
            String tenantId,
            long amountInSubunits,
            String description,
            String customerName,
            String customerContact,
            String customerEmail,
            java.util.Map<String, String> notes) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support UPI collect requests");
    }

    /**
     * The {@link #createUpiCollectRequest} overload that also sets the hosted link's own expiry
     * (Razorpay: the Payment Link API's documented {@code expire_by} epoch-seconds parameter), so
     * an abandoned link stops being payable gateway-side instead of only app-side. Callers with a
     * real expiry semantics (the checkout-session tool) pass their session expiry so the two agree;
     * callers without one keep the seven-argument form unchanged.
     *
     * <p>Same additive posture as the offer-id overload above: {@code null} delegates to the
     * seven-argument method byte-for-byte, a non-null value on a gateway with no link-expiry
     * surface <b>throws</b> rather than silently shipping a never-expiring link the caller believed
     * bounded.
     */
    default GatewayUpiCollectRequest createUpiCollectRequest(
            String tenantId,
            long amountInSubunits,
            String description,
            String customerName,
            String customerContact,
            String customerEmail,
            java.util.Map<String, String> notes,
            java.time.Instant expireBy) {
        if (expireBy == null) {
            return createUpiCollectRequest(
                    tenantId,
                    amountInSubunits,
                    description,
                    customerName,
                    customerContact,
                    customerEmail,
                    notes);
        }
        throw new PaymentGatewayException(
                "Gateway '" + gatewayName() + "' does not support payment-link expiry", null);
    }

    /**
     * UPI-collect follow-up - creates a scannable UPI QR code for a fixed one-off amount (Razorpay:
     * QR Code API, {@code type: "upi_qr"}) - the other NPCI-recommended migration target alongside
     * {@link #createUpiCollectRequest}'s Payment Link, for "scan to pay" surfaces where a hosted
     * link is less natural than a code to scan.
     *
     * <p>{@code default}-throws for the same additive reason as {@link #createUpiCollectRequest}.
     *
     * @param amountInSubunits smallest currency unit (paise) - the QR is created with a fixed
     *     amount, not an open "pay any amount" code
     * @param name a short label for the QR code (dashboard/support reference)
     * @param description shown alongside the QR code
     * @param notes free-form key/value metadata, same posture as {@link #createTopUpOrder}
     */
    default GatewayUpiQrCode createUpiQrCode(
            String tenantId,
            long amountInSubunits,
            String name,
            String description,
            java.util.Map<String, String> notes) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support UPI QR codes");
    }

    /**
     * Closes a QR code created by {@link #createUpiQrCode} (Razorpay: {@code POST
     * /v1/payments/qr_codes/:id/close}) - stops it from accepting further payments, once this app
     * has recorded the expected payment (via the {@code qr_code.credited} webhook, {@link
     * BillingEventType#QR_CODE_PAYMENT_RECEIVED}) or the customer has abandoned it. A closed QR
     * code cannot be reopened - a fresh one must be created for a new payment.
     *
     * <p>{@code default}-throws for the same additive reason as {@link #createUpiCollectRequest}.
     */
    default void closeUpiQrCode(String qrCodeId) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support closing UPI QR codes");
    }

    /**
     * Fetches a QR code created by {@link #createUpiQrCode} (Razorpay: {@code GET
     * /v1/payments/qr_codes/:id}), including its {@code notes} metadata. Added specifically so
     * callers can verify ownership (the {@code notes.tenant_id} stamped at creation time) before an
     * operation like {@link #closeUpiQrCode} that Razorpay's own API does not scope by tenant - see
     * {@code RazorpayCheckoutController#closeUpiQr}.
     *
     * <p>{@code default}-throws for the same additive reason as {@link #createUpiCollectRequest}.
     */
    default GatewayQrCodeDetails fetchUpiQrCode(String qrCodeId) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support fetching UPI QR codes");
    }

    /**
     * Verifies a completed one-off payment's signature - the synchronous confirm counterpart of
     * {@link #verifyWebhookSignature}, for the client-side checkout {@code handler} callback that
     * returns an order id + payment id + signature after a {@link #createTopUpOrder} payment.
     * Throws {@link InvalidWebhookSignatureException} when it doesn't check out (never returns a
     * boolean the caller could forget to check, same posture as {@link #verifyWebhookSignature}).
     * {@code default}-throws for the same additive reason as {@link #createTopUpOrder}.
     *
     * @param orderId the gateway order id from {@link #createTopUpOrder}
     * @param paymentId the gateway payment id the checkout callback returned
     * @param signatureHeader the checkout callback's signature over order+payment
     */
    default void verifyPaymentSignature(String orderId, String paymentId, String signatureHeader) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support one-off payment verification");
    }

    /**
     * PRICING-MONETIZATION-AUDIT-2026-07-31 D5 (subscription cancellation) - cancels an existing
     * gateway subscription, the tenant-initiated counterpart of {@link
     * #createSubscriptionWithTrial}. With {@code cancelAtCycleEnd} true (the self-serve default -
     * see {@code TenantSubscriptionService}), the customer keeps what they paid for until the
     * current billing cycle ends and the gateway emits its {@code subscription.cancelled} webhook
     * only then; false cancels immediately.
     *
     * <p>{@code default}-throws for the same additive reason as {@link #createTopUpOrder} - only a
     * gateway that actually routes subscriptions (Razorpay today) implements it, and routing a
     * cancel to one that doesn't must surface as a clear failure, never a silent still-billing
     * subscription.
     *
     * @return the gateway's post-cancel subscription status string (Razorpay: {@code "cancelled"}
     *     immediately, or still {@code "active"} until cycle end when {@code cancelAtCycleEnd} is
     *     true) - recorded on {@code Tenant.subscriptionStatus} by the caller, same raw-gateway-
     *     string posture that column has always had (see V11's header).
     */
    default String cancelSubscription(String gatewaySubscriptionId, boolean cancelAtCycleEnd) {
        throw new UnsupportedOperationException(
                "Gateway '" + gatewayName() + "' does not support subscription cancellation");
    }
}
