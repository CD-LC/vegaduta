package com.agenticai.core.audit.seal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.audit.seal.SealTestSupport.Chain;
import com.agenticai.core.audit.seal.SealTestSupport.TestSigner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditSealVerifierTest {

    private static final String TENANT = "t_acme";

    private TestSigner signer;
    private Chain chain;

    @BeforeEach
    void setUp() {
        signer = new TestSigner("kid-1");
        chain = new Chain(signer, TENANT, 3, 3); // 3 seals x 3 rows
    }

    private AuditSealVerifier.Result verify(List<AuditSealRecord> seals, String firstPrev) {
        return AuditSealVerifier.verify(seals, firstPrev, chain.source(), AuditSealSignatures::verify);
    }

    private AuditSealVerifier.Result verifyAll() {
        return verify(chain.seals, AuditSealHasher.GENESIS_HASH);
    }

    @Test
    void anIntactChainVerifies() {
        AuditSealVerifier.Result r = verifyAll();
        assertTrue(r.ok(), r.reason());
        assertEquals(3, r.sealsChecked());
        assertEquals(9, r.rowsChecked());
        assertEquals(0, r.purgedSeals());
        assertEquals(3L, r.headSeq());
        assertEquals(chain.seals.get(2).sealHash(), r.headSealHash());
        assertEquals(List.of("kid-1"), r.keyIds());
        assertNull(r.firstBrokenSeq());
    }

    @Test
    void chainLinksAreSequential() {
        assertEquals(AuditSealHasher.GENESIS_HASH, chain.seals.get(0).prevSealHash());
        assertEquals(chain.seals.get(0).sealHash(), chain.seals.get(1).prevSealHash());
        assertEquals(chain.seals.get(1).sealHash(), chain.seals.get(2).prevSealHash());
    }

    @Test
    void aModifiedRowIsDetected() {
        AuditSealHasher.Row original = chain.rowsBySeq.get(2L).get(1);
        chain.rowsBySeq.get(2L).set(1, new AuditSealHasher.Row(original.id(), "mallory", original.actionType(),
            original.targetType(), original.targetId(), original.outcome(), original.detail(),
            original.createdAtMicros()));
        AuditSealVerifier.Result r = verifyAll();
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
        assertEquals(chain.seals.get(1).id(), r.firstBrokenSealId());
        assertTrue(r.reason().contains("range_hash mismatch"), r.reason());
        assertEquals(1, r.sealsChecked(), "the intact seal before the break was still counted");
    }

    @Test
    void aDeletedRowIsDetected() {
        chain.rowsBySeq.get(3L).remove(1);
        AuditSealVerifier.Result r = verifyAll();
        assertFalse(r.ok());
        assertEquals(3L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("holds 2 row(s)"), r.reason());
    }

    @Test
    void anInsertedRowIsDetected() {
        chain.rowsBySeq.get(1L).add(SealTestSupport.rows(100, 101).get(0));
        AuditSealVerifier.Result r = verifyAll();
        assertFalse(r.ok());
        assertEquals(1L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("holds more than the 3 row(s)"), r.reason());
    }

    @Test
    void reorderedRowsAreDetected() {
        Collections.swap(chain.rowsBySeq.get(2L), 0, 2);
        AuditSealVerifier.Result r = verifyAll();
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("range_hash mismatch"), r.reason());
    }

    @Test
    void aDroppedMiddleSealIsDetectedAsASequenceGap() {
        List<AuditSealRecord> withoutMiddle = List.of(chain.seals.get(0), chain.seals.get(2));
        AuditSealVerifier.Result r = verify(withoutMiddle, AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertEquals(3L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("sequence gap"), r.reason());
    }

    @Test
    void aReorderedSealListIsDetected() {
        List<AuditSealRecord> swapped = new ArrayList<>(chain.seals);
        Collections.swap(swapped, 1, 2);
        assertFalse(verify(swapped, AuditSealHasher.GENESIS_HASH).ok());
    }

    @Test
    void aBrokenChainLinkIsDetectedEvenWhenTheSealIsSelfConsistent() {
        // an attacker rewrites seal 2 prev link and recomputes its hash, but cannot keep seal 3 linking to it
        AuditSealRecord s2 = chain.seals.get(1);
        String forgedPrev = "a".repeat(64);
        String forgedHash = AuditSealHasher.sealHash(TENANT, 2, forgedPrev, s2.rangeHash(), s2.rowCount(),
            s2.firstRowId().toString(), AuditSealHasher.toMicros(s2.firstRowAt()), s2.lastRowId().toString(),
            AuditSealHasher.toMicros(s2.lastRowAt()));
        AuditSealRecord forged = SealTestSupport.rebuild(s2, s2.rangeHash(), forgedPrev, forgedHash,
            s2.algorithm(), s2.signature(), false);
        AuditSealVerifier.Result r = verify(List.of(chain.seals.get(0), forged, chain.seals.get(2)),
            AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("chain link broken"), r.reason());
    }

    @Test
    void aSealWhoseFieldsWereEditedFailsItsOwnHash() {
        AuditSealRecord edited = SealTestSupport.withRangeHash(chain.seals.get(1), "b".repeat(64));
        AuditSealVerifier.Result r = verify(List.of(chain.seals.get(0), edited, chain.seals.get(2)),
            AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("seal_hash does not match"), r.reason());
    }

    @Test
    void aSignatureLiftedFromAnotherSealIsRejected() {
        AuditSealRecord swapped = SealTestSupport.withSignature(chain.seals.get(1), chain.seals.get(0).signature());
        AuditSealVerifier.Result r = verify(List.of(chain.seals.get(0), swapped, chain.seals.get(2)),
            AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("signature is invalid"), r.reason());
    }

    @Test
    void anUnsignedSealIsABreakNotAWarning() {
        AuditSealRecord unsigned = SealTestSupport.withSignature(chain.seals.get(0), null);
        AuditSealVerifier.Result r = verify(List.of(unsigned), AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("unsigned"), r.reason());
    }

    @Test
    void anUnknownAlgorithmIsRejected() {
        AuditSealRecord other = SealTestSupport.withAlgorithm(chain.seals.get(0), "audit-seal-v0");
        assertFalse(verify(List.of(other), AuditSealHasher.GENESIS_HASH).ok());
    }

    @Test
    void theFirstSealMustChainToGenesis() {
        AuditSealVerifier.Result r = verify(chain.seals, "f".repeat(64));
        assertFalse(r.ok());
        assertEquals(1L, r.firstBrokenSeq());
    }

    @Test
    void aWindowStartingMidChainVerifiesAgainstItsPredecessor() {
        List<AuditSealRecord> window = chain.seals.subList(1, 3);
        AuditSealVerifier.Result ok = verify(window, chain.seals.get(0).sealHash());
        assertTrue(ok.ok(), ok.reason());
        assertEquals(2, ok.sealsChecked());
        assertFalse(verify(window, "c".repeat(64)).ok());
    }

    @Test
    void aMissingPredecessorIsABreak() {
        AuditSealVerifier.Result r = verify(chain.seals.subList(1, 3), null);
        assertFalse(r.ok());
        assertTrue(r.reason().contains("missing"), r.reason());
    }

    @Test
    void aPurgedRangeIsCheckedByChainAndSignatureOnly() {
        // seal 2 rows are gone (purged): the row source would return nothing, yet the chain still verifies
        chain.rowsBySeq.remove(2L);
        List<AuditSealRecord> seals = new ArrayList<>(chain.seals);
        seals.set(1, SealTestSupport.withPurged(seals.get(1), true));
        AuditSealVerifier.Result r = verify(seals, AuditSealHasher.GENESIS_HASH);
        assertTrue(r.ok(), r.reason());
        assertEquals(3, r.sealsChecked());
        assertEquals(1, r.purgedSeals());
        assertEquals(6, r.rowsChecked(), "only the two intact ranges had their rows recomputed");
    }

    @Test
    void withoutThePurgeMarkerMissingRowsAreTampering() {
        chain.rowsBySeq.remove(2L);
        AuditSealVerifier.Result r = verifyAll();
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
    }

    @Test
    void aPurgedRangeStillCannotHideAChainOrSignatureBreak() {
        chain.rowsBySeq.remove(2L);
        AuditSealRecord purgedForged = SealTestSupport.withPrev(
            SealTestSupport.withPurged(chain.seals.get(1), true), "d".repeat(64));
        AuditSealVerifier.Result r = verify(List.of(chain.seals.get(0), purgedForged, chain.seals.get(2)),
            AuditSealHasher.GENESIS_HASH);
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());

        AuditSealRecord purgedBadSig = SealTestSupport.withSignature(
            SealTestSupport.withPurged(chain.seals.get(1), true), chain.seals.get(0).signature());
        assertFalse(verify(List.of(chain.seals.get(0), purgedBadSig, chain.seals.get(2)),
            AuditSealHasher.GENESIS_HASH).ok());
    }

    @Test
    void anEmptyWindowIsTriviallyOk() {
        AuditSealVerifier.Result r = verify(List.of(), AuditSealHasher.GENESIS_HASH);
        assertTrue(r.ok());
        assertEquals(0, r.sealsChecked());
    }
}
