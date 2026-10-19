package com.agenticai.core.common.budget;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.billing.gateway.GatewayOrder;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 Phase P2 (paid top-up /
 * overage). When a user hits their per-user dollar budget
 * ({@link PerUserBudgetService#checkBudget} throws {@code
 * PerUserBudgetExhaustedException} &rarr; HTTP 402), this service is the
 * "top up to continue" flow: it lists purchasable amounts, opens a one-off
 * payment order through the {@link PaymentGatewayRegistry} (mirroring the
 * subscription-upgrade flow's gateway usage), and on a signature-verified
 * successful payment credits the paid amount to the current budget window via
 * {@link PerUserBudgetService#creditTopUp}.
 *
 * <p><b>No new table (per the build brief's "prefer Redis credit + an
 * audit-log entry over a new table").</b> The credit itself is a Redis counter
 * inside {@code PerUserBudgetService}. The only extra state here is a
 * short-lived Redis "pending order" record ({@code orderId -> tenant|user|
 * modality|usd}) written at purchase time and consumed once at confirm time -
 * it is both the integrity guard (the credited amount/modality come from THIS
 * server-side record, never from client-supplied confirm fields, so a client
 * can't claim more than it paid) and the idempotency guard (the confirm claims
 * the record with an atomic delete, so a duplicate/replayed confirm can never
 * double-credit). Every initiate/credit is additionally written to
 * {@link AuditLogService} for reconciliation.
 *
 * <p>{@code @Profile("security")} because it depends on the security-gated
 * billing beans, exactly like {@code RazorpayWebhookService}.
 */
@Service
@Profile("security")
public class BudgetTopUpService {

    private static final Logger log = LoggerFactory.getLogger(BudgetTopUpService.class);

    // Runtime-configurable via platform_setting, defaults in-code (same
    // "works against a DB that predates any seed row" posture as
    // PerUserBudgetService's own keys - no migration required to function).
    static final String KEY_ENABLED = "platform.budget.per-user.topup.enabled";
    static final String KEY_OPTIONS_USD = "platform.budget.per-user.topup.options-usd";
    static final String KEY_CURRENCY = "platform.budget.per-user.topup.currency";
    static final String KEY_MIN_USD = "platform.budget.per-user.topup.min-usd";
    static final String KEY_MAX_USD = "platform.budget.per-user.topup.max-usd";

    private static final String DEFAULT_ENABLED = "true";
    private static final String DEFAULT_OPTIONS_USD = "0.15,0.50,1.00,5.00";
    // USD by default: the budget is denominated in USD, so charging USD keeps
    // credited == charged 1:1 with no FX. An operator that switches this to a
    // local currency must set option amounts priced in that currency (the
    // amount figure is charged as-is in the currency's minor unit).
    private static final String DEFAULT_CURRENCY = "USD";
    private static final String DEFAULT_MIN_USD = "0.15";
    private static final String DEFAULT_MAX_USD = "50";

    // Two-decimal currencies (USD cents, INR paise) -> major * 100 = subunits.
    private static final BigDecimal SUBUNITS_PER_MAJOR = BigDecimal.valueOf(100);

    private static final String PENDING_KEY_PREFIX = "agentic:budget-topup:pending:";
    private static final Duration PENDING_TTL = Duration.ofHours(1);

    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final PerUserBudgetService perUserBudgetService;
    private final PlatformSettingsService platformSettingsService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;

    public BudgetTopUpService(
        PaymentGatewayRegistry paymentGatewayRegistry,
        PerUserBudgetService perUserBudgetService,
        PlatformSettingsService platformSettingsService,
        AuditLogService auditLogService,
        StringRedisTemplate redisTemplate
    ) {
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.perUserBudgetService = perUserBudgetService;
        this.platformSettingsService = platformSettingsService;
        this.auditLogService = auditLogService;
        this.redisTemplate = redisTemplate;
    }

    /** The purchasable top-up amounts + the gateway details a client needs to open checkout. */
    public BudgetTopUpOptionsResponse options() {
        PaymentGatewayClient gateway = paymentGatewayRegistry.resolve(null);
        String currency = platformSettingsService.getString(KEY_CURRENCY, DEFAULT_CURRENCY).trim();
        List<BudgetTopUpOption> options = new ArrayList<>();
        for (String raw : platformSettingsService.getString(KEY_OPTIONS_USD, DEFAULT_OPTIONS_USD).split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                BigDecimal usd = new BigDecimal(trimmed);
                if (usd.signum() > 0) {
                    options.add(new BudgetTopUpOption(usd, toSubunits(usd), "$" + usd.toPlainString()));
                }
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric top-up option '{}' in setting {}", trimmed, KEY_OPTIONS_USD);
            }
        }
        return new BudgetTopUpOptionsResponse(
            topUpEnabled(),
            currency,
            minUsd(),
            maxUsd(),
            gateway.gatewayName(),
            gateway.publicKeyId(),
            options
        );
    }

    /**
     * Opens a one-off payment order for {@code amountUsd} on {@code modality}'s
     * budget and records a pending-order entry the confirm step will consume.
     * Returns everything the browser needs to open the gateway's checkout
     * widget (order id + public key). Nothing is credited yet - crediting only
     * happens in {@link #confirm} after the payment is verified.
     */
    public BudgetTopUpPurchaseResponse purchase(String tenantId, String userKey, String modalityName, BigDecimal amountUsd) {
        if (!topUpEnabled()) {
            throw new IllegalArgumentException("Budget top-up is not enabled on this deployment.");
        }
        Modality modality = parseModality(modalityName);
        BigDecimal amount = requireValidAmount(amountUsd);

        PaymentGatewayClient gateway = paymentGatewayRegistry.resolve(null);
        String currency = platformSettingsService.getString(KEY_CURRENCY, DEFAULT_CURRENCY).trim();
        long subunits = toSubunits(amount);
        String receipt = "topup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("agentic_ai_user_key", userKey);
        notes.put("agentic_ai_modality", modality.name().toLowerCase(Locale.ROOT));
        notes.put("agentic_ai_amount_usd", amount.toPlainString());
        notes.put("agentic_ai_purpose", "per_user_budget_topup");

        GatewayOrder order = gateway.createTopUpOrder(tenantId, subunits, currency, receipt, notes);

        // Server-side record of what this order is worth and who it's for - the
        // confirm step credits from THIS, never from client-supplied fields.
        redisTemplate.opsForValue().set(
            pendingKey(order.orderId()),
            String.join("|", tenantId, userKey, modality.name(), amount.toPlainString()),
            PENDING_TTL
        );

        auditLogService.record(
            userKey, "budget.topup.initiated", "budget_topup", order.orderId(),
            AuditOutcome.PENDING_APPROVAL,
            "Initiated " + modality.name().toLowerCase(Locale.ROOT) + " budget top-up of $" + amount.toPlainString()
                + " (" + gateway.gatewayName() + " order " + order.orderId() + ")"
        );

        return new BudgetTopUpPurchaseResponse(
            gateway.gatewayName(), gateway.publicKeyId(), order.orderId(),
            subunits, currency, modality.name().toLowerCase(Locale.ROOT), amount, order.status()
        );
    }

    /**
     * Confirms a completed top-up payment (the client-side checkout {@code
     * handler} callback path): verifies the gateway signature, then - only if
     * this confirm atomically claims the pending-order record (idempotency) -
     * credits the paid amount to the current budget window. Returns the
     * refreshed budget status so the caller can immediately continue.
     */
    public BudgetTopUpConfirmResponse confirm(String tenantId, String userKey, BudgetTopUpConfirmRequest request) {
        String orderId = require(request.orderId(), "orderId");
        String paymentId = require(request.paymentId(), "paymentId");
        String signature = require(request.signature(), "signature");

        String pending = redisTemplate.opsForValue().get(pendingKey(orderId));
        if (pending == null) {
            // Either unknown, expired, or already-confirmed (we delete on
            // credit). Uniform message - never leak which, and never credit.
            throw new IllegalArgumentException("Unknown or already-processed top-up order.");
        }
        String[] parts = pending.split("\\|", 4);
        if (parts.length != 4 || !parts[0].equals(tenantId) || !parts[1].equals(userKey)) {
            // The order belongs to a different tenant/user than the caller -
            // treat as not-found rather than confirming someone else's order.
            throw new IllegalArgumentException("Unknown or already-processed top-up order.");
        }
        Modality modality = Modality.valueOf(parts[2]);
        BigDecimal amountUsd = new BigDecimal(parts[3]);

        // Authenticity: throws InvalidWebhookSignatureException (-> 403) on a
        // bad signature, before any credit. Same verify-then-act shape as the
        // subscription webhook flow.
        paymentGatewayRegistry.resolve(null).verifyPaymentSignature(orderId, paymentId, signature);

        // Atomically claim the pending record; a concurrent/duplicate confirm
        // that loses this race must NOT credit again.
        Boolean claimed = redisTemplate.delete(pendingKey(orderId));
        if (claimed == null || !claimed) {
            log.info("Top-up order {} was already confirmed concurrently - not crediting again", orderId);
            return new BudgetTopUpConfirmResponse(
                false, true, modality.name().toLowerCase(Locale.ROOT), amountUsd,
                perUserBudgetService.status(tenantId, userKey, modality)
            );
        }

        perUserBudgetService.creditTopUp(tenantId, userKey, modality, amountUsd);

        auditLogService.record(
            userKey, "budget.topup.credited", "budget_topup", orderId,
            AuditOutcome.SUCCESS,
            "Credited $" + amountUsd.toPlainString() + " to " + modality.name().toLowerCase(Locale.ROOT)
                + " budget (payment " + paymentId + ")"
        );

        return new BudgetTopUpConfirmResponse(
            true, false, modality.name().toLowerCase(Locale.ROOT), amountUsd,
            perUserBudgetService.status(tenantId, userKey, modality)
        );
    }

    // ---- helpers ----------------------------------------------------------

    private boolean topUpEnabled() {
        return Boolean.parseBoolean(platformSettingsService.getString(KEY_ENABLED, DEFAULT_ENABLED).trim());
    }

    private BigDecimal minUsd() {
        return parseUsd(platformSettingsService.getString(KEY_MIN_USD, DEFAULT_MIN_USD), DEFAULT_MIN_USD);
    }

    private BigDecimal maxUsd() {
        return parseUsd(platformSettingsService.getString(KEY_MAX_USD, DEFAULT_MAX_USD), DEFAULT_MAX_USD);
    }

    private BigDecimal requireValidAmount(BigDecimal amountUsd) {
        if (amountUsd == null || amountUsd.signum() <= 0) {
            throw new IllegalArgumentException("Top-up amount must be a positive dollar value.");
        }
        BigDecimal min = minUsd();
        BigDecimal max = maxUsd();
        if (amountUsd.compareTo(min) < 0 || amountUsd.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                "Top-up amount must be between $" + min.toPlainString() + " and $" + max.toPlainString() + "."
            );
        }
        return amountUsd;
    }

    private static Modality parseModality(String modalityName) {
        String name = (modalityName == null || modalityName.isBlank()) ? "TEXT" : modalityName.trim().toUpperCase(Locale.ROOT);
        try {
            return Modality.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown modality '" + modalityName + "'.");
        }
    }

    private static long toSubunits(BigDecimal amountUsd) {
        return amountUsd.multiply(SUBUNITS_PER_MAJOR).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal parseUsd(String value, String fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return new BigDecimal(fallback);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String pendingKey(String orderId) {
        return PENDING_KEY_PREFIX + orderId;
    }
}
