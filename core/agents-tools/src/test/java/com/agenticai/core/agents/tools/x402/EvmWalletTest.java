package com.agenticai.core.agents.tools.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** secp256k1 address derivation, key-range validation and the sign/recover round-trip. */
class EvmWalletTest {

    /** Private key 1: a published test vector. */
    private static final String KEY_ONE = "0x" + "0".repeat(63) + "1";

    private static final String ADDR_ONE = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";

    @Test
    void privateKeyOneDerivesTheKnownChecksummedAddress() {
        EvmWallet wallet = new EvmWallet(KEY_ONE);
        assertEquals(ADDR_ONE, wallet.address());
        // The 0x prefix is optional.
        assertEquals(ADDR_ONE, new EvmWallet(KEY_ONE.substring(2)).address());
    }

    @Test
    void keysOutsideTheScalarRangeAreRefused() {
        assertThrows(RuntimeException.class, () -> new EvmWallet(null));
        assertThrows(IllegalArgumentException.class, () -> new EvmWallet("0x01"), "too short");
        assertThrows(IllegalArgumentException.class, () -> new EvmWallet("0".repeat(64)), "zero");
        String n = "0x" + EvmWallet.N.toString(16);
        assertThrows(IllegalArgumentException.class, () -> new EvmWallet(n), "the order itself");
        // Bad hex fails while decoding.
        assertThrows(RuntimeException.class, () -> new EvmWallet("zz".repeat(32)));
    }

    @Test
    void signDigestProducesADeterministicLowSVSignatureThatRecovers() {
        EvmWallet wallet = new EvmWallet(KEY_ONE);
        byte[] digest =
                EvmWallet.keccak256("transfer".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String sig = wallet.signDigest(digest);
        assertEquals(132, sig.length(), "0x + 65 bytes as hex");
        assertTrue(sig.startsWith("0x"));
        assertEquals(sig, wallet.signDigest(digest), "RFC 6979: deterministic");

        byte[] raw = EvmWallet.hexToBytes(sig);
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        int v = raw[64] & 0xff;
        assertTrue(v == 27 || v == 28, "v is a legacy recovery id");
        assertTrue(s.compareTo(EvmWallet.N.divide(BigInteger.TWO)) <= 0, "EIP-2 low-s");

        String recovered = EvmWallet.recoverAddress(r, s, v - 27, digest);
        assertTrue(wallet.address().equalsIgnoreCase(recovered), recovered);
    }

    @Test
    void hexAndKeccakHelpersAgreeWithPublishedVectors() {
        assertArrayEqualsBytes(
                new byte[] {0x00, 0x10, (byte) 0xff}, EvmWallet.hexToBytes("0010ff"));
        // keccak256("") = c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
        assertEquals(
                "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                EvmWallet.toHex(EvmWallet.keccak256(new byte[0])));
        BigInteger v = new BigInteger("ff", 16);
        assertEquals(32, EvmWallet.toFixed32(v).length);
        assertEquals(0xff, EvmWallet.toFixed32(v)[31] & 0xff);
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual), Arrays.toString(actual));
    }
}
