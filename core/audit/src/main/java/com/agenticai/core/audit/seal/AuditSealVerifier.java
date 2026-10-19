package com.agenticai.core.audit.seal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pure verification of a window of consecutive seals against the live rows. No Spring, no I/O: rows and the
 * signature check are supplied by the caller, so every tamper scenario is unit-testable.
 *
 * <p>Per seal, in sequence order, it checks: algorithm id, seq continuity (no dropped/reordered seal), the
 * chain link ({@code prev_seal_hash} equals the previous seal's hash; the first seal of a tenant links to the
 * genesis hash), the seal hash recomputed from the seal's own fields, a valid signature, and - unless the
 * range was purged - that the rows currently in the range recompute to the sealed {@code range_hash} with the
 * sealed count and boundaries. A purged range is verified by chain + signature only (its rows are gone by
 * design); the result counts them so it is never silent.
 *
 * <p>What it cannot see: seals dropped from the very END of the history (no later seal links to them). The
 * result therefore reports the head seal's sequence and hash for the customer to record and pin.
 */
public final class AuditSealVerifier {

    private AuditSealVerifier() {
    }

    /** Supplies the rows currently held for a (non-purged) seal's range, in (created_at, id) order. */
    @FunctionalInterface
    public interface RowSource {
        List<AuditSealHasher.Row> rowsFor(AuditSealRecord seal);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
        boolean ok,
        int sealsChecked,
        long rowsChecked,
        int purgedSeals,
        UUID firstBrokenSealId,
        Long firstBrokenSeq,
        String reason,
        Long headSeq,
        String headSealHash,
        List<String> keyIds,
        boolean truncated
    ) {
        public Result withTruncated(boolean value) {
            return new Result(ok, sealsChecked, rowsChecked, purgedSeals, firstBrokenSealId, firstBrokenSeq,
                reason, headSeq, headSealHash, keyIds, value);
        }
    }

    /**
     * @param seals         consecutive seals in ascending seq order
     * @param firstPrevHash the hash the first seal must chain to: the genesis hash when it is seq 1, else the
     *                      predecessor seal's hash; {@code null} when that predecessor is missing (a break)
     */
    public static Result verify(
        List<AuditSealRecord> seals,
        String firstPrevHash,
        RowSource rowSource,
        Predicate<AuditSealRecord> signatureValid
    ) {
        int checked = 0;
        int purged = 0;
        long rowsChecked = 0;
        Long headSeq = null;
        String headHash = null;
        TreeSet<String> keyIds = new TreeSet<>();
        String expectedPrev = firstPrevHash;
        Long expectedSeq = null;

        for (AuditSealRecord seal : seals) {
            String broken = null;
            if (!AuditSealHasher.ALGORITHM.equals(seal.algorithm())) {
                broken = "unsupported seal algorithm '" + seal.algorithm() + "'";
            } else if (expectedSeq != null && seal.seq() != expectedSeq) {
                broken = "sequence gap: expected seal " + expectedSeq + " but found " + seal.seq()
                    + " (a seal was dropped or reordered)";
            } else if (expectedPrev == null) {
                broken = "the seal preceding seq " + seal.seq() + " is missing";
            } else if (!expectedPrev.equals(seal.prevSealHash())) {
                broken = "chain link broken: prev_seal_hash does not match the previous seal";
            } else if (!seal.recomputeSealHash().equals(seal.sealHash())) {
                broken = "seal_hash does not match the seal's own fields";
            } else if (seal.signature() == null || seal.signature().isBlank()) {
                broken = "seal is unsigned";
            } else if (!signatureValid.test(seal)) {
                broken = "signature is invalid";
            } else if (seal.purged()) {
                purged++;
            } else {
                broken = verifyRows(seal, rowSource);
                if (broken == null) {
                    rowsChecked += seal.rowCount();
                }
            }
            if (broken != null) {
                return new Result(false, checked, rowsChecked, purged, seal.id(), seal.seq(), broken,
                    headSeq, headHash, List.copyOf(keyIds), false);
            }
            if (seal.keyId() != null) {
                keyIds.add(seal.keyId());
            }
            checked++;
            expectedPrev = seal.sealHash();
            expectedSeq = seal.seq() + 1;
            headSeq = seal.seq();
            headHash = seal.sealHash();
        }
        return new Result(true, checked, rowsChecked, purged, null, null, null, headSeq, headHash,
            List.copyOf(keyIds), false);
    }

    private static String verifyRows(AuditSealRecord seal, RowSource rowSource) {
        if (seal.rowCount() < 1) {
            return "seal covers no rows";
        }
        List<AuditSealHasher.Row> rows = rowSource.rowsFor(seal);
        if (rows.size() > seal.rowCount()) {
            // rows is capped at rowCount + 1, so this means "at least one extra row"
            return "range holds more than the " + seal.rowCount() + " row(s) the seal covers: an audit row was "
                + "inserted into it, or committed late by a transaction that outlived the seal settle window "
                + "(check the row timestamps)";
        }
        if (rows.size() != seal.rowCount()) {
            return "range holds " + rows.size() + " row(s) but the seal covers " + seal.rowCount()
                + " (rows were deleted)";
        }
        if (!AuditSealHasher.rangeHash(rows).equals(seal.rangeHash())) {
            return "range_hash mismatch: rows in this range were modified, reordered, inserted or removed";
        }
        if (!rows.get(0).id().equals(seal.firstRowId().toString())
            || !rows.get(rows.size() - 1).id().equals(seal.lastRowId().toString())) {
            return "first/last row ids do not match the seal";
        }
        return null;
    }
}
