package com.agenticai.core.agents.tools.x402;

import java.math.BigInteger;
import java.util.Arrays;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;

/**
 * A secp256k1 EVM signing key - the payer side of an x402 {@code exact}-scheme payment. The JDK
 * does not ship secp256k1 (removed in JDK 16) or Keccak-256 (its SHA3-256 differs only in padding -
 * enough to produce wrong digests), so this is built on BouncyCastle directly rather than on {@code
 * java.security.Signature}.
 *
 * <p>What it provides: the EIP-55 checksummed address derived from the key, and {@link #signDigest}
 * - a deterministic (RFC 6979) ECDSA signature over a precomputed digest, normalized to low-s and
 * carrying the correct recovery id ({@code v} in {27, 28}), computed by recovering the public key
 * for each candidate and matching the wallet's own address. A wrong {@code v} fails {@code
 * ecrecover} on the facilitator, which is the failure mode a test pins via the recover-signer
 * round-trip.
 */
public class EvmWallet {

    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    static final ECDomainParameters DOMAIN =
            new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());

    /** Curve order n. */
    static final BigInteger N = CURVE.getN();

    /** Field prime p = 2^256 - 2^32 - 977. */
    static final BigInteger FIELD_P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    private final BigInteger privateKey;
    private final String address;

    public EvmWallet(String hexPrivateKey) {
        byte[] raw = hexToBytes(hexPrivateKey);
        if (raw.length != 32) {
            throw new IllegalArgumentException(
                    "An EVM private key is exactly 32 bytes (64 hex chars)");
        }
        BigInteger d = new BigInteger(1, raw);
        if (d.signum() <= 0 || d.compareTo(N) >= 0) {
            throw new IllegalArgumentException(
                    "EVM private key is outside the secp256k1 scalar range");
        }
        this.privateKey = d;
        this.address = toAddress(DOMAIN.getG().multiply(d).normalize());
    }

    /** EIP-55 checksummed {@code 0x...} address. */
    public String address() {
        return address;
    }

    /**
     * Deterministic ECDSA signature over {@code digest} (a 32-byte hash - callers pass the EIP-712
     * final digest), returned as {@code 0x} + r(32) + s(32) + v(1), low-s normalized, v in {27,
     * 28}.
     */
    public String signDigest(byte[] digest) {
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, DOMAIN));
        BigInteger[] rs = signer.generateSignature(digest);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        int recId = recoverId(r, s, digest, address);
        if (s.compareTo(N.divide(BigInteger.TWO)) > 0) {
            // EIP-2 low-s: flipping s flips the recovery parity.
            s = N.subtract(s);
            recId ^= 1;
        }
        byte[] sig = new byte[65];
        System.arraycopy(toFixed32(r), 0, sig, 0, 32);
        System.arraycopy(toFixed32(s), 0, sig, 32, 32);
        sig[64] = (byte) (27 + recId);
        return "0x" + toHex(sig);
    }

    /**
     * Recover the signer's address from (r, s, v-style recId, digest) - the facilitator's
     * ecrecover.
     */
    static String recoverAddress(BigInteger r, BigInteger s, int recId, byte[] digest) {
        BigInteger x = r.add(BigInteger.valueOf(recId / 2).multiply(N));
        if (x.compareTo(FIELD_P) >= 0) {
            return null;
        }
        byte[] encoded = new byte[33];
        encoded[0] = (byte) ((recId & 1) == 1 ? 0x03 : 0x02);
        System.arraycopy(toFixed32(x), 0, encoded, 1, 32);
        ECPoint rPoint = CURVE.getCurve().decodePoint(encoded).normalize();
        if (!rPoint.multiply(N).isInfinity()) {
            return null;
        }
        BigInteger e = new BigInteger(1, digest);
        BigInteger rInv = r.modInverse(N);
        ECPoint q =
                rPoint.multiply(s).subtract(DOMAIN.getG().multiply(e)).multiply(rInv).normalize();
        return toAddress(q);
    }

    private static int recoverId(
            BigInteger r, BigInteger s, byte[] digest, String expectedAddress) {
        for (int recId = 0; recId < 4; recId++) {
            String recovered = recoverAddress(r, s, recId, digest);
            if (expectedAddress.equalsIgnoreCase(recovered)) {
                return recId;
            }
        }
        throw new IllegalStateException("could not recover a recId matching the wallet address");
    }

    /** keccak256 of the 64-byte uncompressed public key, last 20 bytes, EIP-55 checksummed. */
    static String toAddress(ECPoint publicPoint) {
        byte[] uncompressed = publicPoint.getEncoded(false); // 0x04 || x || y
        byte[] hash = keccak256(Arrays.copyOfRange(uncompressed, 1, 65));
        byte[] addr = Arrays.copyOfRange(hash, 12, 32);
        String lower = toHex(addr);
        byte[] lowerHash = keccak256(lower.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        StringBuilder out = new StringBuilder("0x");
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int nibble = (lowerHash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0x0F;
            out.append(c >= 'a' && nibble >= 8 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }

    static byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    static byte[] toFixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int srcPos = Math.max(0, raw.length - 32);
        System.arraycopy(raw, srcPos, out, 32 - (raw.length - srcPos), raw.length - srcPos);
        return out;
    }

    static byte[] hexToBytes(String hex) {
        String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
