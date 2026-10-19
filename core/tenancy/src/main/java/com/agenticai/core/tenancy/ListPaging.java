package com.agenticai.core.tenancy;

import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.http.ResponseEntity;

/**
 * Shared {@code ?limit=&offset=} handling for the capped list endpoints (runs, contacts, KB
 * documents, approvals): clamping, and the {@code X-Total-Count} / {@code X-Has-More} headers.
 */
public final class ListPaging {

    private ListPaging() {}

    /**
     * Offsets above this return an empty page instead of reaching Spring Data / Hibernate, whose
     * offset arithmetic is int-based ({@code Math.toIntExact} throws ArithmeticException -> 500).
     */
    public static final long MAX_OFFSET = 1_000_000_000L;

    /**
     * Whether a request WITHOUT {@code ?limit=} is capped at the endpoint default. Default false:
     * non-web consumers (mobile apps, the outreach MCP server, the SDK) never send a limit and were
     * promised the complete list; silently truncating them loses data. Clients that page (the web
     * app) always send an explicit limit. Set {@code LIST_DEFAULT_CAP=true} (or {@code
     * -Dagenticai.list.default-cap=true}) to cap limit-less requests at the default too.
     */
    public static boolean defaultCapEnabled() {
        String v =
                System.getProperty("agenticai.list.default-cap", System.getenv("LIST_DEFAULT_CAP"));
        return v != null && Boolean.parseBoolean(v.trim());
    }

    /**
     * An explicit {@code limit} is clamped to [1, max]. Absent: {@code def} when the default cap is
     * enabled, otherwise unbounded (the pre-pagination behaviour).
     */
    public static int limit(Integer limit, int def, int max) {
        if (limit == null) {
            return defaultCapEnabled() ? def : Integer.MAX_VALUE;
        }
        return Math.min(Math.max(limit, 1), max);
    }

    /**
     * Negative or absent offsets mean 0; absurdly large ones are clamped to {@link #MAX_OFFSET}
     * (empty page).
     */
    public static long offset(Long offset) {
        return offset == null ? 0 : Math.min(Math.max(offset, 0), MAX_OFFSET);
    }

    public static OffsetLimitRequest page(Integer limit, Long offset, int def, int max) {
        return new OffsetLimitRequest(offset(offset), limit(limit, def, max));
    }

    /**
     * Wraps {@code rows} with the truncation headers. The count query only runs when it can change
     * the answer: a short first page already IS the total, so unconditional per-poll counts are
     * avoided.
     */
    public static <T> ResponseEntity<List<T>> respond(
            List<T> rows, int size, long offset, LongSupplier count) {
        long seen = offset + rows.size();
        long total;
        if (offset == 0 && rows.size() < size) {
            total = seen;
        } else if (rows.isEmpty()) {
            total = count.getAsLong(); // paged past the end: report the real total, not the offset
        } else {
            total = Math.max(count.getAsLong(), seen);
        }
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .header("X-Has-More", String.valueOf(total > seen))
                .body(rows);
    }

    /**
     * Pages a list that is already fully in memory (per-row visibility filters, bounded lists such
     * as the tenant's agents): slices the newest-first {@code all} and sends the headers with the
     * exact total. No {@code limit} = the complete list, as before.
     */
    public static <T> ResponseEntity<List<T>> window(
            List<T> all, Integer limit, Long offset, int def, int max) {
        int size = limit(limit, def, max);
        long skip = offset(offset);
        int from = (int) Math.min(skip, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(all.size()))
                .header("X-Has-More", String.valueOf(to < all.size()))
                .body(all.subList(from, to));
    }
}
