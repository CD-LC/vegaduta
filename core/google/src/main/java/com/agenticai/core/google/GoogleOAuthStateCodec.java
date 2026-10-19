package com.agenticai.core.google;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.google.GoogleOAuthService.DecodedState;
import java.time.Instant;

/**
 * Encrypts and decodes the OAuth {@code state} value Google round-trips back to the callback. The
 * payload is {@code schema[|u=<subject>][|p=<purpose>]|n=<nonce>|<epoch>}, AES-256-GCM encrypted
 * via {@link EncryptionService}, so a tampered state fails to decrypt at all and the embedded
 * timestamp caps how long a captured state could be reused for (see {@link
 * #STATE_MAX_AGE_SECONDS}). Split out of {@link GoogleOAuthService}, whose class javadoc explains
 * why the tenant (and, since V215, the consenting subject) must travel inside the state.
 */
final class GoogleOAuthStateCodec {

    // 10 minutes - ample for a user to complete Google's consent screen.
    static final long STATE_MAX_AGE_SECONDS = 600;

    /**
     * Marks the subject segment of a V215-format state payload (see {@link #encode}). A distinct
     * prefix - rather than "third field means subject" - is what lets {@link #decode} stay tolerant
     * of the pre-V215 two-field format for states already in flight at deploy time: an old state's
     * payload has no {@code |u=} segment and simply decodes as a tenant-wide connect, which is
     * exactly what its minting admin asked for.
     */
    private static final String STATE_SUBJECT_MARKER = "u=";

    /** Marks the SEC-018 binding-nonce segment of a state payload. */
    private static final String STATE_NONCE_MARKER = "n=";

    /** Marker prefix for the purpose segment of a state payload - see {@link #encode}. */
    private static final String STATE_PURPOSE_MARKER = "p=";

    private final EncryptionService encryptionService;

    GoogleOAuthStateCodec(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    /**
     * Full decode of the round-tripped state - schema plus (since V215) the consenting subject.
     * Backward-tolerant: a pre-V215 two-field payload ({@code schema|epoch}) decodes as a
     * tenant-wide connect rather than throwing, so states minted just before a deploy still
     * complete correctly during their up-to-10-minute validity window.
     *
     * @throws GoogleOAuthStateException if tampered, malformed, or expired.
     */
    DecodedState decode(String state) {
        String decrypted;
        try {
            decrypted = encryptionService.decrypt(state);
        } catch (Exception e) {
            throw new GoogleOAuthStateException("Could not verify the OAuth state - please try connecting again.");
        }
        int epochSeparator = decrypted.lastIndexOf('|');
        if (epochSeparator < 0) {
            throw new GoogleOAuthStateException("Malformed OAuth state.");
        }
        long issuedAtEpochSeconds;
        try {
            issuedAtEpochSeconds = Long.parseLong(decrypted.substring(epochSeparator + 1));
        } catch (NumberFormatException e) {
            throw new GoogleOAuthStateException("Malformed OAuth state.");
        }
        if (Instant.now().getEpochSecond() - issuedAtEpochSeconds > STATE_MAX_AGE_SECONDS) {
            throw new GoogleOAuthStateException("This connection attempt expired - please try connecting again.");
        }
        String head = decrypted.substring(0, epochSeparator);
        // Peel optional trailing marker segments ("p=<purpose>", "u=<subject>")
        // off the head, in whichever order they were encoded. Anything left is
        // the schema - schema names are sanitized to [a-z0-9_] and subjects
        // are UUIDs, so neither can contain '|' or collide with a marker.
        String purpose = null;
        String subject = null;
        String nonce = null;
        while (true) {
            int separator = head.lastIndexOf('|');
            if (separator < 0) {
                break;
            }
            String tail = head.substring(separator + 1);
            if (tail.startsWith(STATE_NONCE_MARKER)) {
                nonce = tail.substring(STATE_NONCE_MARKER.length());
            } else if (tail.startsWith(STATE_PURPOSE_MARKER)) {
                purpose = tail.substring(STATE_PURPOSE_MARKER.length());
            } else if (tail.startsWith(STATE_SUBJECT_MARKER)) {
                subject = tail.substring(STATE_SUBJECT_MARKER.length());
            } else {
                break;
            }
            head = head.substring(0, separator);
        }
        // A state with no marker segments at all is either a pre-V215 state or
        // a tenant-wide base connect minted today - the whole head is the
        // schema and the connect is tenant-wide either way.
        return new DecodedState(head, subject == null || subject.isBlank() ? null : subject, purpose, nonce);
    }

    String encode(String schema, String subjectOrNull, String purposeOrNull, String nonce) {
        // Pre-V215 payload shape (schema|epoch) is deliberately still emitted
        // for tenant-wide base connects - it keeps the common case
        // byte-identical to what it always was, and decode treats
        // "no subject/purpose segment" as a tenant-wide base connect, so old
        // and new tenant-wide states are indistinguishable on the way back in.
        StringBuilder head = new StringBuilder(schema);
        if (subjectOrNull != null) {
            head.append('|').append(STATE_SUBJECT_MARKER).append(subjectOrNull);
        }
        if (purposeOrNull != null) {
            head.append('|').append(STATE_PURPOSE_MARKER).append(purposeOrNull);
        }
        // SEC-018 binding nonce (base64url, never contains '|'); checked by verifyBinding at the callback.
        head.append('|').append(STATE_NONCE_MARKER).append(nonce);
        String payload = head.append('|').append(Instant.now().getEpochSecond()).toString();
        // EncryptionService's base64 output can contain '+' and '/' - fine as
        // a query param value since GoogleOAuthClient.buildAuthorizationUrl
        // percent-encodes it, and Spring transparently percent-decodes the
        // `state` request param on the way back in, so no extra encoding
        // layer is needed here.
        return encryptionService.encrypt(payload);
    }
}
