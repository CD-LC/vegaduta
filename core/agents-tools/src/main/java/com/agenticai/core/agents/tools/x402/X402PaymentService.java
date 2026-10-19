package com.agenticai.core.agents.tools.x402;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The outbound x402 payer: when an agent's HTTP call is answered {@code 402 Payment Required} with
 * an x402 {@code accepts} list, this service picks an affordable requirement and produces the
 * {@code X-PAYMENT} header that retries the call with payment attached.
 *
 * <p><b>Scope, deliberately:</b> the only supported scheme is {@code exact} on an EVM network via
 * EIP-3009 {@code transferWithAuthorization} (the USDC-style authorization a facilitator settles
 * on-chain). Nothing here broadcasts a transaction - the signed authorization is handed to the
 * server's facilitator via the header, which is the entire payer's side of the protocol.
 *
 * <p><b>Guardrails (all config):</b>
 *
 * <ul>
 *   <li>{@code app.x402.enabled} (default false) - the whole feature is opt-in; with no wallet
 *       configured a 402 surfaces to the model as a normal response.
 *   <li>{@code app.x402.wallet-private-key} - the payer's secp256k1 key (hex, with or without
 *       {@code 0x}). Keep it in the vault/env, never in a config file that gets committed.
 *   <li>{@code app.x402.allowed-networks} (default {@code base-sepolia}) - TESTNET-ONLY out of the
 *       box; a mainnet network must be opted into explicitly, so a fresh deployment cannot spend
 *       real USDC.
 *   <li>{@code app.x402.max-payment-atomic} (default 1_000_000 = 1 USDC in the 6-decimal convention
 *       every dollar-stable requirement uses) - the per-call ceiling; a pricier requirement is
 *       declined, not paid.
 *   <li>{@code app.x402.allowed-payees} (default EMPTY = refuse every payment) - comma-separated
 *       EVM addresses a payment may be made TO. The 402 and its {@code payTo} come from whatever
 *       server the LLM chose to call, so without this a tenant (or a prompt injection) can point
 *       http_request at its own server and collect (SEC-029).
 *   <li>{@code app.x402.tenant-daily-budget-usdc} (default 5) - per-tenant spend ceiling over a
 *       rolling 24 hours, see {@link X402TenantBudget} (Redis-backed when available, else per
 *       node).
 * </ul>
 *
 * <p><b>No platform-wallet fallback (SEC-029).</b> Payments are only ever signed with the TENANT's
 * own wallet key (vault secret {@code X402_WALLET_PRIVATE_KEY}). A tenant with no wallet is
 * refused; the operator's {@code app.x402.wallet-private-key} is never used to pay for a tenant's
 * call and is retained only so an existing config still binds.
 */
@Service
public class X402PaymentService {

