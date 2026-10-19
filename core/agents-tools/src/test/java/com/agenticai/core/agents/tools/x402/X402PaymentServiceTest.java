package com.agenticai.core.agents.tools.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * x402 payer tests. The load-bearing assertion is the cryptographic round-trip: sign a real
 * EIP-712/EIP-3009 digest, then RECOVER the signer from (r, s, v) the way a facilitator's {@code
 * ecrecover} does, and check it lands on the wallet's own address. If v, low-s, or the domain hash
 * are off, every facilitator would reject the payment - this is the test that catches it without a
 * live network.
 */
class X402PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    // Hardhat/Anvil account #0 - a public, well-known test key. Never holds
    // funds on a real network; using it here is the standard test vector.
    private static final String TEST_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String TEST_ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    private static final String BASE_SEPOLIA_USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";

    private static final String PAYEE = "0x209693Bc6afc0C5328bA36FaF03CfaEF8602e5bb";
    private static final String TENANT = "tenant-a";

    private X402PaymentService service(String key, String networks, long cap) {
        // Payee allow-listed (mixed case on purpose - matching is case-insensitive), 5 USDC/day
        // budget.
        return new X402PaymentService(
                true,
                key,
                networks,
                cap,
                PAYEE.toUpperCase().replace("0X", "0x"),
                5_000_000L,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JSONObject requirement(String network, String amount) {
        return new JSONObject()
                .put("scheme", "exact")
                .put("network", network)
                .put("maxAmountRequired", amount)
                .put("resource", "https://api.example.com/metered")
                .put("payTo", "0x209693Bc6afc0C5328bA36FaF03CfaEF8602e5bb")
                .put("asset", BASE_SEPOLIA_USDC)
                .put("maxTimeoutSeconds", 300)
                .put("extra", new JSONObject().put("name", "USD Coin").put("version", "2"));
    }

    // ------------------------------ wallet -----------------------------------

    @Test
    void wallet_derivesTheWellKnownAddress_forTheWellKnownKey() {
        assertEquals(TEST_ADDRESS, new EvmWallet(TEST_KEY).address());
    }

    @Test
    void signDigest_recoversTheWalletAddress() {
        EvmWallet wallet = new EvmWallet(TEST_KEY);
        byte[] digest = EvmWallet.keccak256("test".getBytes(StandardCharsets.UTF_8));

        String sig = wallet.signDigest(digest);
        byte[] raw = EvmWallet.hexToBytes(sig);
        assertEquals(65, raw.length);
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64));
        int v = raw[64] & 0xFF;
        assertTrue(v == 27 || v == 28, "v must be 27/28, got " + v);
        assertTrue(
                s.compareTo(EvmWallet.N.divide(BigInteger.TWO)) <= 0, "s must be low-normalized");

        String recovered = EvmWallet.recoverAddress(r, s, v - 27, digest);
        assertEquals(TEST_ADDRESS, recovered);
    }

    // --------------------------- requirement parse ---------------------------

    @Test
    void parseAccepts_readsTheBodyShape_andTheBase64HeaderShape() {
        JSONObject body =
                new JSONObject()
                        .put("x402Version", 1)
                        .put("accepts", new JSONArray().put(requirement("base-sepolia", "10000")));

        assertTrue(
                service(TEST_KEY, "base-sepolia", 1_000_000)
                        .parseAccepts(body.toString())
                        .isPresent());

        String headerForm =
                Base64.getEncoder()
                        .encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(
                service(TEST_KEY, "base-sepolia", 1_000_000).parseAccepts(headerForm).isPresent());
    }

    @Test
    void parseAccepts_garbage_isEmpty() {
        assertTrue(service(TEST_KEY, "base-sepolia", 1_000_000).parseAccepts("not json").isEmpty());
        assertTrue(service(TEST_KEY, "base-sepolia", 1_000_000).parseAccepts("{}").isEmpty());
    }

    // ---------------------------- policy gates -------------------------------

    @Test
    void buildPaymentHeader_declinesAnOffPolicyNetwork_byDefault() {
        // Default allowlist is testnet-only: a mainnet offer is declined.
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base", "10000"));
        assertTrue(svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).isEmpty());
    }

    @Test
    void buildPaymentHeader_declinesOverTheCap() {
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "5000000"));
        assertTrue(svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).isEmpty());
    }

    @Test
    void buildPaymentHeader_declinesWhenDisabledOrNoKey() {
        X402PaymentService off =
                new X402PaymentService(
                        false,
                        TEST_KEY,
                        "base-sepolia",
                        1_000_000,
                        PAYEE,
                        5_000_000L,
                        null,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(off.isEnabled());
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "10000"));
        assertTrue(off.buildPaymentHeader(accepts, TEST_KEY, TENANT).isEmpty());
    }

    // --------------------------- signed payload -------------------------------

    @Test
    void buildPaymentHeader_producesARecoverableAuthorization() {
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "25000"));

        String header = svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).orElseThrow();

        JSONObject payment =
                new JSONObject(
                        new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8));
        assertEquals(1, payment.getInt("x402Version"));
        assertEquals("exact", payment.getString("scheme"));
        assertEquals("base-sepolia", payment.getString("network"));

        JSONObject payload = payment.getJSONObject("payload");
        JSONObject auth = payload.getJSONObject("authorization");
        assertEquals(TEST_ADDRESS, auth.getString("from"));
        assertEquals("0x209693Bc6afc0C5328bA36FaF03CfaEF8602e5bb", auth.getString("to"));
        assertEquals("25000", auth.getString("value"));
        assertEquals("0", auth.getString("validAfter"));
        assertEquals(Long.toString(NOW.getEpochSecond() + 300), auth.getString("validBefore"));
        assertNotNull(auth.getString("nonce"));

        // Recover the signer the way the facilitator will.
        String sig = payload.getString("signature");
        byte[] raw = EvmWallet.hexToBytes(sig);
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64));
        int recId = (raw[64] & 0xFF) - 27;

        byte[] domain =
                X402PaymentService.domainSeparator("USD Coin", "2", 84532L, BASE_SEPOLIA_USDC);
        byte[] structHash =
                X402PaymentService.transferAuthorizationHash(
                        auth.getString("from"),
                        auth.getString("to"),
                        new BigInteger(auth.getString("value")),
                        BigInteger.ZERO,
                        new BigInteger(auth.getString("validBefore")),
                        EvmWallet.hexToBytes(auth.getString("nonce")));
        byte[] digest = EvmWallet.keccak256(concat(new byte[] {0x19, 0x01}, domain, structHash));

        assertEquals(TEST_ADDRESS, EvmWallet.recoverAddress(r, s, recId, digest));
    }

    @Test
    void buildPaymentHeader_usesTheTenantWalletKey() {
        // A second well-known Hardhat key -> a different address in `from`.
        String otherKey = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
        String otherAddress = new EvmWallet(otherKey).address();
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "10000"));

        String header = svc.buildPaymentHeader(accepts, otherKey, TENANT).orElseThrow();
        JSONObject payment =
                new JSONObject(
                        new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8));
        assertEquals(
                otherAddress,
                payment.getJSONObject("payload").getJSONObject("authorization").getString("from"));
    }

    // ------------------------------ SEC-029 ----------------------------------

    @Test
    void sec029_payeeNotAllowListed_isNeverPaid() {
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONObject attacker =
                requirement("base-sepolia", "10000")
                        .put("payTo", "0x000000000000000000000000000000000000dEaD");
        assertTrue(
                svc.buildPaymentHeader(new JSONArray().put(attacker), TEST_KEY, TENANT).isEmpty());
    }

    @Test
    void sec029_emptyPayeeAllowList_refusesEverything_andReportsDisabled() {
        X402PaymentService svc =
                new X402PaymentService(
                        true,
                        TEST_KEY,
                        "base-sepolia",
                        1_000_000,
                        "",
                        5_000_000L,
                        null,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(svc.isEnabled());
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "10000"));
        assertTrue(svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).isEmpty());
    }

    @Test
    void sec029_noTenantWallet_neverFallsBackToThePlatformWallet() {
        // The platform key IS configured (TEST_KEY) - it must still not be used.
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "10000"));
        assertTrue(svc.buildPaymentHeader(accepts, null, TENANT).isEmpty());
        assertTrue(svc.buildPaymentHeader(accepts, "  ", TENANT).isEmpty());
    }

    @Test
    void sec029_noTenant_isNeverPaid() {
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "10000"));
        assertTrue(svc.buildPaymentHeader(accepts, TEST_KEY, null).isEmpty());
    }

    @Test
    void sec029_budgetExhausted_stopsPaying_perTenant() {
        // 1 USDC per call, 5 USDC per rolling day.
        X402PaymentService svc = service(TEST_KEY, "base-sepolia", 1_000_000);
        JSONArray accepts = new JSONArray().put(requirement("base-sepolia", "1000000"));
        for (int i = 0; i < 5; i++) {
            assertTrue(
                    svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).isPresent(), "payment " + i);
        }
        assertTrue(
                svc.buildPaymentHeader(accepts, TEST_KEY, TENANT).isEmpty(),
                "6th payment must be refused");
        // Another tenant has its own budget.
        assertTrue(svc.buildPaymentHeader(accepts, TEST_KEY, "tenant-b").isPresent());
    }

    @Test
    void sec029_budgetWindowRolls_after24Hours() {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock moving =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        X402TenantBudget budget = new X402TenantBudget(2_000_000L, null, moving);
        assertTrue(budget.tryReserve(TENANT, 2_000_000L));
        assertFalse(budget.tryReserve(TENANT, 1L));
        now.set(NOW.plusSeconds(23 * 3600));
        assertFalse(budget.tryReserve(TENANT, 1L), "still inside the rolling day");
        now.set(NOW.plusSeconds(24 * 3600 + 1));
        assertTrue(budget.tryReserve(TENANT, 1_000_000L), "window rolled");
    }

    @Test
    void sec029_budgetUsdcParsing_failsClosed() {
        assertEquals(5_000_000L, X402PaymentService.usdcToAtomic("5"));
        assertEquals(250_000L, X402PaymentService.usdcToAtomic("0.25"));
        assertEquals(0L, X402PaymentService.usdcToAtomic("-1"));
        assertEquals(0L, X402PaymentService.usdcToAtomic("lots"));
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
