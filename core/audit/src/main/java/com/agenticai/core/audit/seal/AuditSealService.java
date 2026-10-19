package com.agenticai.core.audit.seal;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportBundle;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportKey;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportPredecessor;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportRow;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportSeal;
import com.agenticai.core.audit.seal.AuditSealDtos.SealPage;
import com.agenticai.core.audit.seal.AuditSealDtos.SealView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tamper-evident audit log: periodic SEALS over the (immutable, append-only) {@code audit_log_entry} rows.
 *
 * <p><b>Why seals instead of a per-row hash.</b> A hash column chained row to row would force every audited
 * request to read the previous row and take a lock, serialising the whole request path. A seal instead covers
 * a contiguous range written since the previous seal, off the hot path: the audit table is untouched. The
 * hash, chain and signature formats are on {@link AuditSealHasher}; the offline verifier is
 * {@code scripts/verify-audit-export.py}.
 *
 * <p><b>Ordering and the settle delay.</b> Rows are ordered by {@code (created_at, id)} in Postgres.
 * {@code created_at} is stamped when the entity is built, not when its transaction commits, so a row can
 * become visible slightly after newer rows. Sealing therefore only covers rows older than
 * {@code app.audit.seal.settle-seconds} (default 120 s) AND older than the oldest transaction that is still
 * open on another connection ({@code pg_stat_activity.xact_start}), so a long agent or approval turn that
 * writes an audit row and commits minutes later cannot land inside a range that was already sealed (seals
 * are immutable, so such a row could never be repaired). That deferral is capped at
 * {@code app.audit.seal.max-defer-seconds} (default 3600 s) so one stuck idle-in-transaction session cannot
 * stop sealing forever; a row committed even later than that is reported by verification as "extra rows in
 * a sealed range", a message that names late commit as a possible cause alongside tampering.
 *
 * <p><b>Retention.</b> No audit purge exists today (a V75 trigger forbids DELETE, and tenant erasure drops
 * the whole schema, seals included). {@link #markPurgedBefore} is the hook a future purge job must call
 * BEFORE deleting rows: purged ranges then verify by seal chain + signature only. See that method.
 *
 * <p>Tenant scope comes from the thread's Hibernate tenant routing (the sweep runs per tenant through
 * {@code TenantSchemaIterator}; HTTP calls carry the caller's tenant); this class never reads a tenant from
 * a request.
 */
@Service
public class AuditSealService {

    /** Hard ceiling regardless of config: bounds the memory of one sealing transaction. */
    static final int MAX_ROWS_PER_SEAL_CEILING = 50_000;
    /** Seals checked per verify call; beyond it the result is flagged truncated. */
    static final int MAX_VERIFY_SEALS = 2_000;
    /** Live rows hashed per verify call; beyond it the result is flagged truncated (narrow the window). */
    static final int MAX_VERIFY_ROWS = 200_000;
    /** Approximate characters of row text per export; beyond it the export is flagged truncated. */
    static final long MAX_EXPORT_CHARS = 64L * 1024 * 1024;
    /** Rows put in one export; beyond it the export is flagged truncated. */
    static final int MAX_EXPORT_ROWS = 100_000;
    static final int MAX_LIST_PAGE_SIZE = 200;

    private static final Instant FAR_FUTURE = Instant.parse("2999-12-31T23:59:59Z");

    private final AuditLogRepository auditLogRepository;
    private final AuditSealRepository sealRepository;
    private final ObjectProvider<AuditSealSigner> signerProvider;
    private final int maxRowsPerSeal;
    private final long settleSeconds;
    private final long maxDeferSeconds;

    /** Used to detach the rows of a checked seal so memory stays bounded to one seal; null in unit tests. */
    @PersistenceContext
    private EntityManager entityManager;

    public AuditSealService(
        AuditLogRepository auditLogRepository,
        AuditSealRepository sealRepository,
        ObjectProvider<AuditSealSigner> signerProvider,
        @Value("${app.audit.seal.max-rows-per-seal:5000}") int maxRowsPerSeal,
        @Value("${app.audit.seal.settle-seconds:120}") long settleSeconds,
        @Value("${app.audit.seal.max-defer-seconds:3600}") long maxDeferSeconds
    ) {
        this.auditLogRepository = auditLogRepository;
        this.sealRepository = sealRepository;
        this.signerProvider = signerProvider;
        this.maxRowsPerSeal = Math.max(1, Math.min(maxRowsPerSeal, MAX_ROWS_PER_SEAL_CEILING));
        this.settleSeconds = Math.max(0, settleSeconds);
        this.maxDeferSeconds = Math.max(this.settleSeconds, maxDeferSeconds);
    }

    /** True when a signing key is available; without one {@link #sealNextBatch} creates nothing. */
    public boolean signerAvailable() {
        AuditSealSigner signer = signerProvider.getIfAvailable();
        return signer != null && signer.isAvailable();
    }

    // ---- sealing ------------------------------------------------------------------------------------

    /**
     * Seals the next batch (at most {@code max-rows-per-seal}) of settled, not-yet-sealed rows of the current
     * tenant schema. One short transaction: one bounded read, hashing, one insert. Returns empty when there is
     * nothing to seal or no signer is available. A concurrent sealer (another replica) loses on the unique
     * {@code seq} constraint and the caller sees a DataIntegrityViolationException - the advisory lock around
     * the sweep makes that rare, the constraint makes it harmless.
     *
     * @param tenantScope label bound into the seal hash (the schema name from the sweep)
     * @param now         injected clock reading, so tests and the sweep share one instant
     */
    @Transactional
    public Optional<AuditSeal> sealNextBatch(String tenantScope, Instant now) {
        AuditSealSigner signer = signerProvider.getIfAvailable();
        if (signer == null || !signer.isAvailable()) {
            return Optional.empty();
        }
        Instant settledBefore = settledBefore(now);
        Optional<AuditSeal> previous = sealRepository.findTopByOrderBySeqDesc();
        List<AuditLogEntry> entries = previous.isPresent()
            ? auditLogRepository.findUnsealedAfter(
                settledBefore, previous.get().getLastRowAt(), previous.get().getLastRowId(), maxRowsPerSeal)
            : auditLogRepository.findFirstUnsealed(settledBefore, maxRowsPerSeal);
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        List<AuditSealHasher.Row> rows = entries.stream().map(AuditSealHasher::rowOf).toList();
        AuditLogEntry first = entries.get(0);
        AuditLogEntry last = entries.get(entries.size() - 1);
        long seq = previous.map(p -> p.getSeq() + 1).orElse(1L);
        String prevHash = previous.map(AuditSeal::getSealHash).orElse(AuditSealHasher.GENESIS_HASH);
        String rangeHash = AuditSealHasher.rangeHash(rows);
        String sealHash = AuditSealHasher.sealHash(
            tenantScope, seq, prevHash, rangeHash, rows.size(),
            rows.get(0).id(), rows.get(0).createdAtMicros(),
            rows.get(rows.size() - 1).id(), rows.get(rows.size() - 1).createdAtMicros());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", AuditSealHasher.ALGORITHM);
        payload.put("tenant", tenantScope);
        payload.put("seq", seq);
        payload.put("seal_hash", sealHash);
        payload.put("range_hash", rangeHash);
        payload.put("prev", prevHash);
        Optional<AuditSealSigner.Signed> signed = signer.sign(payload);
        if (signed.isEmpty()) {
            return Optional.empty();
        }

        AuditSeal seal = new AuditSeal(
            tenantScope, seq,
            first.getId(), AuditSealHasher.fromMicros(rows.get(0).createdAtMicros()),
            last.getId(), AuditSealHasher.fromMicros(rows.get(rows.size() - 1).createdAtMicros()),
            rows.size(), rangeHash, prevHash, sealHash, AuditSealHasher.ALGORITHM,
            signed.get().compactJws(), signed.get().keyId(), signed.get().publicKeyBase64Url(), now);
        return Optional.of(sealRepository.save(seal));
    }

    // ---- reading ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SealPage list(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_LIST_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Page<AuditSeal> result = sealRepository.findAllByOrderBySeqDesc(PageRequest.of(safePage, safeSize));
        return new SealPage(
            result.getContent().stream().map(SealView::from).toList(), safePage, safeSize, result.getTotalElements());
    }

    /**
     * Recomputes hashes from the LIVE rows and checks chain links and signatures for every seal whose range
     * overlaps [{@code from}, {@code to}] (either bound may be null = open). See {@link AuditSealVerifier}
     * for what is and is not detectable; {@code truncated} is set when more than {@value #MAX_VERIFY_SEALS}
     * seals matched or more than {@value #MAX_VERIFY_ROWS} live rows would be hashed, and only the oldest
     * were checked (narrow the window and call again).
     */
    @Transactional(readOnly = true)
    public AuditSealVerifier.Result verify(Instant from, Instant to) {
        Window window = window(from, to, MAX_VERIFY_SEALS);
        if (window.seals.isEmpty()) {
            return new AuditSealVerifier.Result(true, 0, 0, 0, null, null, null, null, null, List.of(), false);
        }
        // Bound the work of one call: at most MAX_VERIFY_ROWS live rows are hashed (whole seals only).
        long rows = 0;
        int keep = 0;
        boolean truncated = window.truncated;
        for (AuditSeal seal : window.seals) {
            long add = seal.isPurged() ? 0 : seal.getRowCount();
            if (keep > 0 && rows + add > MAX_VERIFY_ROWS) {
                truncated = true;
                break;
            }
            rows += add;
            keep++;
        }
        List<AuditSealRecord> records = window.seals.subList(0, keep).stream().map(AuditSeal::toRecord).toList();
        AuditSealVerifier.Result result = AuditSealVerifier.verify(
            records, window.firstPrevHash, this::liveRows, AuditSealSignatures::verify);
        return result.withTruncated(truncated);
    }

    /**
     * A self-contained JSON bundle {@code scripts/verify-audit-export.py} can verify with no platform access:
     * the seals, the hash-relevant row fields of every non-purged range, the signer key(s) and a description
     * of the algorithm. Capped at {@value #MAX_EXPORT_ROWS} rows and about
     * {@value #MAX_EXPORT_CHARS} characters of row text (flagged {@code truncated}; narrow the window).
     */
    @Transactional(readOnly = true)
    public ExportBundle export(Instant from, Instant to, Instant generatedAt) {
        Window window = window(from, to, MAX_VERIFY_SEALS);
        List<AuditSeal> included = new ArrayList<>();
        List<ExportRow> exportRows = new ArrayList<>();
        boolean truncated = window.truncated;
        long chars = 0;
        for (AuditSeal seal : window.seals) {
            if (!seal.isPurged()) {
                if (!included.isEmpty() && exportRows.size() + seal.getRowCount() > MAX_EXPORT_ROWS) {
                    truncated = true;
                    break;
                }
                List<AuditSealHasher.Row> live = liveRows(seal.toRecord());
                long sealChars = 0;
                for (AuditSealHasher.Row row : live) {
                    sealChars += approxChars(row);
                }
                if (!included.isEmpty() && chars + sealChars > MAX_EXPORT_CHARS) {
                    truncated = true;
                    break;
                }
                chars += sealChars;
                for (AuditSealHasher.Row row : live) {
                    exportRows.add(ExportRow.from(row));
                }
            }
            included.add(seal);
        }

        Map<String, ExportKey> keys = new LinkedHashMap<>();
        for (AuditSeal seal : included) {
            if (seal.getKeyId() != null && seal.getPublicKey() != null) {
                keys.putIfAbsent(seal.getKeyId(), new ExportKey(seal.getKeyId(), "Ed25519", seal.getPublicKey()));
            }
        }
        ExportPredecessor predecessor = null;
        if (!included.isEmpty() && included.get(0).getSeq() > 1 && window.predecessor != null) {
            predecessor = new ExportPredecessor(window.predecessor.getSeq(), window.predecessor.getSealHash());
        }
        return new ExportBundle(
            AuditSealDtos.EXPORT_FORMAT, generatedAt, from, to, truncated, algorithmDescription(), predecessor,
            List.copyOf(keys.values()), included.stream().map(ExportSeal::from).toList(), exportRows);
    }

    // ---- retention ----------------------------------------------------------------------------------

    /**
     * Retention hook. Flags every seal whose entire range ends before {@code cutoff} as purged, so
     * verification no longer expects its rows (it keeps checking the seal chain and signature, and reports
     * how many ranges were checked that way). A purge job MUST call this before it deletes rows, never after:
     * until then a missing row is - correctly - reported as tampering.
     *
     * <p>Honest limit: the flag is itself an unsigned database update, so someone with DB write access could
     * delete rows and flag their seal. The seal chain and signature stay intact, but those rows are then no
     * longer independently attested; {@code purgedSeals} in every verify result and the {@code purged} flag
     * in every export keep that visible. No purge exists today, so nothing calls this yet.
     *
     * @return the number of seals newly flagged
     */
    @Transactional
    public int markPurgedBefore(Instant cutoff, Instant now) {
        return sealRepository.markPurgedBefore(cutoff, now);
    }

    // ---- internals ----------------------------------------------------------------------------------

    private List<AuditSealHasher.Row> liveRows(AuditSealRecord seal) {
        // +1 so one extra (inserted) row is visible as a count mismatch without loading an unbounded range
        int limit = (int) Math.min((long) seal.rowCount(), MAX_EXPORT_ROWS) + 1;
        List<AuditSealHasher.Row> rows = auditLogRepository.findInSealRange(
                seal.firstRowAt(), seal.firstRowId(), seal.lastRowAt(), seal.lastRowId(), limit)
            .stream()
            .map(AuditSealHasher::rowOf)
            .toList();
        // The entities are only needed to build the immutable Rows above; detach them so a verify or export
        // over thousands of seals holds one seal's rows in memory, not all of them.
        if (entityManager != null) {
            entityManager.clear();
        }
        return rows;
    }

    private static long approxChars(AuditSealHasher.Row r) {
        return 64L + len(r.actorId()) + len(r.actionType()) + len(r.targetType()) + len(r.targetId())
            + len(r.outcome()) + len(r.detail());
    }

    private static long len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * The newest instant whose rows may be sealed now: {@code now - settle-seconds}, pulled back to the start
     * of the oldest open transaction on another connection (it may still commit rows stamped after that
     * start), but never further back than {@code max-defer-seconds}. Clock note: {@code xact_start} is the
     * database clock and {@code now} the application clock; skew only shifts the margin slightly.
     */
    private Instant settledBefore(Instant now) {
        Instant horizon = now.minusSeconds(settleSeconds);
        Instant oldestOpen = auditLogRepository.findOldestOpenTransactionStartEpochSeconds()
            .map(epochSeconds -> Instant.ofEpochSecond(0, Math.round(epochSeconds * 1_000_000d) * 1_000L))
            .orElse(null);
        if (oldestOpen != null && oldestOpen.isBefore(horizon)) {
            Instant floor = now.minusSeconds(maxDeferSeconds);
            horizon = oldestOpen.isAfter(floor) ? oldestOpen : floor;
        }
        return horizon;
    }

    private Window window(Instant from, Instant to, int maxSeals) {
        Instant lower = from != null ? from : Instant.EPOCH;
        Instant upper = to != null ? to : FAR_FUTURE;
        if (lower.isAfter(upper)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        List<AuditSeal> found = sealRepository
            .findByLastRowAtGreaterThanEqualAndFirstRowAtLessThanEqualOrderBySeqAsc(
                lower, upper, PageRequest.of(0, maxSeals + 1));
        boolean truncated = found.size() > maxSeals;
        List<AuditSeal> seals = truncated ? new ArrayList<>(found.subList(0, maxSeals)) : found;
        AuditSeal predecessor = null;
        String firstPrevHash = null;
        if (!seals.isEmpty()) {
            long firstSeq = seals.get(0).getSeq();
            if (firstSeq <= 1) {
                firstPrevHash = AuditSealHasher.GENESIS_HASH;
            } else {
                predecessor = sealRepository.findBySeq(firstSeq - 1).orElse(null);
                firstPrevHash = predecessor == null ? null : predecessor.getSealHash();
            }
        }
        return new Window(seals, predecessor, firstPrevHash, truncated);
    }

    private static Map<String, String> algorithmDescription() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("id", AuditSealHasher.ALGORITHM);
        d.put("rowEncoding", "per field: 0x00 if null else 0x01 || uint32-BE(len) || UTF-8; fields in order "
            + "id, actorId, actionType, targetType, targetId, outcome, detail; then int64-BE createdAtMicros");
        d.put("rowDigest", "SHA-256(rowEncoding)");
        d.put("rangeHash", "state = SHA-256('audit-range-v1'); for each row in order: state = SHA-256(state || "
            + "rowDigest); lowercase hex of the final state");
        d.put("sealHash", "SHA-256 over UTF-8 lines joined by LF: algorithm, tenantScope, seq, prevSealHash, "
            + "rangeHash, rowCount, firstRowId, firstRowAtMicros, lastRowId, lastRowAtMicros; lowercase hex; "
            + "the first seal's prevSealHash is 64 zeros");
        d.put("signature", "compact JWS, alg EdDSA (Ed25519), payload {v, tenant, seq, seal_hash, range_hash, "
            + "prev}; verify against the platform's published key (/.well-known/jwks.json), not the embedded one");
        d.put("verifier", "scripts/verify-audit-export.py (Python standard library only)");
        return d;
    }

    private record Window(List<AuditSeal> seals, AuditSeal predecessor, String firstPrevHash, boolean truncated) {
    }
}