    private static final Logger log = LoggerFactory.getLogger(X402PaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte[] EIP712_DOMAIN_TYPEHASH =
            EvmWallet.keccak256(
                    "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                            .getBytes(StandardCharsets.US_ASCII));
    private static final byte[] TRANSFER_WITH_AUTH_TYPEHASH =
            EvmWallet.keccak256(
                    "TransferWithAuthorization(address from,address to,uint256 value,uint256 validAfter,uint256 validBefore,bytes32 nonce)"
                            .getBytes(StandardCharsets.US_ASCII));

    private final boolean enabled;
    private final String walletPrivateKey;
    private final Set<String> allowedNetworks;
    private final BigInteger maxPaymentAtomic;
    private final Clock clock;
    private final Set<String> allowedPayees;
    private final X402TenantBudget budget;

    // @Autowired is REQUIRED, not decorative: the package-private test-seam
    // constructor below means two constructors, and without the marker
    // Spring looks for a no-arg <init> that does not exist - same boot
    // failure as A2aCardVerifier on 2026-09-23.
    @Autowired
    public X402PaymentService(
            @Value("${app.x402.enabled:false}") boolean enabled,
            @Value("${app.x402.wallet-private-key:}") String walletPrivateKey,
            @Value("${app.x402.allowed-networks:base-sepolia}") String allowedNetworks,
            @Value("${app.x402.max-payment-atomic:1000000}") long maxPaymentAtomic,
            @Value("${app.x402.allowed-payees:}") String allowedPayees,
            @Value("${app.x402.tenant-daily-budget-usdc:5}") String tenantDailyBudgetUsdc,
            ObjectProvider<StringRedisTemplate> redis) {
        this(
                enabled,
                walletPrivateKey,
                allowedNetworks,
                maxPaymentAtomic,
                allowedPayees,
                usdcToAtomic(tenantDailyBudgetUsdc),
                redis.getIfAvailable(),
                Clock.systemUTC());
        if (enabled && StringUtils.hasText(walletPrivateKey)) {
            log.warn(
                    "app.x402.wallet-private-key is set but is never used to pay for tenant calls (SEC-029): "
                            + "each tenant must store its own X402_WALLET_PRIVATE_KEY vault secret");
        }
    }

    /**
     * Test seam - fixed clock for deterministic validBefore; {@code redis} null = in-memory budget.
     */
    X402PaymentService(
            boolean enabled,
            String walletPrivateKey,
            String allowedNetworks,
            long maxPaymentAtomic,
            String allowedPayees,
            long tenantDailyBudgetAtomic,
            StringRedisTemplate redis,
            Clock clock) {
        this.allowedPayees =
                StringUtils.hasText(allowedPayees)
                        ? java.util.Arrays.stream(allowedPayees.split(","))
                                .map(a -> a.strip().toLowerCase(Locale.ROOT))
                                .filter(a -> !a.isEmpty())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                        : Set.of();
        this.budget = new X402TenantBudget(tenantDailyBudgetAtomic, redis, clock);
        this.enabled = enabled;
        this.walletPrivateKey = walletPrivateKey;
        this.allowedNetworks =
                StringUtils.hasText(allowedNetworks)
                        ? Set.of(allowedNetworks.toLowerCase(Locale.ROOT).split(","))
                        : Set.of();
        this.maxPaymentAtomic = BigInteger.valueOf(maxPaymentAtomic);
        this.clock = clock;
    }

    /**
     * True when x402 is switched on AND at least one payee is allow-listed - with an empty
     * allow-list nothing could ever be paid, so the executor skips the 402 handling entirely.
     */
    public boolean isEnabled() {
        return enabled && !allowedPayees.isEmpty();
    }

    /**
     * Parse a 402 response's payment requirements. Accepts either wire form: the v1 body shape
     * ({@code {"x402Version":1,"accepts":[...]}}) or the base64 JSON carried by the {@code
     * PAYMENT-REQUIRED} header. Returns empty for anything that is not a parseable x402 offer.
     */
    public Optional<JSONArray> parseAccepts(String paymentRequiredJson) {
        if (!StringUtils.hasText(paymentRequiredJson)) {
            return Optional.empty();
        }
        try {
            String json = paymentRequiredJson.trim();
            if (!json.startsWith("{")) {
                json = new String(Base64.getDecoder().decode(json), StandardCharsets.UTF_8);
            }
            JSONArray accepts = new JSONObject(json).optJSONArray("accepts");
            return accepts == null || accepts.isEmpty() ? Optional.empty() : Optional.of(accepts);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Choose an affordable requirement and build the {@code X-PAYMENT} header value (base64 JSON)
     * for it. Returns empty when nothing in {@code accepts} is a supported, in-policy offer - the
     * caller then surfaces the original 402 to the model unchanged.
     *
     * <p>Refuses (empty) when: the feature is off; {@code tenantWalletKey} is absent (never falls
     * back to the platform wallet); {@code tenantId} is absent; no requirement is in policy
     * (scheme, network, per-call cap, allow-listed payee); or the tenant's rolling-day budget would
     * be exceeded.
     *
     * @param accepts the parsed {@code accepts} array
     * @param tenantWalletKey the tenant's own payer key (vault secret); required
     * @param tenantId the tenant the spend is charged to; required
     */
    public Optional<String> buildPaymentHeader(
            JSONArray accepts, String tenantWalletKey, String tenantId) {
        String key = tenantWalletKey;
        if (!enabled || !StringUtils.hasText(key) || !StringUtils.hasText(tenantId)) {
            return Optional.empty();
        }
        EvmWallet wallet;
        try {
            wallet = new EvmWallet(key.trim());
        } catch (IllegalArgumentException e) {
            log.warn("x402 wallet key is not a usable secp256k1 private key - 402 left unpaid");
            return Optional.empty();
        }

        JSONObject requirement = selectRequirement(accepts);
        if (requirement == null) {
            return Optional.empty();
        }
        long amount = new BigInteger(requirement.getString("maxAmountRequired")).longValueExact();
        if (!budget.tryReserve(tenantId, amount)) {
            log.warn(
                    "x402 payment of {} atomic units refused for tenant {}: rolling-day budget of {} reached",
                    amount,
                    tenantId,
                    budget.budgetAtomic());
            return Optional.empty();
        }
        try {
            return Optional.of(signPayment(wallet, requirement));
        } catch (RuntimeException e) {
            budget.release(tenantId, amount);
            log.warn("x402 payment signing failed - 402 left unpaid: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * First {@code exact}-scheme requirement on an allowed network whose {@code maxAmountRequired}
     * fits the per-call cap. Declines (null) rather than pays anything out of policy.
     */
    JSONObject selectRequirement(JSONArray accepts) {
        for (int i = 0; i < accepts.length(); i++) {
            JSONObject req = accepts.optJSONObject(i);
            if (req == null) {
                continue;
            }
            if (!"exact".equalsIgnoreCase(req.optString("scheme"))) {
                continue;
            }
            if (!allowedNetworks.contains(req.optString("network").toLowerCase(Locale.ROOT))) {
                continue;
            }
            String amount = req.optString("maxAmountRequired", "");
            if (amount.isBlank()) {
                continue;
            }
            try {
                if (new BigInteger(amount).compareTo(maxPaymentAtomic) > 0) {
                    continue;
                }
            } catch (NumberFormatException e) {
                continue;
            }
            // EIP-3009 needs the token's domain name+version (USDC: "USD
            // Coin"/"2"); a requirement without them cannot be signed.
            JSONObject extra = req.optJSONObject("extra");
            if (extra == null
                    || !StringUtils.hasText(extra.optString("name"))
                    || !StringUtils.hasText(extra.optString("version"))) {
                continue;
            }
            if (!StringUtils.hasText(req.optString("payTo"))
                    || !StringUtils.hasText(req.optString("asset"))) {
                continue;
            }
            if (!allowedPayees.contains(req.optString("payTo").strip().toLowerCase(Locale.ROOT))) {
                // SEC-029: the payee is named by the server the LLM called.
                continue;
            }
            return req;
        }
        return null;
    }

    /**
     * EIP-712 sign a {@code transferWithAuthorization} over the requirement and wrap it in the x402
     * v1 {@code X-PAYMENT} payload.
     */
    private String signPayment(EvmWallet wallet, JSONObject req) {
        String network = req.getString("network");
        long chainId = chainIdFor(network);
        BigInteger value = new BigInteger(req.getString("maxAmountRequired"));
        String payTo = req.getString("payTo");
        String asset = req.getString("asset");
        JSONObject extra = req.getJSONObject("extra");
        long validBefore = clock.instant().getEpochSecond() + req.optLong("maxTimeoutSeconds", 300);
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);

        byte[] domainSeparator =
                domainSeparator(
                        extra.getString("name"), extra.getString("version"), chainId, asset);
        byte[] structHash =
                transferAuthorizationHash(
                        wallet.address(),
                        payTo,
                        value,
                        BigInteger.ZERO,
                        BigInteger.valueOf(validBefore),
                        nonce);
        byte[] digest =
                EvmWallet.keccak256(concat(new byte[] {0x19, 0x01}, domainSeparator, structHash));

        JSONObject authorization =
                new JSONObject()
                        .put("from", wallet.address())
                        .put("to", payTo)
                        .put("value", value.toString())
                        .put("validAfter", "0")
                        .put("validBefore", Long.toString(validBefore))
                        .put("nonce", "0x" + EvmWallet.toHex(nonce));

        JSONObject payload =
                new JSONObject()
                        .put("x402Version", 1)
                        .put("scheme", "exact")
                        .put("network", network)
                        .put(
                                "payload",
                                new JSONObject()
                                        .put("signature", wallet.signDigest(digest))
                                        .put("authorization", authorization));

        log.info(
                "x402 paying {} atomic units to {} on {} (wallet {})",
                value,
                payTo,
                network,
                wallet.address());
        return Base64.getEncoder()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** EIP-712 domain separator for the token contract named in the requirement's {@code extra}. */
    static byte[] domainSeparator(
            String name, String version, long chainId, String verifyingContract) {
        byte[] enc =
                concat(
                        EIP712_DOMAIN_TYPEHASH,
                        EvmWallet.keccak256(name.getBytes(StandardCharsets.UTF_8)),
                        EvmWallet.keccak256(version.getBytes(StandardCharsets.UTF_8)),
                        EvmWallet.toFixed32(BigInteger.valueOf(chainId)),
                        abiAddress(verifyingContract));
        return EvmWallet.keccak256(enc);
    }

    /** EIP-3009 TransferWithAuthorization struct hash. */
    static byte[] transferAuthorizationHash(
            String from,
            String to,
            BigInteger value,
            BigInteger validAfter,
            BigInteger validBefore,
            byte[] nonce) {
        byte[] enc =
                concat(
                        TRANSFER_WITH_AUTH_TYPEHASH,
                        abiAddress(from),
                        abiAddress(to),
                        EvmWallet.toFixed32(value),
                        EvmWallet.toFixed32(validAfter),
                        EvmWallet.toFixed32(validBefore),
                        nonce);
        return EvmWallet.keccak256(enc);
    }

    private static byte[] abiAddress(String address) {
        byte[] raw = EvmWallet.hexToBytes(address);
        if (raw.length != 20) {
            throw new IllegalArgumentException("Not a 20-byte EVM address: " + address);
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 12, 20);
        return out;
    }

    /** x402 network name -> EVM chain id (EIP-712 domain). Unknown names fail closed. */
    static long chainIdFor(String network) {
        return switch (network.toLowerCase(Locale.ROOT)) {
            case "base" -> 8453L;
            case "base-sepolia" -> 84532L;
            case "ethereum" -> 1L;
            case "ethereum-sepolia", "sepolia" -> 11155111L;
            case "polygon" -> 137L;
            case "arbitrum" -> 42161L;
            default -> throw new IllegalArgumentException("unknown x402 network: " + network);
        };
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

    /**
     * "5" / "0.25" USDC -> atomic units (6 decimals). Unparseable or negative -> 0 (refuse all).
     */
    static long usdcToAtomic(String usdc) {
        try {
            java.math.BigDecimal value =
                    new java.math.BigDecimal(usdc == null ? "0" : usdc.strip());
            if (value.signum() < 0) {
                return 0;
            }
            return value.movePointRight(6)
                    .setScale(0, java.math.RoundingMode.DOWN)
                    .longValueExact();
        } catch (RuntimeException e) {
            log.warn("app.x402.tenant-daily-budget-usdc is not a number - x402 payments refused");
            return 0;
        }
    }

    /** Exposed for tests - the static concat helper. */
    static List<String> debugNetworks(String csv) {
        return List.of(csv.split(","));
    }
}
