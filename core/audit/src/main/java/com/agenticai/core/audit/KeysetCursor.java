package com.agenticai.core.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor for keyset ("seek") paging of newest-first feeds ordered by {@code (timestamp DESC,
 * id DESC)}: the audit log and workflow run history. Unlike {@code offset}, the cost of the next page
 * does not grow with depth, and rows inserted while a client pages do not shift what it sees.
 *
 * <p>The token is base64url of {@code v1:<epoch micros>:<uuid>}, the last row of the page. Clients
 * must treat it as opaque; the version prefix lets the format change without breaking held tokens.
 * Lives here, next to {@link AuditPaging}, because audit is the lowest module both feeds depend on.
 */
public record KeysetCursor(Instant timestamp, UUID id) {

    /** Response header carrying the cursor of the next page; absent on the last page. */
    public static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";

    private static final String VERSION = "v1";

    public String encode() {
        long micros =
                Math.addExact(
                        Math.multiplyExact(timestamp.getEpochSecond(), 1_000_000L),
                        timestamp.getNano() / 1_000L);
        String raw = VERSION + ":" + micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a client-supplied cursor.
     *
     * @throws IllegalArgumentException when it is not a cursor this server issued (answered as 400)
     */
    public static KeysetCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token.strip()), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            long micros = Long.parseLong(parts[1]);
            Instant ts =
                    Instant.ofEpochSecond(
                            Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
            return new KeysetCursor(ts, UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid cursor: pass back the X-Next-Cursor value unchanged", e);
        }
    }
}
