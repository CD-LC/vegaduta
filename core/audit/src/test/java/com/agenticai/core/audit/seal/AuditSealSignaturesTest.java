package com.agenticai.core.audit.seal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.audit.seal.SealTestSupport.TestSigner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditSealSignaturesTest {

    private TestSigner signer;
    private AuditSealRecord seal;

    @BeforeEach
    void setUp() {
        signer = new TestSigner("kid-1");
        seal = SealTestSupport.seal(signer, "t_acme", 1, AuditSealHasher.GENESIS_HASH, SealTestSupport.rows(0, 3));
    }

    @Test
    void aGenuineSealVerifies() {
        assertTrue(AuditSealSignatures.verify(seal));
    }

    @Test
    void aDifferentKeyDoesNotVerify() {
        TestSigner other = new TestSigner("kid-2");
        AuditSealRecord swappedKey = new AuditSealRecord(seal.id(), seal.seq(), seal.tenantScope(),
            seal.firstRowId(), seal.firstRowAt(), seal.lastRowId(), seal.lastRowAt(), seal.rowCount(),
            seal.rangeHash(), seal.prevSealHash(), seal.sealHash(), seal.algorithm(), seal.signature(),
            seal.keyId(), other.publicKeyB64u(), false);
        assertFalse(AuditSealSignatures.verify(swappedKey));
    }

    @Test
    void theSignedPayloadMustBindThisSeal() {
        // same valid signature, but attached to a seal with a different hash / seq / tenant
        AuditSealRecord otherHash = SealTestSupport.rebuild(seal, seal.rangeHash(), seal.prevSealHash(),
            "e".repeat(64), seal.algorithm(), seal.signature(), false);
        assertFalse(AuditSealSignatures.verify(otherHash));

        AuditSealRecord otherSeq = new AuditSealRecord(seal.id(), 2, seal.tenantScope(), seal.firstRowId(),
            seal.firstRowAt(), seal.lastRowId(), seal.lastRowAt(), seal.rowCount(), seal.rangeHash(),
            seal.prevSealHash(), seal.sealHash(), seal.algorithm(), seal.signature(), seal.keyId(),
            seal.publicKey(), false);
        assertFalse(AuditSealSignatures.verify(otherSeq));

        AuditSealRecord otherTenant = new AuditSealRecord(seal.id(), seal.seq(), "t_other", seal.firstRowId(),
            seal.firstRowAt(), seal.lastRowId(), seal.lastRowAt(), seal.rowCount(), seal.rangeHash(),
            seal.prevSealHash(), seal.sealHash(), seal.algorithm(), seal.signature(), seal.keyId(),
            seal.publicKey(), false);
        assertFalse(AuditSealSignatures.verify(otherTenant));
    }

    @Test
    void aTamperedSignatureOrPayloadFailsClosed() {
        String[] parts = seal.signature().split("\\.");
        // the FIRST character carries six significant bits of the signature (the last one carries padding bits)
        String flipped = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        assertFalse(AuditSealSignatures.verify(SealTestSupport.withSignature(seal,
            parts[0] + "." + parts[1] + "." + flipped)));

        String forgedPayload = SealTestSupport.b64u(
            "{\"seal_hash\":\"x\",\"seq\":1,\"tenant\":\"t_acme\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(AuditSealSignatures.verify(SealTestSupport.withSignature(seal,
            parts[0] + "." + forgedPayload + "." + parts[2])));
    }

    @Test
    void nonEdDsaAndMalformedInputFailClosed() {
        String noneHeader = SealTestSupport.b64u("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String[] parts = seal.signature().split("\\.");
        for (String bad : List.of("", "abc", "a.b", "a.b.c.d", noneHeader + "." + parts[1] + "." + parts[2],
            "!!!.!!!.!!!")) {
            assertFalse(AuditSealSignatures.verify(SealTestSupport.withSignature(seal, bad)), bad);
        }
        AuditSealRecord noKey = new AuditSealRecord(seal.id(), seal.seq(), seal.tenantScope(), seal.firstRowId(),
            seal.firstRowAt(), seal.lastRowId(), seal.lastRowAt(), seal.rowCount(), seal.rangeHash(),
            seal.prevSealHash(), seal.sealHash(), seal.algorithm(), seal.signature(), seal.keyId(), null, false);
        assertFalse(AuditSealSignatures.verify(noKey));
        AuditSealRecord shortKey = new AuditSealRecord(seal.id(), seal.seq(), seal.tenantScope(), seal.firstRowId(),
            seal.firstRowAt(), seal.lastRowId(), seal.lastRowAt(), seal.rowCount(), seal.rangeHash(),
            seal.prevSealHash(), seal.sealHash(), seal.algorithm(), seal.signature(), seal.keyId(), "AAAA", false);
        assertFalse(AuditSealSignatures.verify(shortKey));
    }
}
