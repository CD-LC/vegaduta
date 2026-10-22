package com.agenticai.core.payments.commerce;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.payments.MandateSigningService;
import com.agenticai.core.payments.PaymentSigningKey;
import com.agenticai.core.payments.wallet.AgentWallet;
import com.agenticai.core.payments.wallet.AgentWalletService;
import com.agenticai.core.payments.wallet.WalletException;
import com.agenticai.core.payments.wallet.WalletLedgerEntry;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The AP2-style mandate chain: an <b>intent mandate</b> (user authorizes an agent to shop for X up
 * to a ceiling) parents one or more <b>cart mandates</b> (the user signed THIS concrete spend -
 * items, total, payee). Both are Ed25519-signed over the tenant's {@code payment_signing_key} via
 * {@link MandateSigningService}, immutable after signing, and every transition is recorded in
 * {@code commerce_mandate_event} plus the audit log - the dispute trail.
 *
 * <p><b>Buyer authorization:</b> a cart is born {@code PENDING_AUTHORIZATION} and only the intent's
 * {@code buyer_user_id} can sign it ({@link #authorize}) - the signature covers a sub-document
 * bound to the cart's canonical hash, so it cannot be transplanted onto a different cart. Capture
 * then debits the agent's wallet - the agent spends ITS funded balance on the buyer's behalf,
 * exactly what the mandate authorized.
 *
 * <p><b>Ceiling enforcement:</b> a cart's total plus every already AUTHORIZED/CAPTURED sibling cart
 * must fit the intent's {@code max_total_minor} - enforced at both cart creation and authorize time
 * (the sibling set can grow between the two).
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class CommerceMandateService {

    private static final Logger log = LoggerFactory.getLogger(CommerceMandateService.class);

    private final CommerceMandateRepository mandateRepository;
    private final CommerceMandateEventRepository eventRepository;
    private final CommerceMandateCanonicalizer canonicalizer;
    private final MandateSigningService signingService;
    private final AgentWalletService walletService;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final ObjectMapper objectMapper = JsonMappers.plain();
    private final Clock clock;

    @Autowired
    public CommerceMandateService(
            CommerceMandateRepository mandateRepository,
            CommerceMandateEventRepository eventRepository,
            CommerceMandateCanonicalizer canonicalizer,
            MandateSigningService signingService,
            AgentWalletService walletService,
            ObjectProvider<AuditLogService> auditLogService) {
        this(
                mandateRepository,
                eventRepository,
                canonicalizer,
                signingService,
                walletService,
                auditLogService,
                Clock.systemUTC());
    }

    /** Test seam - fixed clock. */
    CommerceMandateService(
            CommerceMandateRepository mandateRepository,
            CommerceMandateEventRepository eventRepository,
            CommerceMandateCanonicalizer canonicalizer,
            MandateSigningService signingService,
            AgentWalletService walletService,
            ObjectProvider<AuditLogService> auditLogService,
            Clock clock) {
        this.mandateRepository = mandateRepository;
        this.eventRepository = eventRepository;
        this.canonicalizer = canonicalizer;
        this.signingService = signingService;
        this.walletService = walletService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ intent

    /**
     * Create an INTENT mandate. {@code buyerUserId} is the authorizing user (resolved from
     * TenantContext by the controller, never caller-supplied).
     */
    @Transactional
    public CommerceMandate createIntent(
            UUID agentId,
            String buyerUserId,
            String intentText,
            List<String> categories,
            long maxTotalMinor,
            String currency,
            Instant expiresAt) {
        if (maxTotalMinor <= 0) {
            throw new CommerceMandateException("CEILING_INVALID", "maxTotalMinor must be positive");
        }
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new CommerceMandateException(
                    "EXPIRES_IN_PAST", "expiresAt must be in the future");
        }
        String normalizedCurrency = normalizeCurrency(currency);
        UUID id = UUID.randomUUID();
        String canonical =
                canonicalizer.canonicalizeMandate(
                        id,
                        CommerceMandateKind.INTENT,
                        null,
                        currentTenant(),
                        agentId,
                        buyerUserId,
                        normalizedCurrency,
                        maxTotalMinor,
                        null,
                        null,
                        null,
                        categories,
                        intentText,
                        expiresAt);
        PaymentSigningKey key = signingService.activeKey();

        CommerceMandate mandate = new CommerceMandate();
        mandate.setId(id);
        mandate.setKind(CommerceMandateKind.INTENT);
        mandate.setAgentId(agentId);
        mandate.setBuyerUserId(buyerUserId);
        mandate.setCurrency(normalizedCurrency);
        mandate.setMaxTotalMinor(maxTotalMinor);
        mandate.setCategories(writeJson(categories));
        mandate.setIntentText(intentText);
        mandate.setStatus(CommerceMandateStatus.ACTIVE);
        mandate.setCanonicalJson(canonical);
        mandate.setSignature(signingService.sign(key, canonical));
        mandate.setKeyId(key.getKeyId());
        mandate.setExpiresAt(expiresAt);
        CommerceMandate saved = mandateRepository.save(mandate);
        record(
                saved.getId(),
                "intent.created",
                buyerUserId,
                "ceiling=" + maxTotalMinor + " " + normalizedCurrency);
        return saved;
    }

    // ------------------------------------------------------------------- cart

    /**
     * Build a cart under an ACTIVE intent. Items are [{sku,name,qty, unit_price_minor}]; the total
     * is computed server-side (multiplyExact/addExact - never trusted from the caller) and must fit
     * inside the intent's remaining ceiling.
     */
    @Transactional
    public CommerceMandate createCart(
            UUID intentMandateId,
            List<Map<String, Object>> items,
            String payee,
            Instant expiresAt) {
        CommerceMandate intent = requireUsableIntent(intentMandateId);
        if (items == null || items.isEmpty()) {
            throw new CommerceMandateException("ITEMS_REQUIRED", "a cart needs at least one item");
        }
        long total = computeTotal(items);
        long committed = committedUnderIntent(intentMandateId);
        if (Math.addExact(committed, total) > intent.getMaxTotalMinor()) {
            throw new CommerceMandateException(
                    "OVER_CEILING",
                    "cart total "
                            + total
                            + " exceeds intent's remaining ceiling "
                            + (intent.getMaxTotalMinor() - committed));
        }
        Instant now = clock.instant();
        Instant cartExpiry = expiresAt != null ? expiresAt : intent.getExpiresAt();
        if (cartExpiry.isAfter(intent.getExpiresAt())) {
            cartExpiry = intent.getExpiresAt();
        }
        if (!cartExpiry.isAfter(now)) {
            throw new CommerceMandateException(
                    "EXPIRES_IN_PAST", "cart expiry must be in the future");
        }
        String itemsJson = writeJson(items);
        UUID id = UUID.randomUUID();
        String canonical =
                canonicalizer.canonicalizeMandate(
                        id,
                        CommerceMandateKind.CART,
                        intentMandateId,
                        currentTenant(),
                        intent.getAgentId(),
                        intent.getBuyerUserId(),
                        intent.getCurrency(),
                        null,
                        total,
                        itemsJson,
                        payee,
                        null,
                        null,
                        cartExpiry);
        PaymentSigningKey key = signingService.activeKey();

        CommerceMandate cart = new CommerceMandate();
        cart.setId(id);
        cart.setKind(CommerceMandateKind.CART);
        cart.setIntentMandateId(intentMandateId);
        cart.setAgentId(intent.getAgentId());
        cart.setBuyerUserId(intent.getBuyerUserId());
        cart.setCurrency(intent.getCurrency());
        cart.setCartTotalMinor(total);
        cart.setItemsJson(itemsJson);
        cart.setPayee(payee);
        cart.setStatus(CommerceMandateStatus.PENDING_AUTHORIZATION);
        cart.setCanonicalJson(canonical);
        cart.setSignature(signingService.sign(key, canonical));
        cart.setKeyId(key.getKeyId());
        cart.setExpiresAt(cartExpiry);
        CommerceMandate saved = mandateRepository.save(cart);
        record(
                saved.getId(),
                "cart.created",
                currentSubject(),
                "intent=" + intentMandateId + " total=" + total + " " + intent.getCurrency());
        return saved;
    }

    /**
     * The buyer signs the cart: verifies the cart's own signature, re-checks the ceiling (siblings
     * may have committed since creation), then records a signed buyer-authorization document bound
     * to the cart hash.
     */
    @Transactional
    public CommerceMandate authorize(UUID cartMandateId) {
        CommerceMandate cart = requireMandate(cartMandateId);
        if (cart.getKind() != CommerceMandateKind.CART) {
            throw new CommerceMandateException("NOT_A_CART", "only CART mandates are authorized");
        }
        if (cart.getStatus() != CommerceMandateStatus.PENDING_AUTHORIZATION) {
            throw new CommerceMandateException(
                    "WRONG_STATE",
                    "cart is " + cart.getStatus() + ", expected PENDING_AUTHORIZATION");
        }
        String subject = currentSubject();
        if (!cart.getBuyerUserId().equals(subject)) {
            // Only the intent's authorizing buyer may sign its carts.
            throw new CommerceMandateException(
                    "NOT_THE_BUYER", "only the buyer named on the intent may authorize this cart");
        }
        if (!cart.getExpiresAt().isAfter(clock.instant())) {
            throw new CommerceMandateException("CART_EXPIRED", "cart expired before authorization");
        }
        if (!signingService.verify(cart.getKeyId(), cart.getCanonicalJson(), cart.getSignature())) {
            throw new CommerceMandateException("BAD_SIGNATURE", "cart signature does not verify");
        }
        CommerceMandate intent = requireMandate(cart.getIntentMandateId());
        long committed = committedUnderIntent(cart.getIntentMandateId());
        if (Math.addExact(committed, cart.getCartTotalMinor()) > intent.getMaxTotalMinor()) {
            throw new CommerceMandateException(
                    "OVER_CEILING", "authorizing would exceed the intent's ceiling");
        }

        Instant now = clock.instant();
        String authJson =
                canonicalizer.canonicalizeBuyerAuth(
                        cart.getId(),
                        cart.getIntentMandateId(),
                        canonicalizer.canonicalHash(cart.getCanonicalJson()),
                        subject,
                        cart.getCartTotalMinor(),
                        cart.getCurrency(),
                        now);
        PaymentSigningKey key = signingService.activeKey();
        cart.applyBuyerAuthorization(authJson, signingService.sign(key, authJson), now);
        CommerceMandate saved = mandateRepository.save(cart);
        record(
                saved.getId(),
                "cart.authorized",
                subject,
                "cart_hash=" + canonicalizer.canonicalHash(cart.getCanonicalJson()));
        return saved;
    }

    /**
     * Settle an AUTHORIZED cart: debit the agent's wallet for the cart total. The debit's
     * idempotency key is the cart id itself - a retried capture folds onto the first, never
     * double-spends.
     */
    @Transactional
    public CommerceMandate capture(UUID cartMandateId) {
        CommerceMandate cart = requireMandate(cartMandateId);
        if (cart.getStatus() != CommerceMandateStatus.AUTHORIZED) {
            throw new CommerceMandateException(
                    "WRONG_STATE", "cart is " + cart.getStatus() + ", expected AUTHORIZED");
        }
        if (cart.getBuyerAuthJson() == null
                || cart.getBuyerSignature() == null
                || !signingService.verify(
                        cart.getKeyId(), cart.getBuyerAuthJson(), cart.getBuyerSignature())) {
            throw new CommerceMandateException(
                    "BAD_BUYER_AUTH", "buyer authorization does not verify");
        }
        try {
            AgentWallet wallet = walletService.getOrCreate(cart.getAgentId(), cart.getCurrency());
            WalletLedgerEntry debit =
                    walletService.debit(
                            wallet.getId(),
                            cart.getCartTotalMinor(),
                            "cart:" + cart.getId(),
                            "commerce_mandate",
                            cart.getId().toString(),
                            "cart capture: " + nullToEmpty(cart.getPayee()));
            cart.markCaptured(debit.getId(), clock.instant());
        } catch (WalletException e) {
            cart.markFailed(clock.instant());
            mandateRepository.save(cart);
            record(cart.getId(), "cart.capture_failed", currentSubject(), e.getCode());
            throw new CommerceMandateException(
                    "CAPTURE_FAILED", e.getCode() + ": " + e.getMessage());
        }
        CommerceMandate saved = mandateRepository.save(cart);
        record(
                saved.getId(),
                "cart.captured",
                currentSubject(),
                "debit_ref=" + saved.getWalletDebitRef());
        return saved;
    }

    /**
     * Refund a CAPTURED cart: credit the agent's wallet back. Idempotent - reference key is the
     * cart id, so a second call replays to REFUNDED state check and fails fast.
     */
    @Transactional
    public CommerceMandate refund(UUID cartMandateId, String reason) {
        CommerceMandate cart = requireMandate(cartMandateId);
        if (cart.getStatus() != CommerceMandateStatus.CAPTURED) {
            throw new CommerceMandateException(
                    "WRONG_STATE",
                    "only a CAPTURED cart can be refunded (cart is " + cart.getStatus() + ")");
        }
        AgentWallet wallet = walletService.getOrCreate(cart.getAgentId(), cart.getCurrency());
        WalletLedgerEntry credit =
                walletService.credit(
                        wallet.getId(),
                        cart.getCartTotalMinor(),
                        "refund:" + cart.getId(),
                        "commerce_mandate",
                        cart.getId().toString(),
                        "refund: " + nullToEmpty(reason));
        cart.markRefunded(clock.instant());
        CommerceMandate saved = mandateRepository.save(cart);
        record(
                saved.getId(),
                "cart.refunded",
                currentSubject(),
                "credit_ref=" + credit.getId() + " reason=" + reason);
        return saved;
    }

    @Transactional
    public CommerceMandate revoke(UUID mandateId, String reason) {
        CommerceMandate mandate = mandateRepository.findById(mandateId).orElse(null);
        if (mandate == null) {
            return null;
        }
        mandate.markRevoked(currentSubject(), reason, clock.instant());
        CommerceMandate saved = mandateRepository.save(mandate);
        record(mandateId, "mandate.revoked", currentSubject(), reason);
        audit("commerce_mandate.revoked", mandateId.toString(), AuditOutcome.APPROVED, reason);
        return saved;
    }

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<CommerceMandate> listForAgent(UUID agentId) {
        return mandateRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    @Transactional(readOnly = true)
    public List<CommerceMandate> cartsOf(UUID intentMandateId) {
        return mandateRepository.findByIntentMandateIdOrderByCreatedAtDesc(intentMandateId);
    }

    @Transactional(readOnly = true)
    public CommerceMandate findById(UUID id) {
        return mandateRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<CommerceMandateEvent> events(UUID mandateId) {
        return eventRepository.findByMandateIdOrderByIdAsc(mandateId);
    }

    /** Verify a mandate's stored signature - public surface for auditors. */
    @Transactional(readOnly = true)
    public boolean verifySignature(UUID mandateId) {
        CommerceMandate m = mandateRepository.findById(mandateId).orElse(null);
        return m != null
                && signingService.verify(m.getKeyId(), m.getCanonicalJson(), m.getSignature());
    }

    // ---------------------------------------------------------------- internals

    private CommerceMandate requireMandate(UUID id) {
        return mandateRepository
                .findById(id)
                .orElseThrow(() -> new CommerceMandateException("NOT_FOUND", "no such mandate"));
    }

    private CommerceMandate requireUsableIntent(UUID id) {
        CommerceMandate intent = requireMandate(id);
        if (intent.getKind() != CommerceMandateKind.INTENT) {
            throw new CommerceMandateException("NOT_AN_INTENT", "mandate is not an INTENT");
        }
        if (intent.getStatus() != CommerceMandateStatus.ACTIVE) {
            throw new CommerceMandateException(
                    "INTENT_INACTIVE", "intent is " + intent.getStatus());
        }
        if (!intent.getExpiresAt().isAfter(clock.instant())) {
            throw new CommerceMandateException("INTENT_EXPIRED", "intent expired");
        }
        return intent;
    }

    /** Sum of sibling carts that already hold authorization - the committed spend. */
    private long committedUnderIntent(UUID intentMandateId) {
        return mandateRepository.findByIntentMandateIdOrderByCreatedAtDesc(intentMandateId).stream()
                .filter(
                        c ->
                                c.getStatus() == CommerceMandateStatus.AUTHORIZED
                                        || c.getStatus() == CommerceMandateStatus.CAPTURED)
                .mapToLong(CommerceMandate::getCartTotalMinor)
                .sum();
    }

    /** Server-side cart total from item qty * unit_price_minor; never caller-totaled. */
    private long computeTotal(List<Map<String, Object>> items) {
        long total = 0;
        for (Map<String, Object> item : items) {
            long qty = positiveLong(item.get("qty"), "qty");
            long unit = positiveLong(item.get("unit_price_minor"), "unit_price_minor");
            total = Math.addExact(total, Math.multiplyExact(qty, unit));
        }
        if (total <= 0) {
            throw new CommerceMandateException("TOTAL_INVALID", "cart total must be positive");
        }
        return total;
    }

    private static long positiveLong(Object value, String field) {
        if (value instanceof Number n) {
            long v = n.longValue();
            if (v > 0) {
                return v;
            }
        }
        throw new CommerceMandateException("ITEM_INVALID", "item needs a positive " + field);
    }

    private void record(UUID mandateId, String eventType, String actor, String detail) {
        eventRepository.save(CommerceMandateEvent.of(mandateId, eventType, actor, detail));
    }

    private void audit(String action, String targetId, AuditOutcome outcome, String detail) {
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(
                        currentSubject(), action, "commerce_mandate", targetId, outcome, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {}: {}", action, e.toString());
        }
    }

    private String currentTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeCurrency(String currency) {
        if (!StringUtils.hasText(currency) || currency.trim().length() != 3) {
            throw new CommerceMandateException(
                    "CURRENCY_INVALID", "currency must be a 3-letter code");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
