package com.agenticai.core.audit.seal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test fixtures: an Ed25519 signer, deterministic rows and a seal builder independent of AuditSealService. */
final class SealTestSupport {

    static final long BASE_MICROS = 1_700_000_000_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private SealTestSupport() {
    }

    /** Real JDK Ed25519, producing the same JWS shape as the production signer adapter. */
    static final class TestSigner implements AuditSealSigner {
        final KeyPair pair;
        final String kid;
        boolean available = true;

        TestSigner(String kid) {
            this.kid = kid;
            try {
                this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        String publicKeyB64u() {
            byte[] encoded = pair.getPublic().getEncoded();
            return b64u(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Optional<Signed> sign(Map<String, Object> payload) {
            try {
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("alg", "EdDSA");
                header.put("kid", kid);
                header.put("typ", "audit-seal+jws");
                String signingInput = b64u(JSON.writeValueAsBytes(header)) + "." + b64u(JSON.writeValueAsBytes(payload));
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(pair.getPrivate());
                signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return Optional.of(new Signed(signingInput + "." + b64u(signer.sign()), kid, publicKeyB64u()));
            } catch (GeneralSecurityException | java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static String b64u(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    /** Rows {@code from..toExclusive-1}, strictly increasing in (created_at, id). */
    static List<AuditSealHasher.Row> rows(int from, int toExclusive) {
        List<AuditSealHasher.Row> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            out.add(new AuditSealHasher.Row(uuid(i).toString(), i % 2 == 0 ? "alice" : null, "action." + i,
                "agent", "target-" + i, i % 3 == 0 ? "FAILURE" : "SUCCESS", i % 4 == 0 ? null : "detail " + i,
                BASE_MICROS + i * 1_000L));
        }
        return out;
    }

    /** Builds a correctly hashed and signed seal over {@code rows}. */
    static AuditSealRecord seal(
        TestSigner signer, String tenant, long seq, String prevHash, List<AuditSealHasher.Row> rows
    ) {
        AuditSealHasher.Row first = rows.get(0);
        AuditSealHasher.Row last = rows.get(rows.size() - 1);
        String range = AuditSealHasher.rangeHash(rows);
        String sealHash = AuditSealHasher.sealHash(tenant, seq, prevHash, range, rows.size(),
            first.id(), first.createdAtMicros(), last.id(), last.createdAtMicros());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", AuditSealHasher.ALGORITHM);
        payload.put("tenant", tenant);
        payload.put("seq", seq);
        payload.put("seal_hash", sealHash);
        payload.put("range_hash", range);
        payload.put("prev", prevHash);
        AuditSealSigner.Signed signed = signer.sign(payload).orElseThrow();
        return new AuditSealRecord(UUID.randomUUID(), seq, tenant, UUID.fromString(first.id()),
            AuditSealHasher.fromMicros(first.createdAtMicros()), UUID.fromString(last.id()),
            AuditSealHasher.fromMicros(last.createdAtMicros()), rows.size(), range, prevHash, sealHash,
            AuditSealHasher.ALGORITHM, signed.compactJws(), signed.keyId(), signed.publicKeyBase64Url(), false);
    }

    /** A chain of {@code n} seals of {@code perSeal} rows each; rows for seal k are {@code rowsOf(k)}. */
    static final class Chain {
        final List<AuditSealRecord> seals = new ArrayList<>();
        final Map<Long, List<AuditSealHasher.Row>> rowsBySeq = new LinkedHashMap<>();

        Chain(TestSigner signer, String tenant, int n, int perSeal) {
            String prev = AuditSealHasher.GENESIS_HASH;
            for (int k = 0; k < n; k++) {
                List<AuditSealHasher.Row> rows = rows(k * perSeal, (k + 1) * perSeal);
                AuditSealRecord s = seal(signer, tenant, k + 1L, prev, rows);
                seals.add(s);
                rowsBySeq.put(s.seq(), new ArrayList<>(rows));
                prev = s.sealHash();
            }
        }

        AuditSealVerifier.RowSource source() {
            return s -> rowsBySeq.getOrDefault(s.seq(), List.of());
        }
    }

    static AuditSealRecord rebuild(
        AuditSealRecord s, String rangeHash, String prev, String sealHash, String algorithm, String signature,
        boolean purged
    ) {
        return new AuditSealRecord(s.id(), s.seq(), s.tenantScope(), s.firstRowId(), s.firstRowAt(), s.lastRowId(),
            s.lastRowAt(), s.rowCount(), rangeHash, prev, sealHash, algorithm, signature, s.keyId(), s.publicKey(),
            purged);
    }

    static AuditSealRecord withSignature(AuditSealRecord s, String signature) {
        return rebuild(s, s.rangeHash(), s.prevSealHash(), s.sealHash(), s.algorithm(), signature, s.purged());
    }

    static AuditSealRecord withPurged(AuditSealRecord s, boolean purged) {
        return rebuild(s, s.rangeHash(), s.prevSealHash(), s.sealHash(), s.algorithm(), s.signature(), purged);
    }

    static AuditSealRecord withRangeHash(AuditSealRecord s, String rangeHash) {
        return rebuild(s, rangeHash, s.prevSealHash(), s.sealHash(), s.algorithm(), s.signature(), s.purged());
    }

    static AuditSealRecord withPrev(AuditSealRecord s, String prev) {
        return rebuild(s, s.rangeHash(), prev, s.sealHash(), s.algorithm(), s.signature(), s.purged());
    }

    static AuditSealRecord withAlgorithm(AuditSealRecord s, String algorithm) {
        return rebuild(s, s.rangeHash(), s.prevSealHash(), s.sealHash(), algorithm, s.signature(), s.purged());
    }
}
