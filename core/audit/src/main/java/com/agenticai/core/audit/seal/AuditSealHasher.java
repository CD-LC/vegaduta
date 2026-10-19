package com.agenticai.core.audit.seal;

import com.agenticai.core.audit.AuditLogEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure, deterministic hashing for tamper-evident audit seals (algorithm {@value #ALGORITHM}).
 * No Spring, no I/O: the exact byte layout below is a published contract that
 * {@code scripts/verify-audit-export.py} re-implements, so a customer can verify an export offline.
 * Changing ANY byte here is a breaking change that needs a new {@link #ALGORITHM} id.
 *
 * <h3>Row encoding</h3>
 * One audit row is the concatenation, in this fixed order, of its immutable fields
 * {@code id, actor_id, action_type, target_type, target_id, outcome, detail}, each as
 * <ul>
 *   <li>{@code 0x00} for SQL NULL, or</li>
 *   <li>{@code 0x01 || uint32-big-endian(byteLength) || UTF-8 bytes}</li>
 * </ul>
 * followed by {@code created_at} as a signed 64-bit big-endian count of microseconds since the epoch.
 * Length prefixes make the encoding unambiguous ("ab"+"c" never collides with "a"+"bc"), and the
 * null marker keeps NULL distinct from the empty string. The row digest is SHA-256 of those bytes.
 *
 * <h3>Range hash (sequential digest, not a Merkle tree)</h3>
 * {@code state = SHA256("audit-range-v1")}; for each row in order {@code state = SHA256(state ||
 * rowDigest)}; the range hash is the lowercase hex of the final state. A sequential fold was chosen
 * over a Merkle root because verification always re-reads the whole range anyway (no inclusion proofs
 * are offered), it is order-sensitive by construction (reorder/insert/delete all change it) and it is
 * trivial to re-implement in any language.
 *
 * <h3>Seal hash</h3>
 * SHA-256 (lowercase hex) of the UTF-8 string made of the following lines joined by {@code '\n'}:
 * {@code algorithm, tenantScope, seq, prevSealHash, rangeHash, rowCount, firstRowId, firstRowMicros,
 * lastRowId, lastRowMicros}. {@code prevSealHash} chains the seals; the first seal uses {@link
 * #GENESIS_HASH}.
 */
public final class AuditSealHasher {

    /** Algorithm id recorded on every seal and part of the seal-hash preimage. */
    public static final String ALGORITHM = "audit-seal-v1";

    /** {@code prev_seal_hash} of the first seal of a tenant. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final byte[] RANGE_SEED = "audit-range-v1".getBytes(StandardCharsets.UTF_8);

    private AuditSealHasher() {
    }

    /**
     * The hash-relevant, immutable fields of one audit row. {@code id} is the lowercase canonical UUID
     * string, {@code outcome} the enum name, {@code createdAtMicros} microseconds since the epoch
     * (Postgres timestamptz precision, so a value read back from the database is exact).
     */
    public record Row(
        String id,
        String actorId,
        String actionType,
        String targetType,
        String targetId,
        String outcome,
        String detail,
        long createdAtMicros
    ) {
    }

    /** Microseconds since the epoch, truncating anything finer (the database cannot hold finer). */
    public static long toMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }

    /** Inverse of {@link #toMicros(Instant)}. */
    public static Instant fromMicros(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
    }

    public static Row rowOf(AuditLogEntry entry) {
        return new Row(
            entry.getId().toString(),
            entry.getActorId(),
            entry.getActionType(),
            entry.getTargetType(),
            entry.getTargetId(),
            entry.getOutcome() == null ? null : entry.getOutcome().name(),
            entry.getDetail(),
            toMicros(entry.getCreatedAt())
        );
    }

    /** The canonical byte encoding of one row (see class javadoc). */
    public static byte[] canonicalRow(Row row) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(buffer);
            writeField(out, row.id());
            writeField(out, row.actorId());
            writeField(out, row.actionType());
            writeField(out, row.targetType());
            writeField(out, row.targetId());
            writeField(out, row.outcome());
            writeField(out, row.detail());
            out.writeLong(row.createdAtMicros());
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a ByteArrayOutputStream never throws
        }
    }

    private static void writeField(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static byte[] rowDigest(Row row) {
        return sha256(canonicalRow(row));
    }

    /** Sequential digest over {@code rows} in the order given; a seal never covers zero rows. */
    public static String rangeHash(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("a seal range cannot be empty");
        }
        byte[] state = sha256(RANGE_SEED);
        for (Row row : rows) {
            byte[] digest = rowDigest(row);
            byte[] joined = new byte[state.length + digest.length];
            System.arraycopy(state, 0, joined, 0, state.length);
            System.arraycopy(digest, 0, joined, state.length, digest.length);
            state = sha256(joined);
        }
        return HexFormat.of().formatHex(state);
    }

    /** The seal hash over the seal's identity, chain link, range hash and range metadata. */
    public static String sealHash(
        String tenantScope,
        long seq,
        String prevSealHash,
        String rangeHash,
        int rowCount,
        String firstRowId,
        long firstRowMicros,
        String lastRowId,
        long lastRowMicros
    ) {
        String preimage = String.join(
            "\n",
            ALGORITHM,
            tenantScope,
            Long.toString(seq),
            prevSealHash,
            rangeHash,
            Integer.toString(rowCount),
            firstRowId,
            Long.toString(firstRowMicros),
            lastRowId,
            Long.toString(lastRowMicros)
        );
        return HexFormat.of().formatHex(sha256(preimage.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
