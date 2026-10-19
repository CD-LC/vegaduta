package com.agenticai.core.agents.version;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one hash function every version bundle is fingerprinted with:
 * SHA-256 over the exact UTF-8 bytes stored in the {@code *_json} column,
 * rendered lowercase hex.
 *
 * <p>Hashing the stored document rather than a re-canonicalized form is the
 * point: the writers ({@code AgentConfigSnapshotCodec}, {@code
 * WorkflowVersionService}) already serialize deterministically (sorted
 * collections), so byte equality and semantic equality coincide, and the
 * migration V342 backfill {@code encode(sha256(<col>::bytea),'hex')}
 * produces bit-identical values for rows Java wrote.
 *
 * <p>{@code NoSuchAlgorithmException} cannot happen - SHA-256 is a
 * guaranteed JDK algorithm - so it is wrapped rather than declared, keeping
 * every call site clean.
 */
public final class VersionHashing {

    private VersionHashing() {
    }

    public static String sha256Hex(String document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(document.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable - impossible on a compliant JVM", e);
        }
    }
}
