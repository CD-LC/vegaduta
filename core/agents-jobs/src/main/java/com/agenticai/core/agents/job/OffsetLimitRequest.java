package com.agenticai.core.agents.job;

import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} that carries an EXACT row offset. {@code PageRequest} can only express offsets that
 * are whole multiples of the page size, so {@code ?limit=20&offset=30} used to silently return rows 20-39.
 * Spring Data reads {@link #getOffset()} and {@link #getPageSize()} for the query, which is all this needs.
 */
final class OffsetLimitRequest implements Pageable {

    private final long offset;
    private final int limit;

    OffsetLimitRequest(long offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
        }
        this.offset = offset;
        this.limit = limit;
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
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetLimitRequest(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return new OffsetLimitRequest(Math.max(0, offset - limit), limit);
    }

    @Override
    public Pageable first() {
        return new OffsetLimitRequest(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimitRequest((long) Math.max(0, pageNumber) * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }

    @Override
    public Optional<Pageable> toOptional() {
        return Optional.of(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OffsetLimitRequest other && other.offset == offset && other.limit == limit;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(offset) * 31 + limit;
    }

    @Override
    public String toString() {
        return "OffsetLimitRequest[offset=" + offset + ", limit=" + limit + "]";
    }
}
