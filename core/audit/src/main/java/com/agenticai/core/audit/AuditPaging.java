package com.agenticai.core.audit;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

/**
 * {@code ?limit=&offset=} clamping and the {@code X-Total-Count} / {@code X-Has-More} headers for the
 * audit module's list endpoints (audit log, notification bell). This mirrors the contract in
 * {@code docs/api/LIST-ENDPOINT-PAGING.md}; the shared {@code ListPaging} helper lives in
 * {@code core/tenancy}, which depends on this leaf module, so it cannot be reused here.
 */
public final class AuditPaging {

    private AuditPaging() {}

    /** Offsets above this return an empty page instead of overflowing Spring Data's int arithmetic. */
    public static final long MAX_OFFSET = 1_000_000_000L;

    public static int clampLimit(Integer limit, int def, int max) {
        if (limit == null) {
            return def;
        }
        return Math.min(Math.max(limit, 1), max);
    }

    public static long clampOffset(Long offset) {
        return offset == null ? 0 : Math.min(Math.max(offset, 0), MAX_OFFSET);
    }

    /**
     * Wraps {@code rows} with the truncation headers. The count only runs when it can change the
     * answer: a short first page already is the total.
     */
    public static <T> ResponseEntity<List<T>> respond(List<T> rows, int size, long offset, LongSupplier count) {
        long seen = offset + rows.size();
        long total;
        if (offset == 0 && rows.size() < size) {
            total = seen;
        } else if (rows.isEmpty()) {
            total = count.getAsLong();
        } else {
            total = Math.max(count.getAsLong(), seen);
        }
        return withHeaders(rows, total, seen);
    }

    /** Same headers when the total is already known (e.g. from a Spring Data Page). */
    public static <T> ResponseEntity<List<T>> respondWithTotal(List<T> rows, long offset, long total) {
        long seen = offset + rows.size();
        return withHeaders(rows, Math.max(total, seen), seen);
    }

    private static <T> ResponseEntity<List<T>> withHeaders(List<T> rows, long total, long seen) {
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .header("X-Has-More", String.valueOf(total > seen))
            .body(rows);
    }

    /** A {@link Pageable} carrying an exact row offset ({@code PageRequest} only offsets by whole pages). */
    public static Pageable offsetLimit(long offset, int limit, Sort sort) {
        return new OffsetLimit(offset, limit, sort == null ? Sort.unsorted() : sort);
    }

    private record OffsetLimit(long offset, int limit, Sort sort) implements Pageable {

        OffsetLimit {
            if (offset < 0 || limit < 1) {
                throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
            }
        }

        @Override
        public int getPageNumber() {
            return (int) (offset / limit);
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return sort;
        }

        @Override
        public Pageable next() {
            return new OffsetLimit(offset + limit, limit, sort);
        }

        @Override
        public Pageable previousOrFirst() {
            return new OffsetLimit(Math.max(0, offset - limit), limit, sort);
        }

        @Override
        public Pageable first() {
            return new OffsetLimit(0, limit, sort);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            return new OffsetLimit((long) Math.max(0, pageNumber) * limit, limit, sort);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }

        @Override
        public Optional<Pageable> toOptional() {
            return Optional.of(this);
        }
    }
}
