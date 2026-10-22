package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.agents.trust.DelegationException;
import com.agenticai.core.agents.trust.DelegationTokenService;
import com.agenticai.core.agents.trust.DelegationTokenService.VerifiedDelegation;
import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.security.TenantContext;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The inbound x402 <b>seller</b> surface - the complement to {@link X402PaymentService}'s outbound
 * payer. A caller hitting a paid resource gets a 402 carrying an {@code accepts} list; it retries
 * with {@code X-PAYMENT}; this service verifies and settles.
 *
 * <p><b>Two schemes:</b>
 *
 * <ul>
 *   <li>{@code vegaduta-delegation} (internal): the payer presents a delegation token ({@link
 *       DelegationTokenService}) carrying a {@code pay:x402} scope, an {@code aud} bound to this
 *       offer, and a pinned {@code wallet_id}. Settlement is a wallet debit (buyer's tenant schema)
 *       + credit (seller's tenant schema) - two sequential transactions, since each tenant schema
 *       is a different connection; the {@code x402_payment} row records both ledger refs for
 *       reconciliation, and a credit failure triggers a compensating refund debit-reversal.
 *   <li>{@code exact} (EVM): verifies the EIP-3009 {@code transferWithAuthorization} signature
 *       cryptographically (reusing {@link X402PaymentService}'s EIP-712 hashing and {@link
 *       EvmWallet#recoverAddress}). We verify, we do NOT broadcast - the record lands as {@code
 *       VERIFIED} and on-chain settlement is the facilitator's job downstream.
 * </ul>
 *
 * <p><b>Replay guard:</b> {@code x402_payment.nonce} is UNIQUE. Internal payments use {@code
 * <jti>:<call_nonce>} (the call nonce is payer-chosen, so a stolen header replays to the same nonce
 * and folds); EVM payments use the authorization's own nonce. A nonce seen before returns the
 * original settlement - paying twice for one call is impossible.
 *
 * <p><b>Spend bound:</b> a {@code pay:x402} payment is bounded twice - by the grant's cumulative
 * {@code spend_cap_minor} (recorded under the grant's FOR UPDATE lock via {@link
 * DelegationTokenService#recordSpend}, released by the compensator if the seller leg fails) AND by
 * the buyer wallet's balance.
 */
@Service
public class X402SellerService {

    private static final Logger log = LoggerFactory.getLogger(X402SellerService.class);

    /** The internal scheme name - delegation-token payment between agent wallets. */
    public static final String SCHEME_DELEGATION = "vegaduta-delegation";

    /** The EVM scheme name - EIP-3009 transferWithAuthorization. */
    public static final String SCHEME_EXACT = "exact";

    /** The delegation-token audience format payers must bind to: {@code x402://<slug>}. */
    public static final String AUDIENCE_PREFIX = "x402://";

    /** The scope a delegation token must carry to pay. */
    public static final String SCOPE_PAY = "pay:x402";

    private final X402OfferRepository offerRepository;
    private final X402PaymentRepository paymentRepository;
    private final DelegationTokenService delegationTokenService;
    private final AgentWalletLedger walletService;
    private final Clock clock;

    /** Base URL of an x402 facilitator for on-chain settlement; blank = verify-only. */
    private final String facilitatorUrl;

    private final int facilitatorTimeoutMs;

    @Autowired
    public X402SellerService(
            X402OfferRepository offerRepository,
            X402PaymentRepository paymentRepository,
            DelegationTokenService delegationTokenService,
            ObjectProvider<AgentWalletLedger> walletLedger,
            @org.springframework.beans.factory.annotation.Value("${app.x402.facilitator-url:}")
                    String facilitatorUrl,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.x402.facilitator-timeout-ms:10000}")
                    int facilitatorTimeoutMs) {
        // The payments module (the BILLING_PAYMENTS edition feature) may be absent: x402 then
        // refuses
        // settlement with a "payments disabled" answer instead of blocking startup.
        this(
                offerRepository,
                paymentRepository,
                delegationTokenService,
                PaymentsDisabledAgentWalletLedger.orDisabled(walletLedger),
                Clock.systemUTC(),
                facilitatorUrl,
                facilitatorTimeoutMs);
    }

    /** Test seam - fixed clock. */
    X402SellerService(
            X402OfferRepository offerRepository,
            X402PaymentRepository paymentRepository,
            DelegationTokenService delegationTokenService,
            AgentWalletLedger walletService,
            Clock clock) {
        this(
                offerRepository,
                paymentRepository,
                delegationTokenService,
                walletService,
                clock,
                "",
                10_000);
    }

    X402SellerService(
            X402OfferRepository offerRepository,
            X402PaymentRepository paymentRepository,
            DelegationTokenService delegationTokenService,
            AgentWalletLedger walletService,
            Clock clock,
            String facilitatorUrl,
            int facilitatorTimeoutMs) {
        this.offerRepository = offerRepository;
        this.paymentRepository = paymentRepository;
        this.delegationTokenService = delegationTokenService;
        this.walletService = walletService;
        this.clock = clock;
        this.facilitatorUrl = facilitatorUrl == null ? "" : facilitatorUrl.strip();
        this.facilitatorTimeoutMs = facilitatorTimeoutMs;
    }

    /** Resolve an ACTIVE offer by slug; absent/disabled are indistinguishable. */
    public Optional<X402Offer> findOffer(String slug) {
        return offerRepository.findBySlugAndStatus(slug, X402Offer.Status.ACTIVE);
    }

    /**
     * The 402 body ({@code {x402Version:1, accepts:[...]}}) for an offer. Every requirement names
     * the audience the internal scheme binds to and the resource URL, per the x402 v1 wire shape.
     */
    public Map<String, Object> paymentRequirements(X402Offer offer, String resourceUrl) {
        java.util.List<Map<String, Object>> accepts = new java.util.ArrayList<>();
        accepts.add(
                Map.of(
                        "scheme",
                        SCHEME_DELEGATION,
                        "network",
                        "vegaduta",
                        "maxAmountRequired",
                        Long.toString(offer.getPriceMinor()),
                        "asset",
                        offer.getCurrency(),
                        "payTo",
                        "wallet:" + offer.getWalletId(),
                        "resource",
                        resourceUrl,
                        "extra",
                        Map.of(
                                "audience",
                                AUDIENCE_PREFIX + offer.getSlug(),
                                "scope",
                                SCOPE_PAY,
                                "jwks_hint",
                                "/.well-known/jwks.json")));
        if (offer.hasEvmRequirement()) {
            accepts.add(
                    Map.of(
                            "scheme", SCHEME_EXACT,
                            "network", offer.getEvmNetwork(),
                            "maxAmountRequired", Long.toString(offer.getEvmPriceAtomic()),
                            "asset", offer.getEvmAsset(),
                            "payTo", offer.getPayTo(),
                            "resource", resourceUrl,
                            "extra",
                                    Map.of(
                                            "name", offer.getEvmAssetName(),
                                            "version", offer.getEvmAssetVersion())));
        }
        return Map.of("x402Version", 1, "accepts", accepts);
    }

    /**
     * Verify + settle one presented {@code X-PAYMENT} header. Returns the settled payment record;
     * throws {@link X402Exception} on any failure. Idempotent on nonce - a retried request returns
     * the original record.
     */
    public X402PaymentRecord settle(X402Offer offer, String xPaymentHeader) {
        JSONObject payment = parsePaymentHeader(xPaymentHeader);
        String scheme = payment.optString("scheme", "");
        return switch (scheme) {
            case SCHEME_DELEGATION -> settleInternal(offer, payment);
            case SCHEME_EXACT -> settleEvm(offer, payment);
            default ->
                    throw new X402Exception(
                            "UNSUPPORTED_SCHEME", "scheme " + scheme + " is not accepted here");
        };
    }

    // --------------------------------------------------------- internal scheme

    private X402PaymentRecord settleInternal(X402Offer offer, JSONObject payment) {
        JSONObject payload = payment.optJSONObject("payload");
        String token = payload == null ? null : payload.optString("token", null);
        String callNonce = payload == null ? null : payload.optString("call_nonce", null);
        if (!StringUtils.hasText(token) || !StringUtils.hasText(callNonce)) {
            throw new X402Exception(
                    "MALFORMED_PAYMENT",
                    "vegaduta-delegation payment needs payload.token + payload.call_nonce");
        }
        // Stateless half first - this endpoint runs with NO TenantContext, so
        // the DB-backed recorded checks must wait until the claim set yields
        // the buyer's tenant schema. verifyStateless never touches the DB.
        VerifiedDelegation d;
        try {
            d =
                    delegationTokenService.verifyStateless(
                            token, AUDIENCE_PREFIX + offer.getSlug(), SCOPE_PAY);
        } catch (DelegationException e) {
            throw new X402Exception("DELEGATION_" + e.getCode(), e.getMessage());
        }
        if (d.walletId() == null) {
            throw new X402Exception("NO_WALLET_BOUND", "token carries no wallet_id");
        }
        if (!StringUtils.hasText(d.tenantId()) || "unknown".equals(d.tenantId())) {
            throw new X402Exception(
                    "DELEGATION_NO_TENANT", "token carries no tenant_id - cannot settle");
        }
        String nonce = d.jti() + ":" + callNonce;

        // Replay fold: the nonce UNIQUE means the first settlement wins; a
        // retry of the same header returns it instead of double-charging.
        Optional<X402PaymentRecord> existing = paymentRepository.findByNonce(nonce);
        if (existing.isPresent()) {
            return existing.get();
        }

        X402PaymentRecord record = newPaymentRecord(offer, payment);
        record.setScheme(SCHEME_DELEGATION);
        record.setNetwork("vegaduta");
        record.setNonce(nonce);
        record.setPayerTenantId(d.tenantId());
        record.setPayerRef(d.walletId().toString());
        record.setAmountAtomic(offer.getPriceMinor());
        try {
            record = paymentRepository.save(record);
        } catch (DataIntegrityViolationException race) {
            return paymentRepository.findByNonce(nonce).orElseThrow(() -> race);
        }
        final String recordId = record.getId().toString();
        final String jti = d.jti();
        // Whether recordSpend committed its increment - the compensator only
        // releases when it did, so a cap rejection can't drain the counter
        // below other legitimate spend.
        final java.util.concurrent.atomic.AtomicBoolean spendRecorded =
                new java.util.concurrent.atomic.AtomicBoolean();

        try {
            // Leg 1, buyer tenant: revocation/grant checks + cumulative spend
            // cap + wallet debit. recordSpend runs under the grant's FOR
            // UPDATE lock so concurrent calls serialize on the counter.
            Long debit =
                    inTenant(
                            d.tenantId(),
                            () -> {
                                try {
                                    delegationTokenService.recordSpend(jti, offer.getPriceMinor());
                                    spendRecorded.set(true);
                                } catch (DelegationException e) {
                                    throw new X402Exception(
                                            "DELEGATION_" + e.getCode(), e.getMessage());
                                }
                                return walletService.debit(
                                        d.walletId(),
                                        offer.getPriceMinor(),
                                        "x402:" + nonce,
                                        "x402",
                                        recordId,
                                        "x402 purchase: " + offer.getSlug());
                            });
            record.setBuyerLedgerRef(debit);
            // Leg 2: credit the SELLER's wallet, in the seller's tenant schema.
            Long credit =
                    inTenant(
                            offer.getTenantId(),
                            () ->
                                    walletService.credit(
                                            offer.getWalletId(),
                                            offer.getPriceMinor(),
                                            "x402-credit:" + nonce,
                                            "x402",
                                            recordId,
                                            "x402 sale: " + offer.getSlug()));
            record.setSellerLedgerRef(credit);
            record.setStatus(X402PaymentRecord.Status.SETTLED);
            record.setSettledAt(clock.instant());
        } catch (RuntimeException e) {
            // Any settlement-leg failure: refund the debit if it landed and
            // release the spend counter if it was recorded - the buyer is
            // made whole on both ledgers - then mark the record REJECTED.
            compensate(offer, record, d, nonce, spendRecorded.get());
            record.setStatus(X402PaymentRecord.Status.REJECTED);
            record.setRejectReason(shortReason(e));
            paymentRepository.save(record);
            throw new X402Exception(
                    "SETTLEMENT_FAILED", "payment could not settle: " + shortReason(e));
        }
        return paymentRepository.save(record);
    }

    /**
     * Compensating reversal when the seller leg fails after a buyer debit: refund the wallet AND
     * release the spend recorded against the grant's cumulative cap - the buyer is made whole on
     * both ledgers.
     */
    private void compensate(
            X402Offer offer,
            X402PaymentRecord record,
            VerifiedDelegation d,
            String nonce,
            boolean spendRecorded) {
        if (record.getBuyerLedgerRef() != null) {
            try {
                inTenant(
                        d.tenantId(),
                        () ->
                                walletService.credit(
                                        d.walletId(),
                                        offer.getPriceMinor(),
                                        "x402-refund:" + nonce,
                                        "x402",
                                        record.getId().toString(),
                                        "x402 settlement reversal: " + offer.getSlug()));
            } catch (RuntimeException e) {
                log.error(
                        "x402 compensating refund failed for payment {} - manual reconciliation needed: {}",
                        record.getId(),
                        e.toString());
            }
        }
        if (spendRecorded) {
            releaseSpendQuietly(d.tenantId(), d.jti(), offer.getPriceMinor());
        }
    }

    private void releaseSpendQuietly(String tenantId, String jti, long amountMinor) {
        try {
            inTenant(
                    tenantId,
                    () -> {
                        delegationTokenService.releaseSpend(jti, amountMinor);
                        return null;
                    });
        } catch (RuntimeException e) {
            log.error(
                    "x402 spend-cap release failed for jti {} - grant counter may over-count: {}",
                    jti,
                    e.toString());
        }
    }

    // -------------------------------------------------------------- EVM scheme

    private X402PaymentRecord settleEvm(X402Offer offer, JSONObject payment) {
        if (!offer.hasEvmRequirement()) {
            throw new X402Exception(
                    "NO_EVM_OPTION", "this offer does not accept exact/EVM payment");
        }
        JSONObject payload = payment.optJSONObject("payload");
        JSONObject auth = payload == null ? null : payload.optJSONObject("authorization");
        String signature = payload == null ? null : payload.optString("signature", null);
        if (auth == null || !StringUtils.hasText(signature)) {
            throw new X402Exception(
                    "MALFORMED_PAYMENT",
                    "exact payment needs payload.authorization + payload.signature");
        }
        String network = payment.optString("network", "");
        if (!network.equalsIgnoreCase(offer.getEvmNetwork())) {
            throw new X402Exception(
                    "WRONG_NETWORK", "expected " + offer.getEvmNetwork() + ", got " + network);
        }
        String from = auth.optString("from", "");
        String to = auth.optString("to", "");
        String nonce = auth.optString("nonce", "");
        BigInteger value, validAfter, validBefore;
        try {
            value = new BigInteger(auth.getString("value"));
            validAfter = new BigInteger(auth.optString("validAfter", "0"));
            validBefore = new BigInteger(auth.getString("validBefore"));
        } catch (RuntimeException e) {
            throw new X402Exception("MALFORMED_AUTHORIZATION", "authorization fields unparseable");
        }
        long now = clock.instant().getEpochSecond();
        if (validBefore.longValue() <= now) {
            throw new X402Exception("AUTH_EXPIRED", "authorization validBefore is in the past");
        }
        if (validAfter.longValue() > now) {
            throw new X402Exception("AUTH_NOT_YET", "authorization validAfter is in the future");
        }
        if (!to.equalsIgnoreCase(offer.getPayTo())) {
            throw new X402Exception("WRONG_PAYEE", "authorization.to does not match offer payTo");
        }
        if (value.compareTo(BigInteger.valueOf(offer.getEvmPriceAtomic())) < 0) {
            throw new X402Exception("UNDERPAID", "authorization.value below required price");
        }

        // Rebuild the EIP-712 digest exactly as the payer signed it, then
        // ecrecover the signer and require it IS authorization.from.
        byte[] domainSeparator =
                X402PaymentService.domainSeparator(
                        offer.getEvmAssetName(), offer.getEvmAssetVersion(),
                        X402PaymentService.chainIdFor(network), offer.getEvmAsset());
        byte[] structHash =
                X402PaymentService.transferAuthorizationHash(
                        from, to, value, validAfter, validBefore, EvmWallet.hexToBytes(nonce));
        byte[] digest =
                EvmWallet.keccak256(concat(new byte[] {0x19, 0x01}, domainSeparator, structHash));
        String recovered = recover(signature, digest);
        if (recovered == null || !recovered.equalsIgnoreCase(from)) {
            throw new X402Exception("BAD_SIGNATURE", "recovered signer is not authorization.from");
        }

        String recordNonce = "evm:" + from.toLowerCase(java.util.Locale.ROOT) + ":" + nonce;
        Optional<X402PaymentRecord> existing = paymentRepository.findByNonce(recordNonce);
        if (existing.isPresent()) {
            return existing.get();
        }
        X402PaymentRecord record = newPaymentRecord(offer, payment);
        record.setScheme(SCHEME_EXACT);
        record.setNetwork(network);
        record.setNonce(recordNonce);
        record.setPayerRef(from);
        try {
            record.setAmountAtomic(value.longValueExact());
        } catch (ArithmeticException e) {
            throw new X402Exception(
                    "AMOUNT_OVERFLOW", "authorization.value exceeds the bigint ledger width");
        }
        // VERIFIED first: the signature proves the authorization. When a
        // facilitator is configured we additionally settle on-chain and flip
        // to SETTLED; without one the record stays VERIFIED for downstream
        // settlement (we never broadcast ourselves).
        record.setStatus(X402PaymentRecord.Status.VERIFIED);
        record.setSettledAt(clock.instant());
        try {
            record = paymentRepository.save(record);
        } catch (DataIntegrityViolationException race) {
            return paymentRepository.findByNonce(recordNonce).orElseThrow(() -> race);
        }
        trySettleViaFacilitator(offer, payment, record);
        return paymentRepository.save(record);
    }

    /**
     * Optional on-chain settlement via a configured x402 facilitator ({@code
     * app.x402.facilitator-url}): POST {@code <url>/settle} with the presented payment plus the
     * matching requirement. A {@code success:true} response flips the record SETTLED and stores the
     * returned tx reference; anything else leaves it VERIFIED - the verification already happened,
     * settlement is retriable later.
     */
    private void trySettleViaFacilitator(
            X402Offer offer, JSONObject payment, X402PaymentRecord record) {
        if (!StringUtils.hasText(facilitatorUrl)) {
            return;
        }
        try {
            JSONObject req = new JSONObject();
            req.put("x402Version", 1);
            req.put("paymentPayload", payment);
            JSONObject requirement = new JSONObject();
            requirement.put("scheme", SCHEME_EXACT);
            requirement.put("network", offer.getEvmNetwork());
            requirement.put("maxAmountRequired", Long.toString(offer.getEvmPriceAtomic()));
            requirement.put("asset", offer.getEvmAsset());
            requirement.put("payTo", offer.getPayTo());
            requirement.put("resource", AUDIENCE_PREFIX + offer.getSlug());
            requirement.put(
                    "extra",
                    new JSONObject()
                            .put("name", offer.getEvmAssetName())
                            .put("version", offer.getEvmAssetVersion()));
            req.put("paymentRequirements", requirement);

            // Guarded transport: the facilitator URL is merchant configuration, but the
            // socket still resolves through SsrfGuard (no DNS-rebinding window).
            java.net.http.HttpClient client =
                    com.agenticai.core.security.net.GuardedJdkHttpClient.create(
                            com.agenticai.core.security.net.SsrfGuard.defaultGuard(),
                            java.time.Duration.ofMillis(facilitatorTimeoutMs),
                            java.time.Duration.ofMillis(facilitatorTimeoutMs),
                            false);
            java.net.http.HttpRequest httpReq =
                    java.net.http.HttpRequest.newBuilder()
                            .uri(
                                    java.net.URI.create(
                                            facilitatorUrl.replaceAll("/+$", "") + "/settle"))
                            .timeout(java.time.Duration.ofMillis(facilitatorTimeoutMs))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(req.toString()))
                            .build();
            java.net.http.HttpResponse<String> resp =
                    client.send(httpReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn(
                        "x402 facilitator /settle returned {} for payment {}",
                        resp.statusCode(),
                        record.getId());
                return;
            }
            JSONObject body = new JSONObject(resp.body());
            if (body.optBoolean("success", false)) {
                record.setStatus(X402PaymentRecord.Status.SETTLED);
                String txRef = body.optString("transaction", body.optString("txHash", ""));
                if (StringUtils.hasText(txRef)) {
                    record.setSettlementRef(txRef.length() > 160 ? txRef.substring(0, 160) : txRef);
                }
                log.info(
                        "x402 payment {} settled on-chain via facilitator: {}",
                        record.getId(),
                        txRef);
            } else {
                log.warn(
                        "x402 facilitator rejected payment {}: {}",
                        record.getId(),
                        body.optString("errorReason", resp.body()));
            }
        } catch (Exception e) {
            // Settlement failure never loses the verified payment - the record
            // stays VERIFIED and a retry/facilitator catch-up can settle later.
            log.warn(
                    "x402 facilitator call failed for payment {}: {}",
                    record.getId(),
                    e.toString());
        }
    }

    /** r(32)||s(32)||v(1) signature -> recovered address, or null. */
    private static String recover(String signature, byte[] digest) {
        byte[] sig = EvmWallet.hexToBytes(signature);
        if (sig.length != 65) {
            return null;
        }
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 32, 64));
        int v = sig[64] & 0xFF;
        int recId = (v == 27 || v == 28) ? v - 27 : v; // some wallets emit 0/1
        if (recId < 0 || recId > 1) {
            return null;
        }
        return EvmWallet.recoverAddress(r, s, recId, digest);
    }

    // ---------------------------------------------------------------- internals

    /** Run {@code work} with TenantContext pinned to a schema, then restore. */
    private <T> T inTenant(String schema, java.util.function.Supplier<T> work) {
        TenantContext previous = TenantContext.current().orElse(null);
        try {
            TenantContext.set(TenantContext.of(schema, "system:x402-settlement", Set.of()));
            return work.get();
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private X402PaymentRecord newPaymentRecord(X402Offer offer, JSONObject payment) {
        X402PaymentRecord record = new X402PaymentRecord();
        record.setId(UUID.randomUUID());
        record.setOfferId(offer.getId());
        record.setPayload(payment.toString());
        return record;
    }

    private static JSONObject parsePaymentHeader(String header) {
        if (!StringUtils.hasText(header)) {
            throw new X402Exception("NO_PAYMENT", "no X-PAYMENT header presented");
        }
        try {
            String json = header.trim();
            if (!json.startsWith("{")) {
                json = new String(Base64.getDecoder().decode(json), StandardCharsets.UTF_8);
            }
            return new JSONObject(json);
        } catch (RuntimeException e) {
            throw new X402Exception("MALFORMED_PAYMENT", "X-PAYMENT is not base64/JSON");
        }
    }

    private static String shortReason(RuntimeException e) {
        String msg =
                e instanceof AgentWalletLedger.WalletLedgerException w
                        ? w.getCode()
                        : e instanceof X402Exception x ? x.getCode() : e.getClass().getSimpleName();
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
