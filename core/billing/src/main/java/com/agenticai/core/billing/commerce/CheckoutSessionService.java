package com.agenticai.core.billing.commerce;

import com.agenticai.core.billing.gateway.GatewayUpiCollectRequest;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The one writer of {@code control_plane.checkout_session} - see this
 * package's {@code package-info} for what this is and is not.
 *
 * <p>Two entry points, two callers:
 * <ul>
 *   <li>{@link #createPaymentLink} - the {@code create_payment_link} agent
 *       tool (core/agents). Inserts the row FIRST as
 *       {@code NOT_READY_FOR_PAYMENT}, then asks the gateway for the UPI
 *       link with this row's id stamped in the link's {@code notes} - so
 *       a Razorpay-side link always points back at a local row even if the
 *       follow-up UPDATE fails, and never the reverse (a paid link with no
 *       row to attribute it to, which is exactly the gap {@code
 *       UpiPaymentConfirmedHandler}'s original javadoc documented).</li>
 *   <li>{@link #recordGatewayPayment} - {@code UpiPaymentConfirmedHandler},
 *       on the webhook thread (no {@code TenantContext}; the repository
 *       needs none). Idempotent via the state-guarded UPDATE; publishes
 *       {@link CheckoutSessionCompletedEvent} only on the real transition.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code JdbcTemplate} against a
 * fully-qualified control-plane table, one statement per step, same as
 * {@code InvoiceService}. The gateway call in the middle of {@link
 * #createPaymentLink} is a network round trip that must not sit inside an
 * open DB transaction anyway.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class CheckoutSessionService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSessionService.class);

    /**
     * Razorpay's documented minimum for a Payment Link is 100 paise (Re 1) -
     * same floor {@code RazorpayCheckoutController#createUpiCollect} enforces.
     */
    public static final long MIN_AMOUNT_PAISE = 100L;

    /** Console list page size ceiling - a tenant with thousands of sessions gets the newest N, not the table. */
    static final int MAX_LIST_LIMIT = 200;

    /** What the agent tool passes in - everything the row needs except what the service itself derives. */
    public record CreatePaymentLinkCommand(
        String tenantId,
        UUID agentId,
        String sessionId,
        String channel,
        long amountPaise,
        String description,
        String customerPhone,
        String customerName
    ) {
    }

    /** Outcome of a webhook reconciliation - see {@link #recordGatewayPayment}. */
    public enum ReconciliationResult {
        /** No local row carries this gateway id - a link created outside this package (e.g. the raw checkout controller). */
        NOT_FOUND,
        /** The row just transitioned to COMPLETED; the completed event was published. */
        COMPLETED,
        /** Already COMPLETED before this call - a redelivered webhook. Nothing changed, nothing published. */
        ALREADY_COMPLETED,
        /** The gateway reports a paid amount that differs from the row's total - flagged for a human, NOT completed. */
        AMOUNT_MISMATCH
    }

    public record Reconciliation(ReconciliationResult result, CheckoutSession session) {
    }

    private final CheckoutSessionRepository repository;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final long maxAmountPaise;
    private final Duration expiry;

    // Two constructors (this one + the package-private test seam below), so
    // Spring needs to be told which one to inject through - without this the
    // context fails at boot with "No default constructor found".
    @Autowired
    public CheckoutSessionService(
        CheckoutSessionRepository repository,
        PaymentGatewayRegistry gatewayRegistry,
        ApplicationEventPublisher events,
        // UPI's per-transaction ceiling for ordinary merchant collections is
        // Rs 1,00,000 (NPCI; higher caps exist only for specific categories),
        // so anything above it would fail at the customer's bank anyway -
        // better to refuse here with a clear message than mint a link that
        // can never be paid. Configurable for tenants in an exempted category.
        @Value("${app.commerce.checkout.max-amount-paise:10000000}") long maxAmountPaise,
        // App-side expiry (see package-info's "known v0 limits") - matches
        // the audit's proposed 24h expire_by for the gateway link.
        @Value("${app.commerce.checkout.expiry-hours:24}") long expiryHours
    ) {
        this(repository, gatewayRegistry, events, Clock.systemUTC(), maxAmountPaise, Duration.ofHours(expiryHours));
    }

    /** Test seam - injectable clock so expiry/paid-at assertions are deterministic. */
    CheckoutSessionService(
        CheckoutSessionRepository repository,
        PaymentGatewayRegistry gatewayRegistry,
        ApplicationEventPublisher events,
        Clock clock,
        long maxAmountPaise,
        Duration expiry
    ) {
        this.repository = repository;
        this.gatewayRegistry = gatewayRegistry;
        this.events = events;
        this.clock = clock;
        this.maxAmountPaise = maxAmountPaise;
        this.expiry = expiry;
    }

    /**
     * Cheap, non-throwing gate {@code AgentToolProvider} uses to decide
     * whether to offer the commerce tools at all (mirrors the {@code
     * isActive()}-style checks every other optional tool family uses): true
     * when the configured default gateway has a registered client. A
     * gateway that exists but doesn't implement UPI collect (PhonePe) still
     * passes this gate and fails clearly at call time instead - that is a
     * misconfiguration worth surfacing, not hiding.
     */
    public boolean isPaymentGatewayAvailable() {
        try {
            gatewayRegistry.resolve(null);
            return true;
        } catch (IllegalArgumentException noDefaultGateway) {
            return false;
        }
    }

    /**
     * @throws IllegalArgumentException on a bad amount/description - the
     *     caller (the tool) turns this into a model-readable error
     * @throws PaymentGatewayException when the gateway refused the link; the
     *     row is left CANCELED with the reason in {@code review_note}
     */
    public CheckoutSession createPaymentLink(CreatePaymentLinkCommand command) {
        return createPaymentLink(command, null);
    }

    /**
     * {@link #createPaymentLink(CreatePaymentLinkCommand)} with caller-supplied
     * ACP-shaped {@code line_items} - used by the product-feed checkout ({@code
     * ProductFeedService}), which prices a real cart server-side and must carry
     * more than one item. {@code null}/blank keeps the single-line default the
     * agent tool produces.
     */
    public CheckoutSession createPaymentLink(CreatePaymentLinkCommand command, String lineItemsJson) {
        validate(command);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String description = command.description().trim();
        PaymentGatewayClient gateway = gatewayRegistry.resolve(null);

        CheckoutSession pending = new CheckoutSession(
            id, command.tenantId(), command.agentId(), command.sessionId(),
            command.channel() == null || command.channel().isBlank() ? "agent" : command.channel(),
            nullIfBlank(command.customerPhone()), nullIfBlank(command.customerName()),
            "INR", command.amountPaise(), description,
            lineItemsJson == null || lineItemsJson.isBlank()
                ? singleLineItem(description, command.amountPaise())
                : lineItemsJson,
            CheckoutSessionStatus.NOT_READY_FOR_PAYMENT, gateway.gatewayName(),
            null, null, null, null, null, now, now, now.plus(expiry)
        );
        repository.insert(pending);

        // Stamped into the gateway link's notes so the Razorpay dashboard
        // record is traceable back to this row (and tenant) by a human even
        // if everything after this call failed - never relied on as the
        // source of truth for completion (gateway_ref_id on the row is).
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("checkout_session_id", id.toString());
        notes.put("tenant_id", command.tenantId());
        if (command.agentId() != null) {
            notes.put("agent_id", command.agentId().toString());
        }

        GatewayUpiCollectRequest link;
        try {
            link = gateway.createUpiCollectRequest(
                command.tenantId(), command.amountPaise(), description,
                pending.customerName(), pending.customerPhone(), null, notes,
                // The session's own expiry goes gateway-side too, so the
                // hosted link stops being payable when the session expires
                // (expire_by overload; null-tolerant default for gateways
                // without a link-expiry surface).
                pending.expiresAt()
            );
        } catch (PaymentGatewayException | UnsupportedOperationException e) {
            // Leave a CANCELED row rather than deleting: the attempt is part
            // of the audit trail, and the console should show WHY a customer
            // never got a link.
            repository.markCanceled(id, "Gateway refused to create the payment link: " + e.getMessage(), clock.instant());
            log.warn("CHECKOUT_SESSION_LINK_FAILED id={} tenant={} amountPaise={} gateway={}",
                id, command.tenantId(), command.amountPaise(), gateway.gatewayName(), e);
            if (e instanceof PaymentGatewayException gatewayFailure) {
                throw gatewayFailure;
            }
            throw new PaymentGatewayException(
                "Gateway '" + gateway.gatewayName() + "' does not support UPI payment links", e);
        }

        Instant readyAt = clock.instant();
        Instant expiresAt = readyAt.plus(expiry);
        repository.markReadyForPayment(id, link.id(), link.shortUrl(), expiresAt, readyAt);
        log.info("CHECKOUT_SESSION_READY id={} tenant={} agent={} amountPaise={} gatewayRef={}",
            id, command.tenantId(), command.agentId(), command.amountPaise(), link.id());
        // Re-read rather than hand-assembling the READY row, so the tool's
        // response reflects exactly what the database holds.
        return repository.findById(id).orElseThrow(() -> new IllegalStateException(
            "checkout_session " + id + " vanished between insert and read"));
    }

    /**
     * Tenant-scoped read with the lazy READY -&gt; EXPIRED transition applied
     * (see {@link CheckoutSessionStatus}). Empty for another tenant's id,
     * indistinguishable from a nonexistent one - same posture as {@code
     * InvoiceRepository#findByIdAndTenantId}.
     */
    public Optional<CheckoutSession> getForTenant(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId).map(this::applyLazyExpiry);
    }

    public List<CheckoutSession> listForTenant(String tenantId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return repository.listByTenantId(tenantId, bounded).stream().map(this::applyLazyExpiry).toList();
    }

    /**
     * The webhook-side reconciliation. Order of checks matters and is
     * deliberate:
     * <ol>
     *   <li>No row for this gateway id -&gt; {@code NOT_FOUND}; the caller
     *       keeps its pre-existing "log it so a human can reconcile" line.</li>
     *   <li>Already COMPLETED -&gt; {@code ALREADY_COMPLETED}, nothing
     *       written, nothing published (Razorpay redelivers on any non-2xx
     *       and occasionally on 2xx too).</li>
     *   <li>Gateway says a DIFFERENT amount was paid than this row asked for
     *       -&gt; {@code AMOUNT_MISMATCH}: the row is flagged via {@code
     *       review_note} and NOT completed. Under-payment cannot happen on a
     *       fixed-amount Razorpay link in practice, so hitting this means
     *       something is genuinely wrong and a human must look before
     *       anything downstream treats the order as paid. An unknown
     *       amount (lookup unavailable) is NOT a mismatch.</li>
     *   <li>Otherwise the state-guarded UPDATE runs; 1 row -&gt; publish the
     *       completed event, 0 rows -&gt; a concurrent delivery won the race,
     *       report {@code ALREADY_COMPLETED}.</li>
     * </ol>
     *
     * @param payment what {@link PaidPaymentLookup} found, or null when it
     *                was unavailable/empty
     */
    public Reconciliation recordGatewayPayment(String gatewayRefId, PaidPaymentLookup.PaidPayment payment) {
        Optional<CheckoutSession> found = repository.findByGatewayRefId(gatewayRefId);
        if (found.isEmpty()) {
            return new Reconciliation(ReconciliationResult.NOT_FOUND, null);
        }
        CheckoutSession session = found.get();
        if (session.status() == CheckoutSessionStatus.COMPLETED) {
            return new Reconciliation(ReconciliationResult.ALREADY_COMPLETED, session);
        }
        Instant now = clock.instant();
        if (payment != null && payment.amountPaidPaise() != null && payment.amountPaidPaise() != session.amountPaise()) {
            String note = "Amount mismatch: gateway reports " + payment.amountPaidPaise() + " paise paid"
                + (payment.paymentId() != null ? " (payment " + payment.paymentId() + ")" : "")
                + " but this session is for " + session.amountPaise() + " paise - not completed, needs manual review";
            repository.setReviewNote(session.id(), note, now);
            return new Reconciliation(ReconciliationResult.AMOUNT_MISMATCH, repository.findById(session.id()).orElse(session));
        }
        String paymentId = payment == null ? null : payment.paymentId();
        int updated = repository.markCompleted(session.id(), paymentId, now);
        CheckoutSession completed = repository.findById(session.id()).orElse(session);
        if (updated == 0) {
            return new Reconciliation(ReconciliationResult.ALREADY_COMPLETED, completed);
        }
        // Best-effort by the same contract every other in-JVM event in this
        // codebase follows (WorkflowEventPublisher's javadoc): a broken
        // subscriber must never make a genuinely paid session look unpaid to
        // the gateway (which would retry) or to the caller.
        try {
            events.publishEvent(new CheckoutSessionCompletedEvent(completed));
        } catch (RuntimeException e) {
            log.error("CheckoutSessionCompletedEvent fan-out failed for checkout_session {} - the row IS completed; "
                + "downstream listeners (workflow trigger, notifications) did not run", session.id(), e);
        }
        return new Reconciliation(ReconciliationResult.COMPLETED, completed);
    }

    private CheckoutSession applyLazyExpiry(CheckoutSession session) {
        Instant now = clock.instant();
        if (!session.isPastExpiry(now)) {
            return session;
        }
        // State-guarded: a webhook completing this row between our read and
        // this write wins (0 rows updated), and we report what the row now
        // actually says rather than a stale EXPIRED.
        if (repository.markExpired(session.id(), now) == 0) {
            return repository.findById(session.id()).orElse(session);
        }
        return session.withStatus(CheckoutSessionStatus.EXPIRED);
    }

    private void validate(CreatePaymentLinkCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (command.amountPaise() < MIN_AMOUNT_PAISE) {
            throw new IllegalArgumentException("Amount must be at least " + MIN_AMOUNT_PAISE + " paise (Re 1)");
        }
        if (command.amountPaise() > maxAmountPaise) {
            throw new IllegalArgumentException(
                "Amount exceeds the configured per-payment ceiling of " + maxAmountPaise + " paise");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        // Razorpay caps Payment Link descriptions at 2048 chars; keep well
        // under so the same text also fits a WhatsApp message comfortably.
        if (command.description().trim().length() > 500) {
            throw new IllegalArgumentException("description must be 500 characters or fewer");
        }
    }

    /**
     * ACP-shaped {@code line_items}: the v0 tool takes one amount and one
     * description, so the row carries exactly one item whose total equals
     * the session total - the shape a later multi-item writer keeps.
     */
    static String singleLineItem(String description, long amountPaise) {
        JSONObject item = new JSONObject();
        item.put("id", "item_1");
        item.put("name", description);
        item.put("quantity", 1);
        item.put("unit_amount", amountPaise);
        item.put("total", amountPaise);
        return new JSONArray().put(item).toString();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
