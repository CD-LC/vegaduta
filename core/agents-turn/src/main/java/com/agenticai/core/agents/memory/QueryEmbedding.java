package com.agenticai.core.agents.memory;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The embedding of one query text, computed on first use and then shared. A chat turn recalls from
 * two memory stores with the same user message (the agent's private facts, then the tenant-shared
 * ones); each used to embed that message through the sidecar on its own, so the same text crossed
 * the network twice. Both now read one of these.
 *
 * <p>Only a successful embedding is kept. A failure is rethrown to the caller that asked and the
 * next caller tries again, exactly as two independent embeds would have behaved.
 *
 * <p>The sidecar call is a blocking network call, and chat turns run on virtual threads. It must
 * therefore never run inside a {@code synchronized} block: a virtual thread that blocks while it
 * holds a monitor pins its carrier thread, and a handful of such turns pins them all, so threads
 * that would release database connections cannot run and the pool drains. A {@link ReentrantLock}
 * parks the virtual thread instead.
 */
public final class QueryEmbedding {

    private final String text;
    private final Function<String, float[]> embedder;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile float[] vector;

    QueryEmbedding(String text, Function<String, float[]> embedder) {
        this.text = text;
        this.embedder = embedder;
    }

    /** The query text this embedding is for. */
    public String text() {
        return text;
    }

    /** The embedding, computed by the first caller; callers must not modify the returned array. */
    public float[] vector() {
        float[] ready = vector;
        if (ready != null) {
            return ready;
        }
        lock.lock();
        try {
            if (vector == null) {
                vector = embedder.apply(text);
            }
            return vector;
        } finally {
            lock.unlock();
        }
    }
}
