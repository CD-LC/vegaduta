package com.agenticai.core.tenancy;

import java.util.Iterator;
import java.util.List;
import org.springframework.data.redis.core.Cursor;

/** In-memory {@link Cursor} that records whether it was closed and can fail mid-scan. */
final class FakeCursor<T> implements Cursor<T> {

    private final Iterator<T> it;
    private final int failAfter;
    private int served;
    private boolean closed;

    FakeCursor(List<T> keys) {
        this(keys, -1);
    }

    /**
     * Throws {@link IllegalStateException} from {@code hasNext} once {@code failAfter} keys were
     * served.
     */
    FakeCursor(List<T> keys, int failAfter) {
        this.it = keys.iterator();
        this.failAfter = failAfter;
    }

    @Override
    public boolean hasNext() {
        if (failAfter >= 0 && served >= failAfter) {
            throw new IllegalStateException("connection reset mid-scan");
        }
        return it.hasNext();
    }

    @Override
    public T next() {
        served++;
        return it.next();
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public CursorId getId() {
        return CursorId.initial();
    }

    @Override
    public long getCursorId() {
        return 0;
    }

    @Override
    public long getPosition() {
        return served;
    }
}
