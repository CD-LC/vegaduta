package com.agenticai.core.billing.gateway;

import com.agenticai.core.json.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link PaymentGatewayClient} backed by PhonePe's Payment Gateway + UPI Autopay (subscription
 * mandate) APIs - the second gateway implementation this interface's own javadoc anticipated
 * ("adding a second gateway is additive, not a rewrite").
 *
 * <p><b>What's CONFIRMED against PhonePe's official developer docs (developer.phonepe.com) as of
 * this writing, vs. BEST-EFFORT / NEEDS VERIFICATION before this goes anywhere near real money -
 * stated explicitly, the same discipline {@link RazorpayGatewayClient}'s own javadoc follows:</b>
 *
 * <ul>
 *   <li><b>CONFIRMED</b> - the OAuth {@code client_credentials} token flow: exact sandbox ({@code
 *       api-preprod.phonepe.com/apis/pg-sandbox/v1/oauth/token}) and production ({@code
 *       api.phonepe.com/apis/identity-manager/v1/oauth/token}) endpoints, request fields ({@code
 *       client_id}/{@code client_version}/{@code client_secret}/{@code grant_type=
 *       client_credentials}), response shape ({@code access_token}/{@code expires_at}/{@code
 *       token_type: "O-Bearer"}), and that the token is sent as {@code Authorization: O-Bearer
 *       <token>} on subsequent calls.
 *   <li><b>CONFIRMED</b> - webhook authenticity verification: HTTP Basic Auth style, {@code
 *       Authorization: SHA256(username:password)} where username/password are configured in the
 *       PhonePe Business Dashboard's Webhook settings, compared against a locally-computed hash of
 *       those same configured credentials. Notably NOT an HMAC-over-the-request-body scheme the way
 *       Razorpay's webhook signature works - this only proves the caller knows the configured
 *       username/password, not that this specific body wasn't altered in transit (TLS is what
 *       protects that here) - a real, documented difference from Razorpay, not an oversight below.
 *   <li><b>CONFIRMED</b> - the one-time Standard Checkout webhook event names ({@code
 *       checkout.order.completed}, {@code checkout.order.failed}, {@code pg.refund.completed},
 *       {@code pg.refund.failed}), and that subscription/mandate ("Autopay") setup is a SEPARATE
 *       API surface from one-time Standard Checkout, with its own documented fields ({@code
 *       subscriptionType: "RECURRING"}, {@code authWorkflowType}, {@code amountType}, {@code
 *       maxAmount} - capped at ₹15,000 by NPCI's own UPI Autopay rules, not this codebase's choice,
 *       {@code frequency}, {@code productType: "UPI_MANDATE"}).
 *   <li><b>NOT CONFIRMED - flagged, not guessed at</b>:
 *       <ol>
 *         <li>The exact base path for the non-OAuth Payment Gateway / Subscription-Setup REST
 *             calls. Only the OAuth token endpoint's exact URL was directly verifiable from the
 *             fetched docs during this pass - {@code app.billing.phonepe.api-base-url} below is
 *             left fully config-driven (no hardcoded default) specifically so this can't silently
 *             point at a guessed, wrong host.
 *         <li>The exact subscription LIFECYCLE webhook event names (activated/charged/cancelled/
 *             paused for an Autopay mandate specifically, as opposed to the one-time Standard
 *             Checkout events above, which ARE confirmed). {@link #mapEventType} maps every event
 *             name it hasn't independently confirmed to {@link BillingEventType#UNKNOWN} rather
 *             than guess a mapping that could silently misfile a real subscription-lifecycle event
 *             - the same safe-default precedent {@link RazorpayGatewayClient#gatewayName}'s sibling
 *             {@code mapEventType} method already established.
 *         <li>How a trial period maps onto the Subscription Setup API - no trial-period field was
 *             visible in what was fetched. See {@link #createSubscriptionWithTrial}'s inline note.
 *         <li>INR pricing for this platform's PREMIUM/UNLIMITED tiers - UPI Autopay is INR-only,
 *             and {@code app.billing.phonepe.plan-premium}/{@code plan-unlimited} (parsed by {@link
 *             #parsePlanConfig}) need real amounts a business decision, not this class, should set
 *             - see those properties' own comments in {@code application.yml}.
 *       </ol>
 * </ul>
 *
 * <p>Registered alongside {@link RazorpayGatewayClient} via {@link PaymentGatewayRegistry} - see
 * that class's javadoc for how a caller picks which gateway to use. Not yet wired into {@code
 * TenantUpgradeService}'s live call path pending the confirmations above; it exists today as a
 * real, independently testable {@link PaymentGatewayClient} implementation, not a placeholder.
 */
@Component
@Profile("security")
public class PhonePeGatewayClient implements PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PhonePeGatewayClient.class);

    /**
     * NPCI's own UPI Autopay per-mandate ceiling - a real regulatory constraint, not this
     * codebase's choice.
     */
    private static final long MAX_AUTOPAY_AMOUNT_PAISE = 15_000_00L;

    private final RestClient oauthClient;
    private final RestClient apiClient;
    private final ObjectMapper objectMapper = JsonMappers.plain();
    private final String clientId;
    private final String clientSecret;
    private final String clientVersion;
    private final String merchantId;
    private final String webhookUsername;
    private final String webhookPassword;

    /** SEC-024: warn once (not per request) when webhook credentials are blank. */
    private final java.util.concurrent.atomic.AtomicBoolean blankCredentialsWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final String premiumPlanConfig;
    private final String unlimitedPlanConfig;
    // Kept as a raw string in addition to the built apiClient because
    // isConfigured() must be able to report "no API base URL configured"
    // without parsing the client back apart.
    private final String apiBaseUrl;

    // Simple in-memory cache, single field - same "not worth more than this until proven a hot
    // path" reasoning TenantStatusGuardImpl/TenantSchemaResolver already document elsewhere in
    // this codebase; subscription creation happens per tenant upgrade, not per request. Not
    // thread-safe against a rare double-refresh race under concurrent first calls - acceptable,
    // worst case is one extra token fetch, never a correctness problem (PhonePe's token endpoint
    // is idempotent to call).
    private volatile String cachedAccessToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder, injected rather than two bare {@code
     *     RestClient.builder()} calls (2026-07-29 security audit - this client is named explicitly
     *     in the finding). A hand-built builder bypasses Spring Boot's HTTP client
     *     auto-configuration, so both the OAuth and the payments client ran with no connect and no
     *     read timeout: a PhonePe outage parked a checkout request forever instead of failing it,
     *     with the user's browser waiting on a payment whose real state was unknown. Injecting the
     *     shared builder picks up {@code spring.http.client.*}. Note {@code .baseUrl()} mutates the
     *     builder, so the two clients each get their own {@code clone()} rather than sharing one
     *     instance.
     */
    public PhonePeGatewayClient(
            RestClient.Builder restClientBuilder,
            @Value(
                            "${app.billing.phonepe.oauth-base-url:https://api-preprod.phonepe.com/apis/pg-sandbox}")
                    String oauthBaseUrl,
            @Value("${app.billing.phonepe.api-base-url}") String apiBaseUrl,
            @Value("${app.billing.phonepe.client-id}") String clientId,
            @Value("${app.billing.phonepe.client-secret}") String clientSecret,
            @Value("${app.billing.phonepe.client-version}") String clientVersion,
            @Value("${app.billing.phonepe.merchant-id}") String merchantId,
            @Value("${app.billing.phonepe.webhook-username}") String webhookUsername,
            @Value("${app.billing.phonepe.webhook-password}") String webhookPassword,
            @Value("${app.billing.phonepe.plan-premium:}") String premiumPlanConfig,
            @Value("${app.billing.phonepe.plan-unlimited:}") String unlimitedPlanConfig) {
        this.oauthClient = restClientBuilder.clone().baseUrl(oauthBaseUrl).build();
        this.apiClient = restClientBuilder.clone().baseUrl(apiBaseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clientVersion = clientVersion;
        this.merchantId = merchantId;
        this.webhookUsername = webhookUsername;
        this.webhookPassword = webhookPassword;
        this.premiumPlanConfig = premiumPlanConfig;
        this.unlimitedPlanConfig = unlimitedPlanConfig;
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * PhonePe needs all four of client-id/client-secret/merchant-id AND an api-base-url before any
     * subscription call can succeed - the base URL was deliberately left blank-by-default in
     * application.yml because the correct non-OAuth host was never independently confirmed (see the
     * class javadoc's "NOT CONFIRMED" list). Checking it here means a deployment that set
     * credentials but not the URL fails with a clear "not configured" at upgrade time rather than
     * an HTTP error against a blank host at call time.
     */
    @Override
    public boolean isConfigured() {
        return isPresent(clientId)
                && isPresent(clientSecret)
                && isPresent(merchantId)
                && isPresent(apiBaseUrl);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public java.util.Set<GatewayCapability> capabilities() {
        return java.util.EnumSet.of(
                GatewayCapability.SUBSCRIPTIONS, GatewayCapability.USAGE_ADDONS);
    }

    @Override
    public CheckoutMode checkoutMode() {
        return CheckoutMode.REDIRECT;
    }

    /** PhonePe authenticates its webhook with {@code Authorization: SHA256(username:password)}. */
    @Override
    public String webhookSignatureHeader() {
        return "Authorization";
    }

    @Override
    public String gatewayName() {
        return "phonepe";
    }

    @Override
    public String publicKeyId() {
        // Unlike Razorpay's Key ID, PhonePe's integration is redirect-based (see
        // createSubscriptionWithTrial's returned checkoutUrl / redirectUrl) rather than a
        // client-side checkout widget keyed by a publishable id - there is no PhonePe-specific
        // value the browser needs beyond that redirect URL. merchantId is returned here only
        // because this interface requires SOME value and merchantId is the closest PhonePe analog
        // (low-sensitivity, already visible to the customer on PhonePe's own hosted PayPage) -
        // callers should not assume it behaves like Razorpay's Key ID for a client-side widget.
        return merchantId;
    }

    @Override
    public GatewaySubscription createSubscriptionWithTrial(
            String tenantId,
            String planId,
            int trialDays,
            String customerEmail,
            String customerName) {
        // See this class's javadoc, "NOT CONFIRMED" item 3: no trial-period field was found on
        // the Subscription Setup API. TenantUpgradeService today only ever passes trialDays=0 for
        // upgrades (see that class's own "No trial on an upgrade" javadoc), so this is currently
        // unreachable in practice - but would silently under-deliver a promised trial if this
        // client were ever used from a NEW-signup flow (Razorpay-only today) without this being
        // resolved first, so it's surfaced loudly rather than silently ignored.
        if (trialDays > 0) {
            log.warn(
                    "PhonePeGatewayClient#createSubscriptionWithTrial called with trialDays={} but no trial-period field "
                            + "has been confirmed on PhonePe's Subscription Setup API yet - proceeding as if trialDays=0",
                    trialDays);
        }

        PlanConfig plan = parsePlanConfig(planId);
        if (plan.amountInPaise() > MAX_AUTOPAY_AMOUNT_PAISE) {
            throw new PaymentGatewayException(
                    "Plan amount "
                            + plan.amountInPaise()
                            + " paise exceeds UPI Autopay's NPCI-mandated "
                            + MAX_AUTOPAY_AMOUNT_PAISE
                            + "-paise per-mandate ceiling - this plan cannot be sold via PhonePe/UPI Autopay as configured.",
                    null);
        }

        String accessToken = obtainAccessToken();
        String merchantOrderId = "upgrade_" + tenantId + "_" + Instant.now().toEpochMilli();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("merchantOrderId", merchantOrderId);
        request.put("amount", plan.amountInPaise());
        request.put("subscriptionType", "RECURRING");
        request.put("authWorkflowType", "TRANSACTION");
        request.put("amountType", "FIXED");
        request.put("maxAmount", plan.amountInPaise());
        request.put("frequency", plan.frequency());
        request.put("productType", "UPI_MANDATE");
        ObjectNode metaInfo = request.putObject("metaInfo");
        // Same "record for support/debugging, never relied on as the source of truth" posture
        // RazorpayGatewayClient's notes field follows - Tenant.gatewaySubscriptionId is the real
        // link, this is only for cross-referencing a gateway-side record back to a tenant by hand.
        metaInfo.put("udf1", tenantId);
        metaInfo.put("udf2", customerEmail);
        metaInfo.put("udf3", customerName);

        try {
            JsonNode response =
                    apiClient
                            .post()
                            .uri("/v2/subscriptions/setup")
                            .header(HttpHeaders.AUTHORIZATION, "O-Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                throw new PaymentGatewayException(
                        "PhonePe subscription setup returned an empty response", null);
            }
            String subscriptionId = response.path("subscriptionId").asString(merchantOrderId);
            String redirectUrl = response.path("redirectUrl").asString(null);
            String state = response.path("state").asString("PENDING");
            return new GatewaySubscription(subscriptionId, redirectUrl, state);
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Could not create PhonePe subscription: " + e.getMessage(), e);
        }
    }

    /** PhonePe authenticates webhooks with {@code Authorization: <sha256(username:password)>}. */
    @Override
    public String webhookSignatureHeaderName() {
        return org.springframework.http.HttpHeaders.AUTHORIZATION;
    }

    @Override
    public void verifyWebhookSignature(String rawBody, String signatureHeader) {
        // See this class's javadoc: PhonePe's scheme is NOT an HMAC over rawBody the way
        // Razorpay's is - it's a fixed hash of the configured username:password pair, checked
        // against whatever the caller sent in the Authorization header. rawBody is intentionally
        // unused here (not a bug - PhonePe genuinely does not sign the body this way).
        // SEC-024: fail CLOSED while either webhook credential is unset. With blank credentials
        // the expected value would be sha256(":") - a public constant anyone can send.
        if (!isPresent(webhookUsername) || !isPresent(webhookPassword)) {
            if (blankCredentialsWarned.compareAndSet(false, true)) {
                log.warn(
                        "PhonePe webhook credentials (app.billing.phonepe.webhook-username/-password,"
                                + " PHONEPE_WEBHOOK_USERNAME / PHONEPE_WEBHOOK_PASSWORD) are not configured - every"
                                + " PhonePe webhook is rejected until both are set");
            }
            throw new InvalidWebhookSignatureException(
                    "PhonePe webhook credentials are not configured");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Missing PhonePe webhook Authorization header");
        }
        String expected = sha256Hex(webhookUsername + ":" + webhookPassword);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSignatureException(
                    "PhonePe webhook Authorization header did not match the configured credentials");
        }
    }

    @Override
    public BillingEvent parseWebhookEvent(String rawBody) {
        JsonNode json;
        try {
            json = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            // NOT a benign "unknown event type": verifyWebhookSignature already passed before this
            // is called, so PhonePe really did send this body and the charge it describes is being
            // dropped on the floor. UNKNOWN is still the right return (the dispatcher ignores it
            // and the controller must still 200 so PhonePe stops retrying) - but never silently,
            // since this log line is the only record that a paid subscription event arrived and
            // went nowhere. Compare RazorpayGatewayClient, which cannot hit this path at all.
            log.error("Could not parse PhonePe webhook body - dropping the event as UNKNOWN", e);
            return new BillingEvent(BillingEventType.UNKNOWN, null, "", gatewayName());
        }
        String rawEventName = json.path("event").asString("");
        String subscriptionOrOrderId = json.path("payload").path("orderId").asString(null);
        if (subscriptionOrOrderId == null) {
            subscriptionOrOrderId = json.path("payload").path("subscriptionId").asString(null);
        }
        return new BillingEvent(
                mapEventType(rawEventName), subscriptionOrOrderId, rawEventName, gatewayName());
    }

    @Override
    public void createOverageAddon(
            String gatewaySubscriptionId,
            String description,
            long amountInSubunits,
            String currency) {
        // Honestly unimplemented rather than guessed: no PhonePe API equivalent to Razorpay's
        // "add-on charged against a subscription's next invoice" was found during this pass.
        // Fabricating a call here risks either silently no-op'ing a real overage charge or
        // double-charging via the wrong endpoint - both worse than failing loudly. Business-tier
        // usage-metering overage (the only caller of this method today) should stay Razorpay-only
        // until a real PhonePe equivalent is confirmed.
        throw new UnsupportedOperationException(
                "PhonePeGatewayClient does not yet support overage add-on charges - no confirmed PhonePe API equivalent "
                        + "to Razorpay's per-subscription add-on exists as of this writing. Route overage billing through "
                        + "the Razorpay gateway until this is resolved.");
    }

    private String obtainAccessToken() {
        Instant now = Instant.now();
        String token = cachedAccessToken;
        if (token != null && now.isBefore(cachedTokenExpiresAt)) {
            return token;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_version", clientVersion);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");

        try {
            JsonNode response =
                    oauthClient
                            .post()
                            .uri("/v1/oauth/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || !response.hasNonNull("access_token")) {
                throw new PaymentGatewayException(
                        "PhonePe OAuth token response was missing access_token", null);
            }
            String accessToken = response.path("access_token").asString();
            long expiresAtEpochSeconds = response.path("expires_at").asLong(now.getEpochSecond());
            // Refresh a little early (60s margin) rather than risk a request starting just before
            // expiry and finishing just after it.
            this.cachedTokenExpiresAt =
                    Instant.ofEpochSecond(expiresAtEpochSeconds).minusSeconds(60);
            this.cachedAccessToken = accessToken;
            return accessToken;
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Could not obtain a PhonePe OAuth access token: " + e.getMessage(), e);
        }
    }

    /**
     * See this class's javadoc, "NOT CONFIRMED" item 2 - only the one-time Standard Checkout events
     * and refund events are independently confirmed. Everything else, including any real Autopay
     * subscription-lifecycle event, deliberately falls to {@link BillingEventType#UNKNOWN} rather
     * than a guessed mapping - the same safe-default precedent {@code RazorpayGatewayClient}'s own
     * {@code mapEventType} established.
     */
    private static BillingEventType mapEventType(String rawEventName) {
        return switch (rawEventName) {
            case "checkout.order.completed" -> BillingEventType.SUBSCRIPTION_CHARGED;
            case "checkout.order.failed" -> BillingEventType.SUBSCRIPTION_PENDING;
            case "pg.refund.completed", "pg.refund.failed" -> BillingEventType.UNKNOWN;
            default -> BillingEventType.UNKNOWN;
        };
    }

    private static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    /**
     * {@code app.billing.phonepe.plan-premium}/{@code plan-unlimited} are expected in the form
     * {@code "<amountInPaise>:<frequency>"} (e.g. {@code "99900:MONTHLY"}) - see this class's
     * javadoc, "NOT CONFIRMED" item 4, for why the actual INR figures are left to real business
     * configuration rather than a currency-converted guess baked into this code.
     */
    private PlanConfig parsePlanConfig(String planId) {
        String raw =
                "premium".equalsIgnoreCase(planId)
                        ? premiumPlanConfig
                        : "unlimited".equalsIgnoreCase(planId) ? unlimitedPlanConfig : planId;
        if (raw == null || raw.isBlank()) {
            throw new PaymentGatewayException(
                    "No PhonePe plan configuration found for '"
                            + planId
                            + "' - set "
                            + "app.billing.phonepe.plan-premium / app.billing.phonepe.plan-unlimited "
                            + "(format: \"<amountInPaise>:<frequency>\")",
                    null);
        }
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new PaymentGatewayException(
                    "Malformed PhonePe plan configuration '"
                            + raw
                            + "' - expected \"<amountInPaise>:<frequency>\"",
                    null);
        }
        try {
            return new PlanConfig(
                    Long.parseLong(parts[0].trim()), parts[1].trim().toUpperCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            throw new PaymentGatewayException("Malformed PhonePe plan amount in '" + raw + "'", e);
        }
    }

    private record PlanConfig(long amountInPaise, String frequency) {}
}
