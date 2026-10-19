package com.agenticai.core.voice.realtime;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serialises outbound frames on one {@link java.net.http.WebSocket}.
 *
 * <p>{@code java.net.http.WebSocket} allows only one outstanding send: calling
 * {@code sendText} before the previous send's future has completed makes the
 * NEW future complete exceptionally with {@link IllegalStateException}. The
 * realtime providers used to fire {@code sendText} per microphone chunk and
 * ignore the returned future (Error Prone FutureReturnValueIgnored), so any
 * chunk that arrived while the previous one was still being written was
 * dropped with no log at all. Every send now waits for the one before it.
 *
 * <p>Bounded: if the upstream socket stalls, frames beyond
 * {@link #MAX_PENDING} are dropped (and logged) instead of buffering audio
 * without limit. Failures are logged, never thrown - a realtime audio frame
 * that cannot be delivered is not worth failing the caller's thread over.
 */
final class WebSocketSendQueue {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSendQueue.class);

    /** About 5 s of 100 ms microphone chunks. */
    static final int MAX_PENDING = 64;

    private final WebSocket webSocket;
    private final String label;
    private final AtomicInteger pending = new AtomicInteger();
    private final Object lock = new Object();
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    WebSocketSendQueue(WebSocket webSocket, String label) {
        this.webSocket = webSocket;
        this.label = label;
    }

    /** Queues a complete text frame behind any send still in flight. */
    void sendText(String text) {
        enqueue(ws -> ws.sendText(text, true), "text frame");
    }

    /** Queues a normal-closure close frame behind any send still in flight. */
    void sendClose() {
        enqueue(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"), "close frame");
    }

    /** Sends in flight or waiting; exposed for tests. */
    int pending() {
        return pending.get();
    }

    private void enqueue(Function<WebSocket, CompletableFuture<WebSocket>> send, String what) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            log.warn("{} upstream socket is not draining - dropping a {}", label, what);
            return;
        }
        synchronized (lock) {
            tail = tail
                .handle((ignored, previousFailure) -> null)
                .thenCompose(ignored -> send.apply(webSocket))
                .whenComplete((ignored, failure) -> {
                    pending.decrementAndGet();
                    if (failure != null) {
                        log.debug("{} {} send failed: {}", label, what, failure.toString());
                    }
                });
        }
    }
}
