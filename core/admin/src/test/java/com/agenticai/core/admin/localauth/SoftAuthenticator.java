package com.agenticai.core.admin.localauth;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A software WebAuthn authenticator for tests: a real EC P-256 key pair, a real credential id, and
 * attestation ("none") and assertion messages built byte by byte as the spec defines them (CBOR
 * attestation object, authenticator data, clientDataJSON, ECDSA signature over {@code authData ||
 * SHA-256(clientDataJSON)}). Nothing here calls the library under test, so the library is verified
 * against an independent encoder.
 *
 * <p>Every defect a test needs (wrong origin, wrong RP ID hash, no user verification, a flipped
 * signature bit, a counter value) is a parameter of the message it builds.
 */
final class SoftAuthenticator {

    static final int UP = 0x01;
    static final int UV = 0x04;
    static final int BE = 0x08;
    static final int BS = 0x10;
    static final int AT = 0x40;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    final KeyPair keys;
    final byte[] credentialId = new byte[32];

    SoftAuthenticator() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            keys = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
    }

    String credentialIdB64() {
        return B64.encodeToString(credentialId);
    }

    /** A registration response ({@code PublicKeyCredential.toJSON()} shape). */
    String registration(String challengeB64, String origin, String rpIdForHash, int flags) {
        byte[] authData = concat(sha256(rpIdForHash), new byte[] {(byte) flags}, u32(0), new byte[16], u16(credentialId.length), credentialId, coseKey());
        Map<Object, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", "none");
        attestation.put("attStmt", new LinkedHashMap<>());
        attestation.put("authData", authData);
        String clientData = clientData("webauthn.create", challengeB64, origin);
        return "{\"id\":\"" + credentialIdB64() + "\",\"rawId\":\"" + credentialIdB64() + "\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes(StandardCharsets.UTF_8))
                + "\",\"attestationObject\":\"" + B64.encodeToString(Cbor.encode(attestation))
                + "\",\"transports\":[\"internal\"]},\"clientExtensionResults\":{}}";
    }

    /** An assertion response. {@code userHandle} may be null; {@code tamper} flips a bit of the signature. */
    String assertion(String challengeB64, String origin, String rpIdForHash, int flags, long counter, byte[] userHandle, boolean tamper) {
        byte[] authData = concat(sha256(rpIdForHash), new byte[] {(byte) flags}, u32(counter));
        byte[] clientData = clientData("webauthn.get", challengeB64, origin).getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(concat(authData, sha256(clientData)));
        if (tamper) {
            signature[signature.length - 1] ^= 0x01;
        }
        return "{\"id\":\"" + credentialIdB64() + "\",\"rawId\":\"" + credentialIdB64() + "\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"" + B64.encodeToString(clientData)
                + "\",\"authenticatorData\":\"" + B64.encodeToString(authData)
                + "\",\"signature\":\"" + B64.encodeToString(signature) + "\""
                + (userHandle == null ? "" : ",\"userHandle\":\"" + B64.encodeToString(userHandle) + "\"")
                + "},\"clientExtensionResults\":{}}";
    }

    private byte[] sign(byte[] message) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(keys.getPrivate());
            s.update(message);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** COSE_Key for ES256: {1: 2 (EC2), 3: -7, -1: 1 (P-256), -2: x, -3: y}. */
    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1L, 2L);
        cose.put(3L, -7L);
        cose.put(-1L, 1L);
        cose.put(-2L, fixed32(pub.getW().getAffineX()));
        cose.put(-3L, fixed32(pub.getW().getAffineY()));
        return Cbor.encode(cose);
    }

    private static String clientData(String type, String challengeB64, String origin) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64 + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}";
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] u16(int v) {
        return new byte[] {(byte) (v >> 8), (byte) v};
    }

    private static byte[] u32(long v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** Just enough CBOR (RFC 8949) for an attestation object and a COSE key. */
    static final class Cbor {
        private Cbor() {}

        static byte[] encode(Object value) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, value);
            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream out, Object v) {
            if (v instanceof Long n) {
                if (n >= 0) {
                    head(out, 0, n);
                } else {
                    head(out, 1, -1 - n);
                }
            } else if (v instanceof byte[] b) {
                head(out, 2, b.length);
                out.writeBytes(b);
            } else if (v instanceof String s) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                head(out, 3, b.length);
                out.writeBytes(b);
            } else if (v instanceof List<?> l) {
                head(out, 4, l.size());
                l.forEach(x -> write(out, x));
            } else if (v instanceof Map<?, ?> m) {
                head(out, 5, m.size());
                m.forEach((k, x) -> {
                    write(out, k);
                    write(out, x);
                });
            } else {
                throw new IllegalArgumentException("unsupported CBOR value " + v);
            }
        }

        private static void head(ByteArrayOutputStream out, int major, long arg) {
            int m = major << 5;
            if (arg < 24) {
                out.write(m | (int) arg);
            } else if (arg < 256) {
                out.write(m | 24);
                out.write((int) arg);
            } else if (arg < 65536) {
                out.write(m | 25);
                out.write((int) (arg >> 8));
                out.write((int) arg);
            } else {
                out.write(m | 26);
                out.write((int) (arg >> 24));
                out.write((int) (arg >> 16));
                out.write((int) (arg >> 8));
                out.write((int) arg);
            }
        }
    }
}
